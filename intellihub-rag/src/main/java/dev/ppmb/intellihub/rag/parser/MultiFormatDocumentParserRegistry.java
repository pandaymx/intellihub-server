package dev.ppmb.intellihub.rag.parser;

import dev.ppmb.intellihub.core.exception.GlobalErrorCodeConstants;
import dev.ppmb.intellihub.core.exception.IntelliHubExceptionUtil;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 多格式文档解析器注册表与统一调度中心。
 *
 * <p>默认内置 7 大类企業级文档解析器：
 *
 * <ul>
 *   <li>{@link PdfDocumentParser}：{@code .pdf}（按页提取并生成 {@code ## Page N} 章节与元数据）；
 *   <li>{@link OoxmlDocumentParser}：{@code .docx}、{@code .xlsx}、{@code .pptx}（Office Open XML
 *       全系列，保留标题样式、表格与幻灯片结构）；
 *   <li>{@link TabularDocumentParser}：{@code .csv}、{@code .tsv}（自动编码检测 + 重复表头的 Markdown 分片表格）；
 *   <li>{@link HtmlDocumentParser}：{@code .html}、{@code .htm}、{@code .xhtml}、{@code .xml}（去噪并转换语义标签）；
 *   <li>{@link StructuredDataDocumentParser}：{@code .json}、{@code .jsonl}、{@code .yaml}、{@code
 *       .yml}、{@code .properties}、{@code .toml}；
 *   <li>{@link SourceCodeDocumentParser}：{@code .java}、{@code .kt}、{@code .py}、{@code .ts}、{@code
 *       .js}、{@code .go}、{@code .rs}、{@code .sql}、{@code .sh} 等源码；
 *   <li>{@link MarkdownTextDocumentParser}：{@code .md}、{@code .markdown}、{@code .txt}、{@code
 *       .log}、{@code .rst}、{@code .adoc}。
 * </ul>
 */
public class MultiFormatDocumentParserRegistry {

    private final List<DocumentParser> parsers = new CopyOnWriteArrayList<>();
    private final MarkdownTextDocumentParser textFallbackParser = new MarkdownTextDocumentParser();

    public MultiFormatDocumentParserRegistry() {
        this(defaultParsers());
    }

    public MultiFormatDocumentParserRegistry(List<DocumentParser> initialParsers) {
        if (initialParsers != null) {
            this.parsers.addAll(initialParsers);
        }
    }

    public static List<DocumentParser> defaultParsers() {
        return List.of(
                new PdfDocumentParser(),
                new OoxmlDocumentParser(),
                new TabularDocumentParser(),
                new HtmlDocumentParser(),
                new StructuredDataDocumentParser(),
                new SourceCodeDocumentParser(),
                new MarkdownTextDocumentParser());
    }

    /**
     * 注册自定义文档解析器（优先于默认解析器匹配）。
     */
    public void registerParser(DocumentParser parser) {
        Objects.requireNonNull(parser, "parser 不能为空");
        this.parsers.add(0, parser);
    }

    /**
     * 返回当前支持的所有文件扩展名集合（如 {@code pdf, docx, xlsx, pptx, csv, md, txt, html, json, java...}）。
     */
    public List<String> getSupportedExtensions() {
        Set<String> exts = new LinkedHashSet<>();
        for (DocumentParser parser : parsers) {
            exts.addAll(parser.supportedExtensions());
        }
        return List.copyOf(exts);
    }

    /**
     * 返回已注册的解析器列表。
     */
    public List<DocumentParser> getParsers() {
        return Collections.unmodifiableList(new ArrayList<>(parsers));
    }

    /**
     * 根据文件名、Content-Type 与文件头魔数（Magic Bytes）自动路由并解析文档。
     */
    public ParsedDocument parse(String fileName, String contentType, byte[] contentBytes) {
        if (contentBytes == null || contentBytes.length == 0) {
            throw IntelliHubExceptionUtil.exception(
                    GlobalErrorCodeConstants.AI_DOCUMENT_PARSE_ERROR,
                    "上传的文件内容为空: {}",
                    fileName != null ? fileName : "unknown");
        }

        // 1. 优先按扩展名与 MIME 类型匹配已注册解析器
        for (DocumentParser parser : parsers) {
            if (parser.supports(fileName, contentType)) {
                return parser.parse(fileName, contentType, contentBytes);
            }
        }

        // 2. 文件头魔数（Magic Bytes）嗅探兜底：识别无扩展名或扩展名异常的 PDF / OOXML
        if (startsWithPdfMagic(contentBytes)) {
            return new PdfDocumentParser().parse(fileName, contentType, contentBytes);
        }
        if (startsWithZipMagic(contentBytes)) {
            return new OoxmlDocumentParser().parse(fileName, contentType, contentBytes);
        }

        // 3. 可读纯文本兜底（若前 512 字节不包含二进制 NUL 字节，按纯文本解析）
        if (looksLikeReadableText(contentBytes)) {
            return textFallbackParser.parse(fileName, contentType, contentBytes);
        }

        String ext = CharsetEncodingSupport.extractExtension(fileName);
        throw IntelliHubExceptionUtil.exception(
                GlobalErrorCodeConstants.AI_UNSUPPORTED_FILE_FORMAT,
                ext.isEmpty() ? fileName : ("." + ext));
    }

    /**
     * 从输入流解析文档。
     */
    public ParsedDocument parse(String fileName, String contentType, InputStream inputStream) {
        if (inputStream == null) {
            throw IntelliHubExceptionUtil.exception(
                    GlobalErrorCodeConstants.AI_DOCUMENT_PARSE_ERROR, "文件输入流不能为空");
        }
        try {
            return parse(fileName, contentType, inputStream.readAllBytes());
        } catch (IOException e) {
            throw IntelliHubExceptionUtil.exception(
                    GlobalErrorCodeConstants.AI_DOCUMENT_PARSE_ERROR,
                    "读取文件流失败 (" + fileName + "): " + e.getMessage());
        }
    }

    private static boolean startsWithPdfMagic(byte[] bytes) {
        return bytes.length >= 4
                && bytes[0] == '%'
                && bytes[1] == 'P'
                && bytes[2] == 'D'
                && bytes[3] == 'F';
    }

    private static boolean startsWithZipMagic(byte[] bytes) {
        return bytes.length >= 4
                && (bytes[0] & 0xFF) == 0x50
                && (bytes[1] & 0xFF) == 0x4B
                && (bytes[2] & 0xFF) == 0x03
                && (bytes[3] & 0xFF) == 0x04;
    }

    private static boolean looksLikeReadableText(byte[] bytes) {
        int checkLen = Math.min(bytes.length, 512);
        for (int i = 0; i < checkLen; i++) {
            if (bytes[i] == 0) {
                return false;
            }
        }
        return true;
    }
}
