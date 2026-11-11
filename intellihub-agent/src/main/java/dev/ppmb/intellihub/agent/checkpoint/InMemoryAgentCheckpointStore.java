package dev.ppmb.intellihub.agent.checkpoint;

import dev.ppmb.intellihub.agent.engine.AgentCheckpoint;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认的线程安全内存级 {@link AgentCheckpointStore} 实现（零外部依赖，适合本地单测与轻量运行）。
 */
public class InMemoryAgentCheckpointStore implements AgentCheckpointStore {

    private final Map<String, AgentCheckpoint> store = new ConcurrentHashMap<>();

    @Override
    public void save(AgentCheckpoint checkpoint) {
        if (checkpoint == null
                || checkpoint.checkpointId() == null
                || checkpoint.checkpointId().isBlank()) {
            return;
        }
        store.put(checkpoint.checkpointId().strip(), checkpoint);
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
        return Optional.ofNullable(store.remove(checkpointId.strip()));
    }

    @Override
    public int size() {
        return store.size();
    }
}
