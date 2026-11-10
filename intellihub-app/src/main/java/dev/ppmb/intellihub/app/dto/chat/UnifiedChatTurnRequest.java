package dev.ppmb.intellihub.app.dto.chat;

import java.util.List;

/**
 * 一体化多模态对话工作台请求 DTO（单次对话回合同时支持多轮上下文、RAG 知识增强、ReAct 工具调用、防注入护栏与结构化抽取）。
 */
public class UnifiedChatTurnRequest {

    /** 当前用户输入消息 */
    private String message;

    /** 多轮历史消息上下文 */
    private List<HistoryMessage> history;

    /** 可选指定模型渠道或高可用路由组（默认使用 demo-mock 或全局默认路由） */
    private String channel;

    /** 是否开启 ReAct 智能体自动工具调用 */
    private Boolean enableAgent;

    /** 是否开启 Hybrid RAG 知识库检索增强 */
    private Boolean enableRag;

    /** 是否开启结构化 JSON 数据提取卡片 */
    private Boolean enableStructuredExtract;

    /** 结构化提取的目标字段列表 */
    private List<String> targetFields;

    /** 可选知识库租户过滤标识 */
    private String tenantId;

    /** 可选知识库 ID 过滤标识 */
    private String kbId;

    /** 可选采样温度 */
    private Double temperature;

    public UnifiedChatTurnRequest() {}

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public List<HistoryMessage> getHistory() {
        return history;
    }

    public void setHistory(List<HistoryMessage> history) {
        this.history = history;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public Boolean getEnableAgent() {
        return enableAgent;
    }

    public void setEnableAgent(Boolean enableAgent) {
        this.enableAgent = enableAgent;
    }

    public Boolean getEnableRag() {
        return enableRag;
    }

    public void setEnableRag(Boolean enableRag) {
        this.enableRag = enableRag;
    }

    public Boolean getEnableStructuredExtract() {
        return enableStructuredExtract;
    }

    public void setEnableStructuredExtract(Boolean enableStructuredExtract) {
        this.enableStructuredExtract = enableStructuredExtract;
    }

    public List<String> getTargetFields() {
        return targetFields;
    }

    public void setTargetFields(List<String> targetFields) {
        this.targetFields = targetFields;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getKbId() {
        return kbId;
    }

    public void setKbId(String kbId) {
        this.kbId = kbId;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public record HistoryMessage(String role, String content) {}
}
