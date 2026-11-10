package dev.ppmb.intellihub.agent.engine;

import java.util.List;
import java.util.Objects;

/**
 * Agent 多步运行完整返回结果（包含最终回答、终止原因、完整 ReAct 轨迹、Token 消耗及可选的挂起快照）。
 *
 * @param output 剥离 {@code <think>} 后的最终回答文本（或挂起提示）
 * @param stopReason 循环终止原因（{@link AgentStopReason}）
 * @param steps 每一轮 ReAct 推理与工具调用的完整审计轨迹
 * @param totalIterations 实际执行的推理轮次数
 * @param totalToolCalls 实际触发的工具调用总次数
 * @param totalTokens 累计消耗的总 Token 数
 * @param checkpoint 当 {@code stopReason == AWAITING_APPROVAL} 时的挂起恢复快照（其余情况为 {@code null}）
 * @param totalLatencyMs 端到端总耗时（毫秒）
 */
public record AgentRunResult(
        String output,
        AgentStopReason stopReason,
        List<AgentStepTrace> steps,
        int totalIterations,
        int totalToolCalls,
        long totalTokens,
        AgentCheckpoint checkpoint,
        long totalLatencyMs) {

    public AgentRunResult(
            String output,
            AgentStopReason stopReason,
            List<AgentStepTrace> steps,
            int totalIterations,
            int totalToolCalls,
            long totalLatencyMs) {
        this(output, stopReason, steps, totalIterations, totalToolCalls, 0L, null, totalLatencyMs);
    }

    public AgentRunResult {
        Objects.requireNonNull(stopReason, "stopReason 不能为空");
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public boolean isCompletedNormally() {
        return stopReason == AgentStopReason.COMPLETED;
    }

    public boolean isAwaitingApproval() {
        return stopReason == AgentStopReason.AWAITING_APPROVAL && checkpoint != null;
    }
}
