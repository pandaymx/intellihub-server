package dev.ppmb.intellihub.rag.parser;

import dev.ppmb.intellihub.core.exception.GlobalErrorCodeConstants;
import dev.ppmb.intellihub.core.exception.IntelliHubExceptionUtil;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTML / XML 文档解析器。
 *
 * <ul>
 *   <li>自动剥离 {@code <script>}、{@code <style>}、{@code <noscript>} 与注释噪音；
 *   <li>提取 {@code <title>} 作为文档标题；
 *   <li>将 {@code <h1>..<h6>} 映射为 Markdown {@code #..######} 标题，{@code <pre>} 映射为围栏代码块，{@code <li>}
 *       映射为列表项，并解码 HTML 实体。
 * </ul>
 */
public class HtmlDocumentParser implements DocumentParser {

    private static final Pattern TITLE_PATTERN =
            Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern NOISE_BLOCK_PATTERN =
            Pattern.compile(
                    "<(script|style|noscript|svg|head)[^>]*>.*?</\\1>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern COMMENT_PATTERN = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern HEADING_TAG_PATTERN =
            Pattern.compile(
                    "<h([1-6])[^>]*>(.*?)</h\\1>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern PRE_TAG_PATTERN =
            Pattern.compile("<pre[^>]*>(.*?)</pre>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern LI_TAG_PATTERN =
            Pattern.compile("<li[^>]*>(.*?)</li>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern BLOCK_CLOSE_PATTERN =
            Pattern.compile(
                    "</(p|div|section|article|tr|blockquote|header|footer|main)>|<br\\s*/?>",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern TD_TH_CLOSE_PATTERN =
            Pattern.compile("</(td|th)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANY_TAG_PATTERN = Pattern.compile("<[^>]+>");

    @Override
    public String name() {
        return "HtmlDocumentParser";
    }

    @Override
    public List<String> supportedExtensions() {
        return List.of("html", "htm", "xhtml", "xml");
    }

    @Override
    public boolean supports(String fileName, String contentType) {
        if (DocumentParser.super.supports(fileName, contentType)) {
            return true;
        }
        if (contentType == null) {
            return false;
        }
        String lower = contentType.toLowerCase();
        return lower.contains("text/html") || lower.contains("application/xhtml+xml");
    }

    @Override
    public ParsedDocument parse(String fileName, String contentType, byte[] contentBytes) {
        if (contentBytes == null || contentBytes.length == 0) {
            throw IntelliHubExceptionUtil.exception(
                    GlobalErrorCodeConstants.AI_DOCUMENT_PARSE_ERROR, "HTML 文件内容为空: {}", fileName);
        }

        String rawHtml = CharsetEncodingSupport.decodeToString(contentBytes);
        String ext = CharsetEncodingSupport.extractExtension(fileName);
        String baseTitle = CharsetEncodingSupport.extractBaseTitle(fileName);

        Matcher titleMatcher = TITLE_PATTERN.matcher(rawHtml);
        String extractedTitle =
                titleMatcher.find() ? stripTagsAndDecode(titleMatcher.group(1)).strip() : "";
        String docTitle = !extractedTitle.isEmpty() ? extractedTitle : baseTitle;

        String working = COMMENT_PATTERN.matcher(rawHtml).replaceAll("");
        working = NOISE_BLOCK_PATTERN.matcher(working).replaceAll("\n");

        // 转换 <h1>..<h6>
        Matcher hMatcher = HEADING_TAG_PATTERN.matcher(working);
        StringBuilder hBuffer = new StringBuilder();
        while (hMatcher.find()) {
            int level = Integer.parseInt(hMatcher.group(1));
            String headingText =
                    stripTagsAndDecode(hMatcher.group(2)).replaceAll("\\s+", " ").strip();
            String replacement =
                    headingText.isEmpty()
                            ? ""
                            : ("\n\n" + "#".repeat(level) + " " + headingText + "\n\n");
            hMatcher.appendReplacement(hBuffer, Matcher.quoteReplacement(replacement));
        }
        hMatcher.appendTail(hBuffer);
        working = hBuffer.toString();

        // 转换 <pre>
        Matcher preMatcher = PRE_TAG_PATTERN.matcher(working);
        StringBuilder preBuffer = new StringBuilder();
        while (preMatcher.find()) {
            String codeContent = stripTagsAndDecode(preMatcher.group(1)).strip();
            String replacement =
                    codeContent.isEmpty() ? "" : ("\n\n```\n" + codeContent + "\n```\n\n");
            preMatcher.appendReplacement(preBuffer, Matcher.quoteReplacement(replacement));
        }
        preMatcher.appendTail(preBuffer);
        working = preBuffer.toString();

        // 转换 <li>
        Matcher liMatcher = LI_TAG_PATTERN.matcher(working);
        StringBuilder liBuffer = new StringBuilder();
        while (liMatcher.find()) {
            String itemText =
                    stripTagsAndDecode(liMatcher.group(1)).replaceAll("\\s+", " ").strip();
            String replacement = itemText.isEmpty() ? "" : ("\n- " + itemText + "\n");
            liMatcher.appendReplacement(liBuffer, Matcher.quoteReplacement(replacement));
        }
        liMatcher.appendTail(liBuffer);
        working = liBuffer.toString();

        working = TD_TH_CLOSE_PATTERN.matcher(working).replaceAll(" | ");
        working = BLOCK_CLOSE_PATTERN.matcher(working).replaceAll("\n\n");
        String cleaned = stripTagsAndDecode(working);
        cleaned =
                cleaned.replace("\r\n", "\n")
                        .replaceAll("[ \\t]+\\n", "\n")
                        .replaceAll("\\n{3,}", "\n\n")
                        .strip();

        if (!cleaned.startsWith("# ")) {
            cleaned = "# " + docTitle + "\n\n" + cleaned;
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("file_size_bytes", contentBytes.length);

        String format = "xml".equals(ext) ? "XML" : "HTML";
        return new ParsedDocument(fileName, format, docTitle, cleaned, cleaned.length(), metadata);
    }

    private static String stripTagsAndDecode(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        String withoutTags = ANY_TAG_PATTERN.matcher(input).replaceAll("");
        return decodeHtmlEntities(withoutTags);
    }

    private static String decodeHtmlEntities(String text) {
        return text.replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'");
    }
}
