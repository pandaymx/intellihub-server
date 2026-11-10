package dev.ppmb.intellihub.app.dto.agent;

import dev.ppmb.intellihub.agent.engine.AgentRunResult;
import dev.ppmb.intellihub.agent.engine.AgentStopReason;
import java.util.List;

/**
 * Agent 运行结果响应 DTO。
 */
public class AgentRunApiResponse {

    /** 智能体最终输出文本（或审批挂起提示） */
    private String output;

    /** 终止原因（COMPLETED, MAX_ITERATIONS, BUDGET_EXHAUSTED, AWAITING_APPROVAL 等） */
    private AgentStopReason stopReason;

    /** 是否等待人工审批挂起 */
    private boolean awaitingApproval;

    /** 挂起快照 ID（用于后续调用 /api/v1/agent/resume 恢复执行） */
    private String checkpointId;

    /** 实际迭代轮次 */
    private int totalIterations;

    /** 实际工具调用次数 */
    private int totalToolCalls;

    /** 累计消耗的 Token 数量 */
    private long totalTokens;

    /** 全流程耗时（毫秒） */
    private long totalLatencyMs;

    /** 所使用的模型渠道 */
    private String channel;

    /** 详细思考轨迹列表 */
    private List<AgentStepTraceDto> steps;

    public AgentRunApiResponse() {}

    public static AgentRunApiResponse fromDomain(AgentRunResult result, String channel) {
        if (result == null) {
            return null;
        }
        AgentRunApiResponse dto = new AgentRunApiResponse();
        dto.setOutput(result.output());
        dto.setStopReason(result.stopReason());
        dto.setAwaitingApproval(result.isAwaitingApproval());
        if (result.checkpoint() != null) {
            dto.setCheckpointId(result.checkpoint().checkpointId());
        }
        dto.setTotalIterations(result.totalIterations());
        dto.setTotalToolCalls(result.totalToolCalls());
        dto.setTotalTokens(result.totalTokens());
        dto.setTotalLatencyMs(result.totalLatencyMs());
        dto.setChannel(channel);
        if (result.steps() != null) {
            dto.setSteps(result.steps().stream().map(AgentStepTraceDto::fromDomain).toList());
        }
        return dto;
    }

    public String getOutput() {
        return output;
    }

    public void setOutput(String output) {
        this.output = output;
    }

    public AgentStopReason getStopReason() {
        return stopReason;
    }

    public void setStopReason(AgentStopReason stopReason) {
        this.stopReason = stopReason;
    }

    public boolean isAwaitingApproval() {
        return awaitingApproval;
    }

    public void setAwaitingApproval(boolean awaitingApproval) {
        this.awaitingApproval = awaitingApproval;
    }

    public String getCheckpointId() {
        return checkpointId;
    }

    public void setCheckpointId(String checkpointId) {
        this.checkpointId = checkpointId;
    }

    public int getTotalIterations() {
        return totalIterations;
    }

    public void setTotalIterations(int totalIterations) {
        this.totalIterations = totalIterations;
    }

    public int getTotalToolCalls() {
        return totalToolCalls;
    }

    public void setTotalToolCalls(int totalToolCalls) {
        this.totalToolCalls = totalToolCalls;
    }

    public long getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(long totalTokens) {
        this.totalTokens = totalTokens;
    }

    public long getTotalLatencyMs() {
        return totalLatencyMs;
    }

    public void setTotalLatencyMs(long totalLatencyMs) {
        this.totalLatencyMs = totalLatencyMs;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public List<AgentStepTraceDto> getSteps() {
        return steps;
    }

    public void setSteps(List<AgentStepTraceDto> steps) {
        this.steps = steps;
    }
}
