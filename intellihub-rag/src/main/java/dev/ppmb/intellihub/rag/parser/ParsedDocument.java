package dev.ppmb.intellihub.rag.parser;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.ai.document.Document;

/**
 * 多格式文档解析后的标准化结果对象。
 *
 * @param fileName 原始文件名（如 {@code architecture-spec.pdf}）
 * @param fileFormat 标准化文件格式标识（如 {@code PDF}、{@code DOCX}、{@code XLSX}、{@code CSV}、{@code MARKDOWN}）
 * @param title 自动识别或提取的文档标题
 * @param markdownContent 转换后的结构化 Markdown 文本（保留标题层级、表格与代码块，供切分器直接消费）
 * @param characterCount 解析出的有效字符总数
 * @param metadata 从文档提取出的结构化元数据（如 {@code page_count}、{@code sheet_count}、{@code row_count} 等）
 */
public record ParsedDocument(
        String fileName,
        String fileFormat,
        String title,
        String markdownContent,
        int characterCount,
        Map<String, Object> metadata) {

    public ParsedDocument {
        fileName = fileName != null ? fileName : "unnamed";
        fileFormat = fileFormat != null ? fileFormat : "TEXT";
        title =
                (title != null && !title.isBlank())
                        ? title.strip()
                        : CharsetEncodingSupport.extractBaseTitle(fileName);
        markdownContent = markdownContent != null ? markdownContent.strip() : "";
        characterCount = markdownContent.length();
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    /**
     * 转换为 Spring AI {@link Document} 对象，自动注入文件名、格式与标题元数据。
     */
    public Document toSpringAiDocument(Map<String, Object> extraMetadata) {
        Map<String, Object> merged = new LinkedHashMap<>(this.metadata);
        merged.put("title", this.title);
        merged.put("file_name", this.fileName);
        merged.put("file_format", this.fileFormat);
        merged.put("character_count", this.characterCount);
        if (extraMetadata != null && !extraMetadata.isEmpty()) {
            merged.putAll(extraMetadata);
        }
        String docId = sanitizeDocId(this.fileName);
        return Document.builder().id(docId).text(this.markdownContent).metadata(merged).build();
    }

    private static String sanitizeDocId(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return "doc-" + System.currentTimeMillis();
        }
        String normalized = rawName.replace('\\', '/').strip();
        int slashIdx = normalized.lastIndexOf('/');
        String simple = slashIdx >= 0 ? normalized.substring(slashIdx + 1) : normalized;
        String cleaned = simple.replaceAll("[\\s#?&=]+", "_");
        return cleaned.isBlank() ? "doc-" + System.currentTimeMillis() : cleaned;
    }
}
