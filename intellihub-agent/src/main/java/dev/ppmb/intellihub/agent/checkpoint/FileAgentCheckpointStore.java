package dev.ppmb.intellihub.agent.checkpoint;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ppmb.intellihub.agent.engine.AgentCheckpoint;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于本地原子文件快照（Write-Temp-and-Rename）的 {@link AgentCheckpointStore} 实现。
 *
 * <p>当服务在未额外部署 PostgreSQL 的轻量环境运行时，也能确保 {@code AWAITING_APPROVAL} 人工审批挂起状态在 JVM 重启后不丢失。
 */
public class FileAgentCheckpointStore implements AgentCheckpointStore {

    private static final Logger log = LoggerFactory.getLogger(FileAgentCheckpointStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path persistencePath;
    private final Map<String, AgentCheckpoint> store = new ConcurrentHashMap<>();
    private final Object lock = new Object();

    public FileAgentCheckpointStore(Path persistencePath) {
        this.persistencePath = Objects.requireNonNull(persistencePath, "persistencePath 不能为空");
        loadFromDisk();
    }

    @Override
    public void save(AgentCheckpoint checkpoint) {
        if (checkpoint == null
                || checkpoint.checkpointId() == null
                || checkpoint.checkpointId().isBlank()) {
            return;
        }
        store.put(checkpoint.checkpointId().strip(), checkpoint);
        saveToDisk();
    }

    @Override
    public Optional<AgentCheckpoint> findById(String checkpointId) {
        if (checkpointId == null || checkpointId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(store.get(checkpointId.strip()));
    }

    @Override
    public Optional<AgentCheckpoint> remove(String checkpointId) {
        if (checkpointId == null || checkpointId.isBlank()) {
            return Optional.empty();
        }
        AgentCheckpoint removed = store.remove(checkpointId.strip());
        if (removed != null) {
            saveToDisk();
        }
        return Optional.ofNullable(removed);
    }

    @Override
    public int size() {
        return store.size();
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
                    try {
                        AgentCheckpoint cp =
                                AgentCheckpointSerializer.deserializeCheckpoint(entry.getValue());
                        store.put(entry.getKey(), cp);
                    } catch (Exception ex) {
                        log.warn(
                                "[FileAgentCheckpointStore] 跳过无法解析的快照 [{}]: {}",
                                entry.getKey(),
                                ex.getMessage());
                    }
                }
                log.info(
                        "[FileAgentCheckpointStore] 已从 [{}] 恢复 {} 个挂起审批快照",
                        persistencePath.toAbsolutePath(),
                        store.size());
            } catch (Exception ex) {
                log.warn(
                        "[FileAgentCheckpointStore] 读取挂起快照文件 [{}] 失败: {}",
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
                Map<String, String> serialized = new LinkedHashMap<>();
                for (Map.Entry<String, AgentCheckpoint> entry : store.entrySet()) {
                    serialized.put(
                            entry.getKey(),
                            AgentCheckpointSerializer.serializeCheckpoint(entry.getValue()));
                }
                Path tmp =
                        persistencePath.resolveSibling(
                                persistencePath.getFileName().toString() + ".tmp");
                MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), serialized);
                Files.move(
                        tmp,
                        persistencePath,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ex) {
                log.warn(
                        "[FileAgentCheckpointStore] 持久化写入快照文件 [{}] 失败: {}",
                        persistencePath,
                        ex.getMessage());
            }
        }
    }
}
