package dev.ppmb.intellihub.app.dto.chat;

/**
 * 聊天补全统一响应 DTO。
 */
public class ChatCompletionResponse {

    /** 剥离推理思维链后的纯净正文文本 */
    private String content;

    /** 模型的原始推理思维链（如 DeepSeek-R1 / Qwen 等模型的 reasoning_content，若有） */
    private String reasoningContent;

    /** 原始输出完整文本（包含思维链标签等） */
    private String rawContent;

    /** 实际调用的渠道或路由组 */
    private String channel;

    /** 实际命中的模型标识 */
    private String model;

    /** 提问耗费的 Token 数量 */
    private Long promptTokens;

    /** 生成耗费的 Token 数量 */
    private Long completionTokens;

    /** 总消耗 Token 数量 */
    private Long totalTokens;

    /** 服务端全流程调用耗时（毫秒） */
    private Long latencyMs;

    public ChatCompletionResponse() {}

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

    public String getRawContent() {
        return rawContent;
    }

    public void setRawContent(String rawContent) {
        this.rawContent = rawContent;
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

    public Long getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Long promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Long getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Long completionTokens) {
        this.completionTokens = completionTokens;
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
}
