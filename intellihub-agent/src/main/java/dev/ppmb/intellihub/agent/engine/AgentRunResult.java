package dev.ppmb.intellihub.agent.engine;

import java.util.List;
import java.util.Objects;

/**
 * Agent 多步运行完整返回结果（包含最终回答、终止原因、完整 ReAct 轨迹与性能指标）。
 *
 * @param output 剥离 {@code <think>} 后的最终回答文本
 * @param stopReason 循环终止原因（{@link AgentStopReason}）
 * @param steps 每一轮 ReAct 推理与工具调用的完整审计轨迹
 * @param totalIterations 实际执行的推理轮次数
 * @param totalToolCalls 实际触发的工具调用总次数
 * @param totalLatencyMs 端到端总耗时（毫秒）
 */
public record AgentRunResult(
        String output,
        AgentStopReason stopReason,
        List<AgentStepTrace> steps,
        int totalIterations,
        int totalToolCalls,
        long totalLatencyMs) {

    public AgentRunResult {
        Objects.requireNonNull(stopReason, "stopReason 不能为空");
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public boolean isCompletedNormally() {
        return stopReason == AgentStopReason.COMPLETED;
    }
}
