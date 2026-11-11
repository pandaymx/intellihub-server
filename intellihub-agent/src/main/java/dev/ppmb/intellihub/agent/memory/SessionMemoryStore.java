package dev.ppmb.intellihub.agent.memory;

import java.util.List;
import org.springframework.ai.chat.messages.Message;

/**
 * 多轮对话会话记忆持久化 SPI（Session Memory Store）。
 *
 * <p>用于在服务端统一纳管与持久化多轮对话上下文（{@link Message} 列表），支持滑动窗口裁剪、跨进程共享及重启恢复。
 */
public interface SessionMemoryStore {

    /**
     * 获取指定会话的全部已保存消息列表。
     */
    List<Message> getMessages(String sessionId);

    /**
     * 获取指定会话最近 {@code maxMessages} 条消息（用于上下文窗口裁剪）。
     */
    default List<Message> getRecentMessages(String sessionId, int maxMessages) {
        List<Message> all = getMessages(sessionId);
        if (maxMessages <= 0 || all.size() <= maxMessages) {
            return all;
        }
        return all.subList(all.size() - maxMessages, all.size());
    }

    /**
     * 向指定会话追加新的一轮或多条消息。
     */
    void appendMessages(String sessionId, List<Message> messages);

    /**
     * 覆盖指定会话的消息历史。
     */
    void replaceMessages(String sessionId, List<Message> messages);

    /**
     * 清空并移除指定会话。
     */
    void clearSession(String sessionId);

    /**
     * 列出当前已保存的所有会话 ID。
     */
    List<String> listSessionIds();
}
