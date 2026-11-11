package dev.ppmb.intellihub.rag.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.ppmb.intellihub.core.exception.GlobalErrorCodeConstants;
import dev.ppmb.intellihub.core.exception.IntelliHubExceptionUtil;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结构化配置与数据文档解析器（支持 {@code .json}、{@code .jsonl}、{@code .ndjson}、{@code .yaml}、{@code .yml}、{@code
 * .properties}、{@code .toml}）。
 *
 * <p>将 JSON 对象顶层键、JSON 数组元素或 YAML/Properties 配置段自动拆解为带 {@code ## Section} 标题与代码块的结构化 Markdown，避免切分器在
 * JSON 大括号中间硬截断。
 */
public class StructuredDataDocumentParser implements DocumentParser {

    private static final ObjectMapper PRETTY_MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Override
    public String name() {
        return "StructuredDataDocumentParser";
    }

    @Override
    public List<String> supportedExtensions() {
        return List.of("json", "jsonl", "ndjson", "yaml", "yml", "properties", "toml");
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
        return lower.contains("application/json") || lower.contains("application/yaml");
    }

    @Override
    public ParsedDocument parse(String fileName, String contentType, byte[] contentBytes) {
        if (contentBytes == null || contentBytes.length == 0) {
            throw IntelliHubExceptionUtil.exception(
                    GlobalErrorCodeConstants.AI_DOCUMENT_PARSE_ERROR, "结构化数据文件内容为空: {}", fileName);
        }

        String rawText = CharsetEncodingSupport.decodeToString(contentBytes).strip();
        String ext = CharsetEncodingSupport.extractExtension(fileName);
        String docTitle = CharsetEncodingSupport.extractBaseTitle(fileName);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("file_size_bytes", contentBytes.length);

        if ("json".equals(ext)
                || (ext.isEmpty()
                        && contentType != null
                        && contentType.toLowerCase().contains("json"))) {
            String md = formatJsonToMarkdown(docTitle, rawText, metadata);
            return new ParsedDocument(fileName, "JSON", docTitle, md, md.length(), metadata);
        }

        if ("jsonl".equals(ext) || "ndjson".equals(ext)) {
            String md = formatJsonLinesToMarkdown(docTitle, rawText, metadata);
            return new ParsedDocument(fileName, "JSONL", docTitle, md, md.length(), metadata);
        }

        String lang =
                switch (ext) {
                    case "yaml", "yml" -> "yaml";
                    case "toml" -> "toml";
                    default -> "properties";
                };
        String format = lang.toUpperCase();
        String md = formatConfigToMarkdown(docTitle, rawText, lang);
        return new ParsedDocument(fileName, format, docTitle, md, md.length(), metadata);
    }

    private static String formatJsonToMarkdown(
            String docTitle, String rawJson, Map<String, Object> metadata) {
        StringBuilder md = new StringBuilder();
        md.append("# ").append(docTitle).append("\n\n");
        try {
            JsonNode root = PRETTY_MAPPER.readTree(rawJson);
            if (root.isObject()) {
                metadata.put("top_level_keys", root.size());
                Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    md.append("## ").append(entry.getKey()).append("\n\n");
                    JsonNode val = entry.getValue();
                    if (val.isValueNode()) {
                        md.append("- **")
                                .append(entry.getKey())
                                .append("**: ")
                                .append(val.asText())
                                .append("\n\n");
                    } else {
                        md.append("```json\n")
                                .append(PRETTY_MAPPER.writeValueAsString(val))
                                .append("\n```\n\n");
                    }
                }
            } else if (root.isArray()) {
                metadata.put("record_count", root.size());
                for (int i = 0; i < root.size(); i++) {
                    md.append("## Record #").append(i + 1).append("\n\n");
                    md.append("```json\n")
                            .append(PRETTY_MAPPER.writeValueAsString(root.get(i)))
                            .append("\n```\n\n");
                }
            } else {
                md.append("```json\n").append(rawJson).append("\n```\n");
            }
        } catch (Exception e) {
            md.append("```json\n").append(rawJson).append("\n```\n");
        }
        return md.toString().strip();
    }

    private static String formatJsonLinesToMarkdown(
            String docTitle, String rawJsonl, Map<String, Object> metadata) {
        StringBuilder md = new StringBuilder();
        md.append("# ").append(docTitle).append("\n\n");
        String[] lines = rawJsonl.replace("\r\n", "\n").split("\n");
        int count = 0;
        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            count++;
            md.append("## Record #").append(count).append("\n\n");
            try {
                JsonNode node = PRETTY_MAPPER.readTree(trimmed);
                md.append("```json\n")
                        .append(PRETTY_MAPPER.writeValueAsString(node))
                        .append("\n```\n\n");
            } catch (Exception e) {
                md.append("```json\n").append(trimmed).append("\n```\n\n");
            }
        }
        metadata.put("record_count", count);
        return md.toString().strip();
    }

    private static String formatConfigToMarkdown(String docTitle, String rawConfig, String lang) {
        StringBuilder md = new StringBuilder();
        md.append("# ").append(docTitle).append("\n\n");
        md.append("```").append(lang).append("\n").append(rawConfig).append("\n```\n");
        return md.toString().strip();
    }
}
