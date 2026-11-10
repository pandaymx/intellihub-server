package dev.ppmb.intellihub.app.dto.chat;

/**
 * 聊天补全请求 DTO。
 */
public class ChatCompletionRequest {

    /** 核心用户提问 / 提示词 */
    private String prompt;

    /** 可选系统提示词（System Prompt） */
    private String systemPrompt;

    /** 可选指定模型渠道或高可用路由组名称（缺省自动使用全局默认路由组） */
    private String channel;

    /** 可选覆盖模型名称（如 deepseek-chat, qwen-plus 等） */
    private String model;

    /** 可选模型生成温度系数（0.0 ~ 2.0） */
    private Double temperature;

    /** 是否采用 Server-Sent Events (SSE) 流式传输 */
    private Boolean stream;

    public ChatCompletionRequest() {}

    public ChatCompletionRequest(String prompt) {
        this.prompt = prompt;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
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

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Boolean getStream() {
        return stream;
    }

    public void setStream(Boolean stream) {
        this.stream = stream;
    }
}
