package dev.ppmb.intellihub.app.dto.agent;

import java.util.List;
import java.util.Map;

/**
 * 运行 ReAct 智能体请求 DTO。
 */
public class AgentRunApiRequest {

    /** 核心目标任务描述（必填） */
    private String userGoal;

    /** 可选自定义系统提示词 */
    private String systemPrompt;

    /** 可选指定模型渠道或高可用路由组 */
    private String channel;

    /** 可选白名单启用工具列表（缺省自动启用全部已注册工具） */
    private List<String> enabledTools;

    /** 可选下传给各工具的外部上下文 Map（如当前用户ID、租户ID等） */
    private Map<String, Object> toolContext;

    /** 可选最大推理轮次（默认 8） */
    private Integer maxIterations;

    /** 可选最大工具调用次数（默认 16） */
    private Integer maxTotalToolCalls;

    /** 可选相同参数防死循环重复调用上限（默认 2） */
    private Integer maxDuplicateToolCalls;

    /** 可选全局 Token 预算上限（默认 32000） */
    private Long maxTotalTokens;

    /** 可选全局最大执行时长（秒，默认 60） */
    private Long maxExecutionDurationSeconds;

    public AgentRunApiRequest() {}

    public AgentRunApiRequest(String userGoal) {
        this.userGoal = userGoal;
    }

    public String getUserGoal() {
        return userGoal;
    }

    public void setUserGoal(String userGoal) {
        this.userGoal = userGoal;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public List<String> getEnabledTools() {
        return enabledTools;
    }

    public void setEnabledTools(List<String> enabledTools) {
        this.enabledTools = enabledTools;
    }

    public Map<String, Object> getToolContext() {
        return toolContext;
    }

    public void setToolContext(Map<String, Object> toolContext) {
        this.toolContext = toolContext;
    }

    public Integer getMaxIterations() {
        return maxIterations;
    }

    public void setMaxIterations(Integer maxIterations) {
        this.maxIterations = maxIterations;
    }

    public Integer getMaxTotalToolCalls() {
        return maxTotalToolCalls;
    }

    public void setMaxTotalToolCalls(Integer maxTotalToolCalls) {
        this.maxTotalToolCalls = maxTotalToolCalls;
    }

    public Integer getMaxDuplicateToolCalls() {
        return maxDuplicateToolCalls;
    }

    public void setMaxDuplicateToolCalls(Integer maxDuplicateToolCalls) {
        this.maxDuplicateToolCalls = maxDuplicateToolCalls;
    }

    public Long getMaxTotalTokens() {
        return maxTotalTokens;
    }

    public void setMaxTotalTokens(Long maxTotalTokens) {
        this.maxTotalTokens = maxTotalTokens;
    }

    public Long getMaxExecutionDurationSeconds() {
        return maxExecutionDurationSeconds;
    }

    public void setMaxExecutionDurationSeconds(Long maxExecutionDurationSeconds) {
        this.maxExecutionDurationSeconds = maxExecutionDurationSeconds;
    }
}
