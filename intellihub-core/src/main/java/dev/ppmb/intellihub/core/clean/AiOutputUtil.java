package dev.ppmb.intellihub.core.clean;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 模型原始输出清洗与容错修复工具类。
 *
 * <ul>
 *   <li>剥离或提取 {@code <think>...</think>} / {@code <thinking>...</thinking>} 思维链内容；
 *   <li>从包含 Markdown 代码块、解释性前后缀、注释或尾随逗号的文本中提取纯净 JSON；
 *   <li>状态机自动修复因 {@code max_tokens} 限制导致的截断 JSON（补齐未闭合引号、悬空键值与括号栈）。
 * </ul>
 */
public final class AiOutputUtil {

    private static final Pattern CLOSED_THINK_PATTERN =
            Pattern.compile("(?is)<think(?:ing)?>\\s*(.*?)\\s*</think(?:ing)?>");

    private static final Pattern UNCLOSED_THINK_PATTERN =
            Pattern.compile("(?is)<think(?:ing)?>\\s*(.*)$");

    private static final Pattern CODE_FENCE_PATTERN =
            Pattern.compile("(?is)```(?:json)?\\s*(.*?)\\s*(?:```|$)");

    private AiOutputUtil() {}

    /**
     * 剥离文本中的 {@code <think>...</think>} 或 {@code <thinking>...</thinking>} 思维链标签块（含未闭合截断块）。
     */
    public static String stripThink(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return "";
        }
        String result = CLOSED_THINK_PATTERN.matcher(rawText).replaceAll("");
        result = UNCLOSED_THINK_PATTERN.matcher(result).replaceAll("");
        return result.strip();
    }

    /**
     * 提取文本中的思维链内容（多段思维链以换行拼接，无思维链时返回空字符串）。
     */
    public static String extractThink(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        Matcher closedMatcher = CLOSED_THINK_PATTERN.matcher(rawText);
        while (closedMatcher.find()) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(closedMatcher.group(1).strip());
        }
        String remaining = CLOSED_THINK_PATTERN.matcher(rawText).replaceAll("");
        Matcher unclosedMatcher = UNCLOSED_THINK_PATTERN.matcher(remaining);
        if (unclosedMatcher.find()) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(unclosedMatcher.group(1).strip());
        }
        return sb.toString();
    }

    /**
     * 从大模型输出中提取容错 JSON 字符串（自动剥离思维链、Markdown 围栏、前后缀废话与尾随逗号）。
     */
    public static String extractJson(String rawText) {
        String candidate = locateJsonCandidate(rawText);
        if (candidate.isEmpty()) {
            return "";
        }
        int start = findFirstJsonStart(candidate);
        if (start < 0) {
            return "";
        }
        int end = findMatchingJsonEnd(candidate, start);
        String sliced =
                end >= start ? candidate.substring(start, end + 1) : candidate.substring(start);
        return removeTrailingCommas(sliced).strip();
    }

    /**
     * 修复因 {@code max_tokens} 等原因截断的残缺 JSON，同时也兼容完整 JSON 的容错提取。
     */
    public static String repairTruncatedJson(String rawText) {
        String candidate = locateJsonCandidate(rawText);
        if (candidate.isEmpty()) {
            return "";
        }
        int start = findFirstJsonStart(candidate);
        if (start < 0) {
            return "";
        }
        String body = candidate.substring(start).strip();
        return repairJsonStateMachine(body);
    }

    /**
     * 一站式提取并修复 JSON（先剥离思维链与代码块，再执行截断补全与尾随逗号清理）。
     */
    public static String extractAndRepairJson(String rawText) {
        return repairTruncatedJson(rawText);
    }

    private static String locateJsonCandidate(String rawText) {
        String cleaned = stripThink(rawText);
        if (cleaned.isEmpty()) {
            return "";
        }
        if (cleaned.contains("```")) {
            Matcher fenceMatcher = CODE_FENCE_PATTERN.matcher(cleaned);
            if (fenceMatcher.find()) {
                String fenced = fenceMatcher.group(1).strip();
                if (findFirstJsonStart(fenced) >= 0) {
                    return fenced;
                }
            }
        }
        return cleaned;
    }

    private static int findFirstJsonStart(String text) {
        int objIdx = text.indexOf('{');
        int arrIdx = text.indexOf('[');
        if (objIdx < 0) {
            return arrIdx;
        }
        if (arrIdx < 0) {
            return objIdx;
        }
        return Math.min(objIdx, arrIdx);
    }

    private static int findMatchingJsonEnd(String text, int start) {
        Deque<Character> stack = new ArrayDeque<>();
        boolean inString = false;
        boolean escaped = false;

        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                stack.push('}');
            } else if (c == '[') {
                stack.push(']');
            } else if (c == '}' || c == ']') {
                if (!stack.isEmpty() && stack.peek() == c) {
                    stack.pop();
                    if (stack.isEmpty()) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    private static String repairJsonStateMachine(String json) {
        StringBuilder sb = new StringBuilder(json.length() + 16);
        Deque<Character> stack = new ArrayDeque<>();
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            sb.append(c);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                stack.push('}');
            } else if (c == '[') {
                stack.push(']');
            } else if (c == '}' || c == ']') {
                if (!stack.isEmpty() && stack.peek() == c) {
                    stack.pop();
                    if (stack.isEmpty()) {
                        break;
                    }
                }
            }
        }

        // 1. 若在字符串内截断：移除孤立转义符并闭合双引号
        if (inString) {
            if (escaped && !sb.isEmpty() && sb.charAt(sb.length() - 1) == '\\') {
                sb.setLength(sb.length() - 1);
            }
            sb.append('"');
        }

        // 2. 闭合栈尚未清空时，修复尾部悬空 token（尾随逗号、冒号无值、半截字面量、悬空对象 key）
        if (!stack.isEmpty()) {
            fixDanglingTail(sb, stack.peek());
            while (!stack.isEmpty()) {
                sb.append(stack.pop());
            }
        }

        return removeTrailingCommas(sb.toString());
    }

    private static void fixDanglingTail(StringBuilder sb, char topExpectedClose) {
        trimTrailingWhitespace(sb);
        if (sb.isEmpty()) {
            return;
        }

        // 补全被截断的布尔/null 字面量或末尾孤立小数点/负号
        completePartialLiteral(sb);
        trimTrailingWhitespace(sb);

        char last = sb.charAt(sb.length() - 1);
        if (last == ',') {
            sb.setLength(sb.length() - 1);
            return;
        }
        if (last == ':') {
            sb.append("null");
            return;
        }

        // 若当前在 JSON 对象中且末尾是一个没有冒号的字符串 key（如 {"a": 1, "b"），自动补 :null
        if (topExpectedClose == '}' && last == '"' && isDanglingObjectKey(sb)) {
            sb.append(":null");
        }
    }

    private static void completePartialLiteral(StringBuilder sb) {
        int i = sb.length() - 1;
        while (i >= 0 && Character.isLetter(sb.charAt(i))) {
            i--;
        }
        if (i < sb.length() - 1) {
            String word = sb.substring(i + 1);
            if ("true".startsWith(word) && !"true".equals(word)) {
                sb.replace(i + 1, sb.length(), "true");
            } else if ("false".startsWith(word) && !"false".equals(word)) {
                sb.replace(i + 1, sb.length(), "false");
            } else if ("null".startsWith(word) && !"null".equals(word)) {
                sb.replace(i + 1, sb.length(), "null");
            }
            return;
        }
        char last = sb.charAt(sb.length() - 1);
        if (last == '.' || last == '-') {
            sb.append('0');
        }
    }

    private static boolean isDanglingObjectKey(StringBuilder sb) {
        // 从末尾闭合引号向前找配对的起始引号
        int endQuote = sb.length() - 1;
        int startQuote = -1;
        for (int i = endQuote - 1; i >= 0; i--) {
            if (sb.charAt(i) == '"' && !isEscaped(sb, i)) {
                startQuote = i;
                break;
            }
        }
        if (startQuote < 0) {
            return false;
        }
        // 查看起始引号前的首个非空白字符：若是 '{' 或 ','，说明这是对象的 key 而非 value
        for (int i = startQuote - 1; i >= 0; i--) {
            char c = sb.charAt(i);
            if (!Character.isWhitespace(c)) {
                return c == '{' || c == ',';
            }
        }
        return false;
    }

    private static boolean isEscaped(CharSequence seq, int index) {
        int backslashes = 0;
        for (int i = index - 1; i >= 0 && seq.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        return (backslashes & 1) == 1;
    }

    private static void trimTrailingWhitespace(StringBuilder sb) {
        while (!sb.isEmpty() && Character.isWhitespace(sb.charAt(sb.length() - 1))) {
            sb.setLength(sb.length() - 1);
        }
    }

    private static String removeTrailingCommas(String json) {
        StringBuilder out = new StringBuilder(json.length());
        boolean inString = false;
        boolean escaped = false;
        int len = json.length();

        for (int i = 0; i < len; i++) {
            char c = json.charAt(i);
            if (inString) {
                out.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                out.append(c);
                continue;
            }
            if (c == ',') {
                int j = i + 1;
                while (j < len && Character.isWhitespace(json.charAt(j))) {
                    j++;
                }
                if (j < len && (json.charAt(j) == '}' || json.charAt(j) == ']')) {
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }
}
