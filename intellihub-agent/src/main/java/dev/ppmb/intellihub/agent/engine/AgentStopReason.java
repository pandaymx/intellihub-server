package dev.ppmb.intellihub.agent.engine;

/**
 * Agent 多步循环终止原因枚举。
 */
public enum AgentStopReason {

    /** 模型自然完成推理并给出最终回答（无需继续调用工具） */
    COMPLETED,

    /** 达到最大推理轮次上限（{@code maxIterations}），已触发总结收尾生成 */
    MAX_ITERATIONS_REACHED,

    /** 达到单次任务最大工具调用总次数上限（{@code maxTotalToolCalls}），已触发总结收尾生成 */
    MAX_TOOL_CALLS_REACHED,

    /** 超过最大挂钟执行时长限制（{@code maxExecutionDuration}） */
    TIMEOUT
}
