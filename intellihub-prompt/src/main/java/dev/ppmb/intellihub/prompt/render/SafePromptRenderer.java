package dev.ppmb.intellihub.prompt.render;

import dev.ppmb.intellihub.core.exception.IntelliHubExceptionUtil;
import dev.ppmb.intellihub.prompt.template.FewShotExample;
import dev.ppmb.intellihub.prompt.template.PromptTemplateSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 安全 Prompt 模板渲染器（支持必填变量强校验、防注入护栏、XML 标签定界包裹与 JSON 原生大括号免转义兼容）。
 */
public class SafePromptRenderer {

    private final PromptInjectionGuard injectionGuard;

    public SafePromptRenderer() {
        this(new PromptInjectionGuard());
    }

    public SafePromptRenderer(PromptInjectionGuard injectionGuard) {
        this.injectionGuard =
                Objects.requireNonNull(injectionGuard, "injectionGuard must not be null");
    }

    public PromptInjectionGuard getInjectionGuard() {
        return injectionGuard;
    }

    /**
     * 基于 {@link PromptTemplateSpec} 与变量字典渲染生成完整的 Spring AI {@link Prompt}（含 SystemMessage、Few-Shot
     * 消息对与 UserMessage）。
     */
    public Prompt render(PromptTemplateSpec spec, Map<String, Object> variables) {
        return render(spec, variables, null);
    }

    /**
     * 基于 {@link PromptTemplateSpec}、变量字典及可选运行时 {@link ChatOptions} 渲染生成完整的 Spring AI {@link
     * Prompt}。
     */
    public Prompt render(
            PromptTemplateSpec spec, Map<String, Object> variables, ChatOptions options) {
        Objects.requireNonNull(spec, "PromptTemplateSpec must not be null");
        Map<String, Object> effectiveVars = mergeAndValidateVariables(spec, variables);

        List<Message> messages = new ArrayList<>();
        if (spec.systemTemplate() != null && !spec.systemTemplate().isBlank()) {
            String renderedSystem =
                    replacePlaceholders(spec.systemTemplate(), effectiveVars, false);
            messages.add(new SystemMessage(renderedSystem));
        }

        for (FewShotExample example : spec.fewShotExamples()) {
            messages.add(new UserMessage(example.user()));
            messages.add(new AssistantMessage(example.assistant()));
        }

        String renderedUser =
                replacePlaceholders(
                        spec.userTemplate(), effectiveVars, spec.wrapUserVariablesInXmlTag());
        messages.add(new UserMessage(renderedUser));

        return options != null ? new Prompt(messages, options) : new Prompt(messages);
    }

    /**
     * 直接渲染单段模板字符串（自动忽略非标识符形式的 JSON 大括号 {@code {"a": 1}}）。
     */
    public String renderText(String template, Map<String, Object> variables) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        Map<String, Object> safeVars = variables != null ? variables : Map.of();
        return replacePlaceholders(template, safeVars, false);
    }

    private Map<String, Object> mergeAndValidateVariables(
            PromptTemplateSpec spec, Map<String, Object> callerVariables) {
        Map<String, Object> merged = new LinkedHashMap<>(spec.defaultVariables());
        if (callerVariables != null) {
            callerVariables.forEach((k, v) -> {
                if (k != null && v != null) {
                    if (spec.guardInjection()) {
                        injectionGuard.validate(k, v.toString());
                    }
                    merged.put(k, v);
                }
            });
        }

        List<String> missing = new ArrayList<>();
        for (String reqKey : spec.requiredVariables()) {
            Object val = merged.get(reqKey);
            if (val == null || val.toString().isBlank()) {
                missing.add(reqKey);
            }
        }
        if (!missing.isEmpty()) {
            throw IntelliHubExceptionUtil.invalidParamException(
                    "渲染 Prompt 模板 [{}@{}] 失败：缺失必填变量 {}", spec.id(), spec.version(), missing);
        }
        return merged;
    }

    private static String replacePlaceholders(
            String template, Map<String, Object> variables, boolean wrapInXmlTag) {
        Matcher matcher = PromptTemplateSpec.VARIABLE_PLACEHOLDER_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder(template.length() + 64);
        while (matcher.find()) {
            String varName = matcher.group(1);
            Object val = variables.get(varName);
            if (val == null) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
                continue;
            }
            String replacement =
                    wrapInXmlTag
                            ? PromptInjectionGuard.wrapInXmlDelimiter(varName, val.toString())
                            : val.toString();
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
