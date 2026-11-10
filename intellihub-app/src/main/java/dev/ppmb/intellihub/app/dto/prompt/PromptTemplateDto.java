package dev.ppmb.intellihub.app.dto.prompt;

import dev.ppmb.intellihub.prompt.template.PromptTemplateSpec;
import java.util.List;
import java.util.Map;

/**
 * 提示词模板规格传输对象。
 */
public class PromptTemplateDto {

    private String id;
    private String version;
    private String description;
    private String systemTemplate;
    private String userTemplate;
    private List<String> requiredVariables;
    private Map<String, Object> defaultVariables;
    private boolean guardInjection;
    private boolean wrapUserVariablesInXmlTag;

    public PromptTemplateDto() {}

    public static PromptTemplateDto fromSpec(PromptTemplateSpec spec) {
        if (spec == null) {
            return null;
        }
        PromptTemplateDto dto = new PromptTemplateDto();
        dto.setId(spec.id());
        dto.setVersion(spec.version());
        dto.setDescription(spec.description());
        dto.setSystemTemplate(spec.systemTemplate());
        dto.setUserTemplate(spec.userTemplate());
        dto.setRequiredVariables(
                spec.requiredVariables() != null
                        ? List.copyOf(spec.requiredVariables())
                        : List.of());
        dto.setDefaultVariables(
                spec.defaultVariables() != null ? Map.copyOf(spec.defaultVariables()) : Map.of());
        dto.setGuardInjection(spec.guardInjection());
        dto.setWrapUserVariablesInXmlTag(spec.wrapUserVariablesInXmlTag());
        return dto;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSystemTemplate() {
        return systemTemplate;
    }

    public void setSystemTemplate(String systemTemplate) {
        this.systemTemplate = systemTemplate;
    }

    public String getUserTemplate() {
        return userTemplate;
    }

    public void setUserTemplate(String userTemplate) {
        this.userTemplate = userTemplate;
    }

    public List<String> getRequiredVariables() {
        return requiredVariables;
    }

    public void setRequiredVariables(List<String> requiredVariables) {
        this.requiredVariables = requiredVariables;
    }

    public Map<String, Object> getDefaultVariables() {
        return defaultVariables;
    }

    public void setDefaultVariables(Map<String, Object> defaultVariables) {
        this.defaultVariables = defaultVariables;
    }

    public boolean isGuardInjection() {
        return guardInjection;
    }

    public void setGuardInjection(boolean guardInjection) {
        this.guardInjection = guardInjection;
    }

    public boolean isWrapUserVariablesInXmlTag() {
        return wrapUserVariablesInXmlTag;
    }

    public void setWrapUserVariablesInXmlTag(boolean wrapUserVariablesInXmlTag) {
        this.wrapUserVariablesInXmlTag = wrapUserVariablesInXmlTag;
    }
}
