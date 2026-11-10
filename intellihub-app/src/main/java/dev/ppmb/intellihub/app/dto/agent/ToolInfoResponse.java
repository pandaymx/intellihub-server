package dev.ppmb.intellihub.app.dto.agent;

/**
 * 已纳管工具信息 DTO。
 */
public class ToolInfoResponse {

    private String name;
    private String description;
    private String inputSchema;
    private boolean highRisk;

    public ToolInfoResponse() {}

    public ToolInfoResponse(String name, String description, String inputSchema, boolean highRisk) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
        this.highRisk = highRisk;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getInputSchema() {
        return inputSchema;
    }

    public void setInputSchema(String inputSchema) {
        this.inputSchema = inputSchema;
    }

    public boolean isHighRisk() {
        return highRisk;
    }

    public void setHighRisk(boolean highRisk) {
        this.highRisk = highRisk;
    }
}
