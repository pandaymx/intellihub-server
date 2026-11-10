package dev.ppmb.intellihub.app.dto.chat;

import dev.ppmb.intellihub.agent.engine.AgentStopReason;
import dev.ppmb.intellihub.app.dto.agent.AgentStepTraceDto;
import dev.ppmb.intellihub.app.dto.rag.ScoredDocumentDto;
import java.util.List;
import java.util.Map;

/**
 * 一体化对话工作台响应 DTO（聚合正文回复、思考过程、ReAct 工具轨迹、人工审批快照、RAG 引用切片与结构化提取结果）。
 */
public class UnifiedChatTurnResponse {

    /** 剥离思维链后的最终正文回答 */
    private String content;

    /** 模型的深度思考过程（<think> 内容） */
    private String reasoningContent;

    /** 实际命中的渠道或路由组 */
    private String channel;

    /** 模型标识 */
    private String model;

    /** 累计消耗 Token 数 */
    private Long totalTokens;

    /** 端到端总耗时（毫秒） */
    private Long latencyMs;

    /** 终止状态（COMPLETED, AWAITING_APPROVAL 等） */
    private AgentStopReason stopReason;

    /** 是否处于高风险工具人工审批挂起状态 */
    private boolean awaitingApproval;

    /** 挂起恢复快照 ID */
    private String checkpointId;

    /** ReAct 思考与工具调用轨迹明细 */
    private List<AgentStepTraceDto> steps;

    /** RAG 检索命中的知识切片引用列表 */
    private List<ScoredDocumentDto> citations;

    /** 结构化抽取出的键值字典（当开启结构化提取时返回） */
    private Map<String, Object> structuredData;

    public UnifiedChatTurnResponse() {}

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getReasoningContent() {
        return reasoningContent;
    }

    public void setReasoningContent(String reasoningContent) {
        this.reasoningContent = reasoningContent;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Long getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(Long totalTokens) {
        this.totalTokens = totalTokens;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Long latencyMs) {
        this.latencyMs = latencyMs;
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

    public List<AgentStepTraceDto> getSteps() {
        return steps;
    }

    public void setSteps(List<AgentStepTraceDto> steps) {
        this.steps = steps;
    }

    public List<ScoredDocumentDto> getCitations() {
        return citations;
    }

    public void setCitations(List<ScoredDocumentDto> citations) {
        this.citations = citations;
    }

    public Map<String, Object> getStructuredData() {
        return structuredData;
    }

    public void setStructuredData(Map<String, Object> structuredData) {
        this.structuredData = structuredData;
    }
}
