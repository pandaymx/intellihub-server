package dev.ppmb.intellihub.prompt.render;

import dev.ppmb.intellihub.core.exception.GlobalErrorCodeConstants;
import dev.ppmb.intellihub.core.exception.IntelliHubExceptionUtil;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * Prompt 越狱与指令注入安全护栏（Prompt Injection Guard）。
 *
 * <ul>
 *   <li>内置针对中英文常见系统指令覆盖、角色劫持与系统提示词窃取的正则检测规则；
 *   <li>支持通过 {@link #addBlockedPattern(Pattern)} 动态追加业务自定义拦截规则；
 *   <li>命中风险特征时抛出统一的 {@link GlobalErrorCodeConstants#AI_GUARD_REJECTED} ({@code 1_001_000_006}) 异常。
 * </ul>
 */
public class PromptInjectionGuard {

    private static final List<Pattern> DEFAULT_PATTERNS =
            List.of(
                    Pattern.compile(
                            "(?i)ignore\\s+(all\\s+)?(previous|prior|above|system)\\s+(instructions|prompts|rules)"),
                    Pattern.compile(
                            "(?i)disregard\\s+(all\\s+)?(previous|prior|above|system)\\s+(instructions|prompts|rules)"),
                    Pattern.compile(
                            "(?i)(reveal|print|output|repeat)\\s+(your|the)\\s+(system\\s+prompt|hidden\\s+instructions)"),
                    Pattern.compile("(?i)you\\s+are\\s+now\\s+in\\s+developer\\s+mode"),
                    Pattern.compile("忽略(之前|以上|上述|前面|所有)(的)?(所有)?(指令|设定|规则|提示词|系统提示)"),
                    Pattern.compile("(输出|打印|泄露|告诉我)(你的|完整的)?(系统提示词|System\\s*Prompt|底层指令)"));

    private final List<Pattern> blockedPatterns = new CopyOnWriteArrayList<>(DEFAULT_PATTERNS);

    public PromptInjectionGuard() {}

    public PromptInjectionGuard(Collection<Pattern> extraPatterns) {
        if (extraPatterns != null) {
            this.blockedPatterns.addAll(extraPatterns);
        }
    }

    public PromptInjectionGuard addBlockedPattern(Pattern pattern) {
        Objects.requireNonNull(pattern, "pattern must not be null");
        this.blockedPatterns.add(pattern);
        return this;
    }

    public List<Pattern> getBlockedPatterns() {
        return Collections.unmodifiableList(new ArrayList<>(blockedPatterns));
    }

    /**
     * 校验用户输入变量值是否包含越狱或指令注入特征；若命中则抛出 {@code AI_GUARD_REJECTED} 异常。
     */
    public void validate(String variableName, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        for (Pattern pattern : blockedPatterns) {
            if (pattern.matcher(value).find()) {
                throw IntelliHubExceptionUtil.exception(
                        GlobalErrorCodeConstants.AI_GUARD_REJECTED,
                        "输入变量 [" + variableName + "] 疑似包含指令注入或越狱攻击特征");
            }
        }
    }

    /**
     * 将外部不可信输入安全包裹在 {@code <user_input name="...">...</user_input>} 定界标签内，并转义内部闭合标签以防止标签逃逸。
     */
    public static String wrapInXmlDelimiter(String variableName, String rawValue) {
        String safeVal =
                rawValue == null
                        ? ""
                        : rawValue.replace("</user_input>", "&lt;/user_input&gt;")
                                .replace("<user_input", "&lt;user_input");
        return "<user_input name=\"" + variableName + "\">\n" + safeVal + "\n</user_input>";
    }
}
