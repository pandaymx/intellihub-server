package dev.ppmb.intellihub.prompt.template;

import dev.ppmb.intellihub.core.exception.IntelliHubExceptionUtil;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 版本化 Prompt 模板定义契约（支持 System/User 双模板、Few-Shot 示例、必填变量自动推导与默认值）。
 *
 * <ul>
 *   <li>自动识别模板中的 {@code {varName}} 合法标识符占位符（不会将内联 JSON 示例 {@code {"a": 1}} 误判为占位符）；
 *   <li>支持配置 {@link #wrapUserVariablesInXmlTag()} 将外部输入包裹在 XML 标签内，配合防注入守卫防止越狱攻击。
 * </ul>
 */
public final class PromptTemplateSpec {

    public static final String DEFAULT_VERSION = "v1";

    /** 仅匹配合法的变量占位符（如 {@code {user_query}}、{@code {context.summary}}），忽略含引号/冒号的 JSON 大括号 */
    public static final Pattern VARIABLE_PLACEHOLDER_PATTERN =
            Pattern.compile("\\{([a-zA-Z_][a-zA-Z0-9_.-]*)\\}");

    private final String id;
    private final String version;
    private final String description;
    private final String systemTemplate;
    private final String userTemplate;
    private final Set<String> requiredVariables;
    private final Map<String, Object> defaultVariables;
    private final List<FewShotExample> fewShotExamples;
    private final boolean guardInjection;
    private final boolean wrapUserVariablesInXmlTag;

    private PromptTemplateSpec(Builder builder) {
        if (builder.id == null || builder.id.isBlank()) {
            throw IntelliHubExceptionUtil.invalidParamException("PromptTemplateSpec id 不能为空");
        }
        if (builder.userTemplate == null || builder.userTemplate.isBlank()) {
            throw IntelliHubExceptionUtil.invalidParamException(
                    "PromptTemplateSpec [{}] 的 userTemplate 不能为空", builder.id);
        }
        this.id = builder.id.strip();
        this.version =
                builder.version == null || builder.version.isBlank()
                        ? DEFAULT_VERSION
                        : builder.version.strip();
        this.description = builder.description == null ? "" : builder.description.strip();
        this.systemTemplate =
                builder.systemTemplate != null && !builder.systemTemplate.isBlank()
                        ? builder.systemTemplate
                        : null;
        this.userTemplate = builder.userTemplate;
        this.defaultVariables =
                builder.defaultVariables.isEmpty()
                        ? Map.of()
                        : Collections.unmodifiableMap(
                                new LinkedHashMap<>(builder.defaultVariables));

        Set<String> resolvedRequired = new LinkedHashSet<>(builder.requiredVariables);
        if (builder.autoDetectRequiredVariables) {
            resolvedRequired.addAll(extractPlaceholders(this.systemTemplate));
            resolvedRequired.addAll(extractPlaceholders(this.userTemplate));
            resolvedRequired.removeAll(this.defaultVariables.keySet());
        }
        this.requiredVariables = Collections.unmodifiableSet(resolvedRequired);
        this.fewShotExamples = List.copyOf(builder.fewShotExamples);
        this.guardInjection = builder.guardInjection;
        this.wrapUserVariablesInXmlTag = builder.wrapUserVariablesInXmlTag;
    }

    public static Builder builder(String id) {
        return new Builder().id(id);
    }

    /**
     * 从模板文本中提取所有符合 {@code {varName}} 规范的变量名集合。
     */
    public static Set<String> extractPlaceholders(String templateText) {
        if (templateText == null || templateText.isBlank()) {
            return Set.of();
        }
        Set<String> vars = new LinkedHashSet<>();
        Matcher matcher = VARIABLE_PLACEHOLDER_PATTERN.matcher(templateText);
        while (matcher.find()) {
            vars.add(matcher.group(1));
        }
        return Collections.unmodifiableSet(vars);
    }

    public String id() {
        return id;
    }

    public String version() {
        return version;
    }

    public String description() {
        return description;
    }

    public String systemTemplate() {
        return systemTemplate;
    }

    public String userTemplate() {
        return userTemplate;
    }

    public Set<String> requiredVariables() {
        return requiredVariables;
    }

    public Map<String, Object> defaultVariables() {
        return defaultVariables;
    }

    public List<FewShotExample> fewShotExamples() {
        return fewShotExamples;
    }

    public boolean guardInjection() {
        return guardInjection;
    }

    public boolean wrapUserVariablesInXmlTag() {
        return wrapUserVariablesInXmlTag;
    }

    public static final class Builder {
        private String id;
        private String version = DEFAULT_VERSION;
        private String description;
        private String systemTemplate;
        private String userTemplate;
        private final Set<String> requiredVariables = new LinkedHashSet<>();
        private final Map<String, Object> defaultVariables = new LinkedHashMap<>();
        private final List<FewShotExample> fewShotExamples = new ArrayList<>();
        private boolean autoDetectRequiredVariables = true;
        private boolean guardInjection = true;
        private boolean wrapUserVariablesInXmlTag = false;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder version(String version) {
            this.version = version;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder systemTemplate(String systemTemplate) {
            this.systemTemplate = systemTemplate;
            return this;
        }

        public Builder userTemplate(String userTemplate) {
            this.userTemplate = userTemplate;
            return this;
        }

        public Builder requiredVariable(String variableName) {
            if (variableName != null && !variableName.isBlank()) {
                this.requiredVariables.add(variableName.strip());
            }
            return this;
        }

        public Builder requiredVariables(Collection<String> variableNames) {
            if (variableNames != null) {
                variableNames.forEach(this::requiredVariable);
            }
            return this;
        }

        public Builder defaultVariable(String name, Object value) {
            if (name != null && !name.isBlank() && value != null) {
                this.defaultVariables.put(name.strip(), value);
            }
            return this;
        }

        public Builder defaultVariables(Map<String, Object> defaults) {
            if (defaults != null) {
                defaults.forEach(this::defaultVariable);
            }
            return this;
        }

        public Builder fewShotExample(String user, String assistant) {
            this.fewShotExamples.add(FewShotExample.of(user, assistant));
            return this;
        }

        public Builder fewShotExample(FewShotExample example) {
            if (example != null) {
                this.fewShotExamples.add(example);
            }
            return this;
        }

        public Builder fewShotExamples(Collection<FewShotExample> examples) {
            if (examples != null) {
                this.fewShotExamples.addAll(examples);
            }
            return this;
        }

        public Builder autoDetectRequiredVariables(boolean autoDetect) {
            this.autoDetectRequiredVariables = autoDetect;
            return this;
        }

        public Builder guardInjection(boolean guardInjection) {
            this.guardInjection = guardInjection;
            return this;
        }

        public Builder wrapUserVariablesInXmlTag(boolean wrapInXml) {
            this.wrapUserVariablesInXmlTag = wrapInXml;
            return this;
        }

        public PromptTemplateSpec build() {
            return new PromptTemplateSpec(this);
        }
    }
}
