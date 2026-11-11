package dev.ppmb.intellihub.agent.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.ai.chat.messages.Message;

/**
 * 线程安全内存级 {@link SessionMemoryStore} 实现（支持按单会话最大消息条数自动滑动裁剪）。
 */
public class InMemorySessionMemoryStore implements SessionMemoryStore {

    public static final int DEFAULT_MAX_MESSAGES_PER_SESSION = 100;

    private final int maxMessagesPerSession;
    private final Map<String, List<Message>> store = new ConcurrentHashMap<>();

    public InMemorySessionMemoryStore() {
        this(DEFAULT_MAX_MESSAGES_PER_SESSION);
    }

    public InMemorySessionMemoryStore(int maxMessagesPerSession) {
        this.maxMessagesPerSession =
                maxMessagesPerSession > 0
                        ? maxMessagesPerSession
                        : DEFAULT_MAX_MESSAGES_PER_SESSION;
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
                    return trimWindow(merged);
                });
    }

    @Override
    public void replaceMessages(String sessionId, List<Message> messages) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        if (messages == null || messages.isEmpty()) {
            store.remove(sessionId.strip());
            return;
        }
        store.put(sessionId.strip(), trimWindow(new ArrayList<>(messages)));
    }

    @Override
    public void clearSession(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            store.remove(sessionId.strip());
        }
    }

    @Override
    public List<String> listSessionIds() {
        return List.copyOf(store.keySet());
    }

    private List<Message> trimWindow(List<Message> list) {
        if (list.size() <= maxMessagesPerSession) {
            return List.copyOf(list);
        }
        return List.copyOf(list.subList(list.size() - maxMessagesPerSession, list.size()));
    }
}
