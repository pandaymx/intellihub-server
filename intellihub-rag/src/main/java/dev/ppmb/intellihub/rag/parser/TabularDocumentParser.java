package dev.ppmb.intellihub.rag.parser;

import dev.ppmb.intellihub.core.exception.GlobalErrorCodeConstants;
import dev.ppmb.intellihub.core.exception.IntelliHubExceptionUtil;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 表格数据文档解析器（支持 {@code .csv} 与 {@code .tsv}）。
 *
 * <ul>
 *   <li>支持 UTF-8 / GB18030 自动编码检测与 RFC-4180 标准双引号转义解析；
 *   <li>自动识别首行表头，并将大表按每 25 行切分为带独立 {@code ### Rows X-Y} 章节的 Markdown 表格，且在每个分片中自动重复表头行，确保下游 RAG
 *       任意切片都不丢失列名上下文。
 * </ul>
 */
public class TabularDocumentParser implements DocumentParser {

    public static final int DEFAULT_ROWS_PER_BATCH = 25;

    @Override
    public String name() {
        return "TabularDocumentParser";
    }

    @Override
    public List<String> supportedExtensions() {
        return List.of("csv", "tsv");
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
        return lower.contains("text/csv") || lower.contains("text/tab-separated-values");
    }

    @Override
    public ParsedDocument parse(String fileName, String contentType, byte[] contentBytes) {
        if (contentBytes == null || contentBytes.length == 0) {
            throw IntelliHubExceptionUtil.exception(
                    GlobalErrorCodeConstants.AI_DOCUMENT_PARSE_ERROR, "表格文件内容为空: {}", fileName);
        }

        String rawText = CharsetEncodingSupport.decodeToString(contentBytes);
        String ext = CharsetEncodingSupport.extractExtension(fileName);
        boolean isTsv =
                "tsv".equals(ext)
                        || (contentType != null
                                && contentType.toLowerCase().contains("tab-separated"));
        char delimiter = isTsv ? '\t' : detectCsvDelimiter(rawText);

        List<List<String>> rows = parseDelimitedRows(rawText, delimiter);
        if (rows.isEmpty()) {
            throw IntelliHubExceptionUtil.exception(
                    GlobalErrorCodeConstants.AI_DOCUMENT_PARSE_ERROR, "表格文件未包含有效数据行: {}", fileName);
        }

        String title = CharsetEncodingSupport.extractBaseTitle(fileName);
        int colCount = rows.stream().mapToInt(List::size).max().orElse(0);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("file_size_bytes", contentBytes.length);
        metadata.put("row_count", rows.size());
        metadata.put("column_count", colCount);
        if (!rows.get(0).isEmpty()) {
            metadata.put("columns", String.join(", ", rows.get(0)));
        }

        StringBuilder md = new StringBuilder();
        md.append("# ").append(title).append("\n\n");
        md.append(renderBatchedMarkdownTables(title, rows, DEFAULT_ROWS_PER_BATCH));

        String finalMd = md.toString().strip();
        return new ParsedDocument(
                fileName, isTsv ? "TSV" : "CSV", title, finalMd, finalMd.length(), metadata);
    }

    private static char detectCsvDelimiter(String text) {
        int firstNewline = text.indexOf('\n');
        String firstLine = firstNewline >= 0 ? text.substring(0, firstNewline) : text;
        long commas = firstLine.chars().filter(c -> c == ',').count();
        long semicolons = firstLine.chars().filter(c -> c == ';').count();
        long tabs = firstLine.chars().filter(c -> c == '\t').count();
        if (tabs > commas && tabs > semicolons) {
            return '\t';
        }
        if (semicolons > commas) {
            return ';';
        }
        return ',';
    }

    /**
     * RFC-4180 兼容的分隔符状态机解析器（支持引号内换行与双引号转义）。
     */
    public static List<List<String>> parseDelimitedRows(String text, char delimiter) {
        List<List<String>> rows = new ArrayList<>();
        List<String> currentRow = new ArrayList<>();
        StringBuilder currentCell = new StringBuilder();
        boolean inQuotes = false;

        int len = text.length();
        for (int i = 0; i < len; i++) {
            char ch = text.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < len && text.charAt(i + 1) == '"') {
                        currentCell.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    currentCell.append(ch);
                }
            } else {
                if (ch == '"') {
                    inQuotes = true;
                } else if (ch == delimiter) {
                    currentRow.add(currentCell.toString().strip());
                    currentCell.setLength(0);
                } else if (ch == '\r' || ch == '\n') {
                    if (ch == '\r' && i + 1 < len && text.charAt(i + 1) == '\n') {
                        i++;
                    }
                    currentRow.add(currentCell.toString().strip());
                    currentCell.setLength(0);
                    if (currentRow.stream().anyMatch(c -> !c.isEmpty())) {
                        rows.add(currentRow);
                    }
                    currentRow = new ArrayList<>();
                } else {
                    currentCell.append(ch);
                }
            }
        }

        if (!currentCell.isEmpty() || !currentRow.isEmpty()) {
            currentRow.add(currentCell.toString().strip());
            if (currentRow.stream().anyMatch(c -> !c.isEmpty())) {
                rows.add(currentRow);
            }
        }
        return rows;
    }

    /**
     * 将大型二维表格按批次渲染为多个带重复表头的 Markdown 子表。
     */
    public static String renderBatchedMarkdownTables(
            String sectionPrefix, List<List<String>> rows, int batchSize) {
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        if (rows.size() <= batchSize + 1) {
            return renderMarkdownTable(rows);
        }

        List<String> header = rows.get(0);
        List<List<String>> dataRows = rows.subList(1, rows.size());
        StringBuilder sb = new StringBuilder();

        for (int start = 0; start < dataRows.size(); start += batchSize) {
            int end = Math.min(dataRows.size(), start + batchSize);
            sb.append("### ")
                    .append(sectionPrefix)
                    .append(" (Rows ")
                    .append(start + 1)
                    .append("-")
                    .append(end)
                    .append(")\n\n");
            List<List<String>> batchTable = new ArrayList<>(end - start + 1);
            batchTable.add(header);
            batchTable.addAll(dataRows.subList(start, end));
            sb.append(renderMarkdownTable(batchTable)).append("\n");
        }
        return sb.toString();
    }

    /**
     * 将二维数组渲染为标准 GFM Markdown 表格。
     */
    public static String renderMarkdownTable(List<List<String>> rows) {
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        int colCount = rows.stream().mapToInt(List::size).max().orElse(0);
        if (colCount == 0) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        List<String> header = rows.get(0);
        sb.append("|");
        for (int c = 0; c < colCount; c++) {
            String cell = c < header.size() ? sanitizeTableCell(header.get(c)) : ("Col_" + (c + 1));
            if (cell.isEmpty()) {
                cell = "Col_" + (c + 1);
            }
            sb.append(" ").append(cell).append(" |");
        }
        sb.append("\n|");
        for (int c = 0; c < colCount; c++) {
            sb.append(" --- |");
        }
        sb.append("\n");

        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            sb.append("|");
            for (int c = 0; c < colCount; c++) {
                String cell = c < row.size() ? sanitizeTableCell(row.get(c)) : "";
                sb.append(" ").append(cell).append(" |");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static String sanitizeTableCell(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\r\n", " ")
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace("|", "\\|")
                .strip();
    }
}
