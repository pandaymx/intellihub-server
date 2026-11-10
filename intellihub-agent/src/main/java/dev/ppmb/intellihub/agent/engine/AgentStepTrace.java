package dev.ppmb.intellihub.agent.engine;

import java.util.List;

/**
 * Agent 单轮 ReAct 思考与工具调用轨迹记录（Step Trace）。
 *
 * @param iteration 当前轮次编号（从 1 开始）
 * @param thought 本轮模型的推理思考内容（含 {@code <think>} 提取或伴随文本）
 * @param toolCalls 本轮触发的工具调用明细列表
 * @param latencyMs 本轮耗时（毫秒）
 */
public record AgentStepTrace(
        int iteration, String thought, List<ToolCallExecutionRecord> toolCalls, long latencyMs) {

    public AgentStepTrace {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    /**
     * 单次工具调用的执行审计明细。
     *
     * @param callId 工具调用 ID
     * @param toolName 工具名称
     * @param arguments 传入的 JSON 参数
     * @param observation 返回给模型的观察结果
     * @param success 是否执行成功
     * @param loopBlocked 是否因重复调用被防死循环守卫拦截
     * @param approvalRejected 是否因高风险操作被审批门禁拒绝
     * @param durationMs 工具单次执行耗时（毫秒）
     */
    public record ToolCallExecutionRecord(
            String callId,
            String toolName,
            String arguments,
            String observation,
            boolean success,
            boolean loopBlocked,
            boolean approvalRejected,
            long durationMs) {}
}
