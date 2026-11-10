package dev.ppmb.intellihub.app.dto.prompt;

import java.util.Map;

/**
 * 提示词渲染请求 DTO。
 */
public class PromptRenderRequest {

    /** 模板版本号（可选，若不传则使用当前激活的最新版本） */
    private String version;

    /** 模板变量键值映射表 */
    private Map<String, Object> variables;

    public PromptRenderRequest() {}

    public PromptRenderRequest(Map<String, Object> variables) {
        this.variables = variables;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public Map<String, Object> getVariables() {
        return variables;
    }

    public void setVariables(Map<String, Object> variables) {
        this.variables = variables;
    }
}
