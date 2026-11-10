package dev.ppmb.intellihub.app.dto.agent;

import dev.ppmb.intellihub.agent.engine.AgentStepTrace;
import java.util.List;

/**
 * Agent 单轮思考与工具调用轨迹传输对象。
 */
public class AgentStepTraceDto {

    private int iteration;
    private String thought;
    private List<ToolCallDto> toolCalls;
    private long latencyMs;

    public AgentStepTraceDto() {}

    public static AgentStepTraceDto fromDomain(AgentStepTrace domain) {
        if (domain == null) {
            return null;
        }
        AgentStepTraceDto dto = new AgentStepTraceDto();
        dto.setIteration(domain.iteration());
        dto.setThought(domain.thought());
        dto.setLatencyMs(domain.latencyMs());
        if (domain.toolCalls() != null) {
            dto.setToolCalls(
                    domain.toolCalls().stream()
                            .map(tc ->
                                    new ToolCallDto(
                                            tc.callId(),
                                            tc.toolName(),
                                            tc.arguments(),
                                            tc.observation(),
                                            tc.success(),
                                            tc.loopBlocked(),
                                            tc.approvalRejected(),
                                            tc.durationMs()))
                            .toList());
        }
        return dto;
    }

    public int getIteration() {
        return iteration;
    }

    public void setIteration(int iteration) {
        this.iteration = iteration;
    }

    public String getThought() {
        return thought;
    }

    public void setThought(String thought) {
        this.thought = thought;
    }

    public List<ToolCallDto> getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(List<ToolCallDto> toolCalls) {
        this.toolCalls = toolCalls;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public record ToolCallDto(
            String callId,
            String toolName,
            String arguments,
            String observation,
            boolean success,
            boolean loopBlocked,
            boolean approvalRejected,
            long durationMs) {}
}
