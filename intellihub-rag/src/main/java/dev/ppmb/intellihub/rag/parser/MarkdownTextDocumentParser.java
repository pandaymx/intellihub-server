package dev.ppmb.intellihub.rag.parser;

import dev.ppmb.intellihub.core.exception.GlobalErrorCodeConstants;
import dev.ppmb.intellihub.core.exception.IntelliHubExceptionUtil;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 与纯文本文档解析器（支持 {@code .md}、{@code .markdown}、{@code .txt}、{@code .log}、{@code .rst}、{@code
 * .adoc}）。
 *
 * <p>内置 UTF-8 / UTF-16 / GB18030 自动字符集探测，自动提取首行 {@code # H1} 标题作为文档标题。
 */
public class MarkdownTextDocumentParser implements DocumentParser {

    private static final Pattern H1_PATTERN = Pattern.compile("^#\\s+(.+)$", Pattern.MULTILINE);

    @Override
    public String name() {
        return "MarkdownTextDocumentParser";
    }

    @Override
    public List<String> supportedExtensions() {
        return List.of("md", "markdown", "txt", "log", "rst", "adoc");
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
        return lower.startsWith("text/plain") || lower.startsWith("text/markdown");
    }

    @Override
    public ParsedDocument parse(String fileName, String contentType, byte[] contentBytes) {
        if (contentBytes == null || contentBytes.length == 0) {
            throw IntelliHubExceptionUtil.exception(
                    GlobalErrorCodeConstants.AI_DOCUMENT_PARSE_ERROR, "文本文件内容为空: {}", fileName);
        }

        String text =
                CharsetEncodingSupport.decodeToString(contentBytes)
                        .replace("\r\n", "\n")
                        .replace('\r', '\n')
                        .strip();
        if (text.isEmpty()) {
            throw IntelliHubExceptionUtil.exception(
                    GlobalErrorCodeConstants.AI_DOCUMENT_PARSE_ERROR, "文本文件不含有效字符: {}", fileName);
        }

        String ext = CharsetEncodingSupport.extractExtension(fileName);
        String baseTitle = CharsetEncodingSupport.extractBaseTitle(fileName);

        Matcher h1Matcher = H1_PATTERN.matcher(text);
        String docTitle = h1Matcher.find() ? h1Matcher.group(1).strip() : baseTitle;

        boolean isMarkdown = "md".equals(ext) || "markdown".equals(ext);
        String finalMd = text;
        if (!isMarkdown && !text.startsWith("#")) {
            finalMd = "# " + docTitle + "\n\n" + text;
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("file_size_bytes", contentBytes.length);

        String format = isMarkdown ? "MARKDOWN" : "TEXT";
        return new ParsedDocument(fileName, format, docTitle, finalMd, finalMd.length(), metadata);
    }
}
