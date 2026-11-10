package dev.ppmb.intellihub.agent.engine;

import dev.ppmb.intellihub.core.exception.IntelliHubExceptionUtil;
import java.time.Duration;

/**
 * Agent 多步 ReAct 执行预算与防死循环控制契约（Agent Execution Budget）。
 *
 * <ul>
 *   <li>{@code maxIterations}：最大推理轮次上限（默认 {@code 8}），防止无限循环对话；
 *   <li>{@code maxTotalToolCalls}：单次任务累计允许调用的工具总次数上限（默认 {@code 16}）；
 *   <li>{@code maxDuplicateToolCalls}：相同 {@code (toolName + arguments)} 指纹允许重复执行的上限（默认 {@code 2}），
 *       超过后直接触发 {@code [LOOP_GUARD]} 拦截并强制推进结论；
 *   <li>{@code maxExecutionDuration}：单次 Agent 运行的最大挂钟时长（默认 {@code 60s}）。
 * </ul>
 */
public final class AgentExecutionBudget {

    public static final int DEFAULT_MAX_ITERATIONS = 8;
    public static final int DEFAULT_MAX_TOTAL_TOOL_CALLS = 16;
    public static final int DEFAULT_MAX_DUPLICATE_TOOL_CALLS = 2;
    public static final Duration DEFAULT_MAX_EXECUTION_DURATION = Duration.ofSeconds(60);

    private final int maxIterations;
    private final int maxTotalToolCalls;
    private final int maxDuplicateToolCalls;
    private final Duration maxExecutionDuration;

    private AgentExecutionBudget(Builder builder) {
        if (builder.maxIterations <= 0) {
            throw IntelliHubExceptionUtil.invalidParamException(
                    "maxIterations 必须大于 0，当前值: {}", builder.maxIterations);
        }
        if (builder.maxTotalToolCalls <= 0) {
            throw IntelliHubExceptionUtil.invalidParamException(
                    "maxTotalToolCalls 必须大于 0，当前值: {}", builder.maxTotalToolCalls);
        }
        if (builder.maxDuplicateToolCalls <= 0) {
            throw IntelliHubExceptionUtil.invalidParamException(
                    "maxDuplicateToolCalls 必须大于 0，当前值: {}", builder.maxDuplicateToolCalls);
        }
        this.maxIterations = builder.maxIterations;
        this.maxTotalToolCalls = builder.maxTotalToolCalls;
        this.maxDuplicateToolCalls = builder.maxDuplicateToolCalls;
        this.maxExecutionDuration =
                builder.maxExecutionDuration != null && !builder.maxExecutionDuration.isNegative()
                        ? builder.maxExecutionDuration
                        : DEFAULT_MAX_EXECUTION_DURATION;
    }

    public static AgentExecutionBudget defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public int maxIterations() {
        return maxIterations;
    }

    public int maxTotalToolCalls() {
        return maxTotalToolCalls;
    }

    public int maxDuplicateToolCalls() {
        return maxDuplicateToolCalls;
    }

    public Duration maxExecutionDuration() {
        return maxExecutionDuration;
    }

    public static final class Builder {
        private int maxIterations = DEFAULT_MAX_ITERATIONS;
        private int maxTotalToolCalls = DEFAULT_MAX_TOTAL_TOOL_CALLS;
        private int maxDuplicateToolCalls = DEFAULT_MAX_DUPLICATE_TOOL_CALLS;
        private Duration maxExecutionDuration = DEFAULT_MAX_EXECUTION_DURATION;

        public Builder maxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        public Builder maxTotalToolCalls(int maxTotalToolCalls) {
            this.maxTotalToolCalls = maxTotalToolCalls;
            return this;
        }

        public Builder maxDuplicateToolCalls(int maxDuplicateToolCalls) {
            this.maxDuplicateToolCalls = maxDuplicateToolCalls;
            return this;
        }

        public Builder maxExecutionDuration(Duration maxExecutionDuration) {
            this.maxExecutionDuration = maxExecutionDuration;
            return this;
        }

        public AgentExecutionBudget build() {
            return new AgentExecutionBudget(this);
        }
    }
}
