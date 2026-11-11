package dev.ppmb.intellihub.agent.checkpoint;

import dev.ppmb.intellihub.agent.engine.AgentCheckpoint;
import java.util.Optional;

/**
 * ReAct 智能体异步人工审批（{@code AWAITING_APPROVAL}）断点快照持久化 SPI（Agent Checkpoint Store）。
 *
 * <p>用于支持高风险工具挂起后的状态跨进程共享与重启恢复（支持内存、本地原子文件及 JDBC/PostgreSQL 数据库实现）。
 */
public interface AgentCheckpointStore {

    /**
     * 保存或更新挂起快照。
     */
    void save(AgentCheckpoint checkpoint);

    /**
     * 按快照 ID 查询挂起快照（不删除）。
     */
    Optional<AgentCheckpoint> findById(String checkpointId);

    /**
     * 原子取出并移除指定 ID 的挂起快照（用于 {@code resume} 恢复执行时防止重复消费）。
     */
    Optional<AgentCheckpoint> remove(String checkpointId);

    /**
     * 当前存储中的挂起快照总数。
     */
    int size();
}
