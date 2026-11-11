package dev.ppmb.intellihub.agent.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ppmb.intellihub.agent.checkpoint.AgentCheckpointSerializer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;

/**
 * 基于本地原子文件快照（Write-Temp-and-Rename）的 {@link SessionMemoryStore} 实现。
 */
public class FileSessionMemoryStore implements SessionMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(FileSessionMemoryStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path persistencePath;
    private final int maxMessagesPerSession;
    private final Map<String, List<Message>> store = new ConcurrentHashMap<>();
    private final Object lock = new Object();

    public FileSessionMemoryStore(Path persistencePath) {
        this(persistencePath, InMemorySessionMemoryStore.DEFAULT_MAX_MESSAGES_PER_SESSION);
    }

    public FileSessionMemoryStore(Path persistencePath, int maxMessagesPerSession) {
        this.persistencePath = Objects.requireNonNull(persistencePath, "persistencePath 不能为空");
        this.maxMessagesPerSession =
                maxMessagesPerSession > 0
                        ? maxMessagesPerSession
                        : InMemorySessionMemoryStore.DEFAULT_MAX_MESSAGES_PER_SESSION;
        loadFromDisk();
    }

    @Override
    public List<Message> getMessages(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        List<Message> list = store.get(sessionId.strip());
        return list == null ? List.of() : List.copyOf(list);
    }

    @Override
    public void appendMessages(String sessionId, List<Message> messages) {
        if (sessionId == null || sessionId.isBlank() || messages == null || messages.isEmpty()) {
            return;
        }
        store.compute(
                sessionId.strip(),
                (k, existing) -> {
                    List<Message> merged =
                            existing != null ? new ArrayList<>(existing) : new ArrayList<>();
                    merged.addAll(messages);
                    if (merged.size() > maxMessagesPerSession) {
                        merged =
                                merged.subList(
                                        merged.size() - maxMessagesPerSession, merged.size());
                    }
                    return List.copyOf(merged);
                });
        saveToDisk();
    }

    @Override
    public void replaceMessages(String sessionId, List<Message> messages) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        if (messages == null || messages.isEmpty()) {
            store.remove(sessionId.strip());
        } else {
            List<Message> copy = new ArrayList<>(messages);
            if (copy.size() > maxMessagesPerSession) {
                copy = copy.subList(copy.size() - maxMessagesPerSession, copy.size());
            }
            store.put(sessionId.strip(), List.copyOf(copy));
        }
        saveToDisk();
    }

    @Override
    public void clearSession(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            if (store.remove(sessionId.strip()) != null) {
                saveToDisk();
            }
        }
    }

    @Override
    public List<String> listSessionIds() {
        return List.copyOf(store.keySet());
    }

    private void loadFromDisk() {
        if (!Files.exists(persistencePath)) {
            return;
        }
        synchronized (lock) {
            try {
                byte[] bytes = Files.readAllBytes(persistencePath);
                if (bytes.length == 0) {
                    return;
                }
                Map<String, String> rawMap =
                        MAPPER.readValue(bytes, new TypeReference<Map<String, String>>() {});
                for (Map.Entry<String, String> entry : rawMap.entrySet()) {
                    store.put(
                            entry.getKey(),
                            AgentCheckpointSerializer.deserializeMessages(entry.getValue()));
                }
            } catch (Exception ex) {
                log.warn(
                        "[FileSessionMemoryStore] 读取会话记忆文件 [{}] 失败: {}",
                        persistencePath,
                        ex.getMessage());
            }
        }
    }

    private void saveToDisk() {
        synchronized (lock) {
            try {
                Path parent = persistencePath.toAbsolutePath().getParent();
                if (parent != null && !Files.exists(parent)) {
                    Files.createDirectories(parent);
                }
                Map<String, String> raw = new LinkedHashMap<>();
                for (Map.Entry<String, List<Message>> entry : store.entrySet()) {
                    raw.put(
                            entry.getKey(),
                            AgentCheckpointSerializer.serializeMessages(entry.getValue()));
                }
                Path tmp =
                        persistencePath.resolveSibling(
                                persistencePath.getFileName().toString() + ".tmp");
                MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), raw);
                Files.move(
                        tmp,
                        persistencePath,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ex) {
                log.warn(
                        "[FileSessionMemoryStore] 写入会话记忆文件 [{}] 失败: {}",
                        persistencePath,
                        ex.getMessage());
            }
        }
    }
}
