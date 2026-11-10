package dev.ppmb.intellihub.app.dto.prompt;

/**
 * 提示词安全渲染响应 DTO。
 */
public class PromptRenderResponse {

    private String templateId;
    private String version;
    private String systemMessage;
    private String userMessage;

    public PromptRenderResponse() {}

    public PromptRenderResponse(
            String templateId, String version, String systemMessage, String userMessage) {
        this.templateId = templateId;
        this.version = version;
        this.systemMessage = systemMessage;
        this.userMessage = userMessage;
    }

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(String templateId) {
        this.templateId = templateId;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getSystemMessage() {
        return systemMessage;
    }

    public void setSystemMessage(String systemMessage) {
        this.systemMessage = systemMessage;
    }

    public String getUserMessage() {
        return userMessage;
    }

    public void setUserMessage(String userMessage) {
        this.userMessage = userMessage;
    }
}
