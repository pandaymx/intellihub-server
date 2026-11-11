package dev.ppmb.intellihub.rag.parser;

import dev.ppmb.intellihub.core.exception.GlobalErrorCodeConstants;
import dev.ppmb.intellihub.core.exception.IntelliHubExceptionUtil;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Office Open XML (OOXML) 多格式文档解析器，原生支持 {@code .docx}（Word）、{@code .xlsx}（Excel）与 {@code
 * .pptx}（PowerPoint）。
 *
 * <ul>
 *   <li><b>安全防护</b>：显式禁用 XML 外部实体（XXE）与 DTD，杜绝恶意文档注入；
 *   <li><b>Word ({@code .docx})</b>：识别 {@code Heading1..6} / {@code 标题 1..6} 样式并转换为 {@code #..######}
 *       标题，将 {@code w:tbl} 表格转换为 Markdown 表格，保留列表项；
 *   <li><b>Excel ({@code .xlsx})</b>：解析 {@code sharedStrings.xml}、{@code workbook.xml} 与各工作表，按每 25
 *       行自动重复表头生成 Markdown 表格分片，确保 RAG 切分后每个切片都自带列头定义；
 *   <li><b>PowerPoint ({@code .pptx})</b>：按幻灯片序号提取标题与正文要点，生成 {@code ## Slide N} 结构化章节。
 * </ul>
 */
public class OoxmlDocumentParser implements DocumentParser {

    private static final int EXCEL_ROWS_PER_SECTION = 25;

    @Override
    public String name() {
        return "OoxmlDocumentParser";
    }

    @Override
    public List<String> supportedExtensions() {
        return List.of("docx", "xlsx", "pptx");
    }

    @Override
    public ParsedDocument parse(String fileName, String contentType, byte[] contentBytes) {
        if (contentBytes == null || contentBytes.length == 0) {
            throw IntelliHubExceptionUtil.exception(
                    GlobalErrorCodeConstants.AI_DOCUMENT_PARSE_ERROR,
                    "Office 文档内容为空: {}",
                    fileName);
        }
        String ext = CharsetEncodingSupport.extractExtension(fileName);
        Map<String, byte[]> zipEntries = readZipEntries(fileName, contentBytes);
        if (zipEntries.isEmpty()) {
            throw IntelliHubExceptionUtil.exception(
                    GlobalErrorCodeConstants.AI_DOCUMENT_PARSE_ERROR,
                    "无效的 Office Open XML 压缩包结构: {}",
                    fileName);
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("file_size_bytes", contentBytes.length);

        String baseTitle = CharsetEncodingSupport.extractBaseTitle(fileName);
        String coreTitle = extractCoreTitle(zipEntries.get("docProps/core.xml"), metadata);
        String docTitle =
                (coreTitle != null && !coreTitle.isBlank()) ? coreTitle.strip() : baseTitle;

        if ("docx".equals(ext) || zipEntries.containsKey("word/document.xml")) {
            String md = parseDocx(docTitle, zipEntries.get("word/document.xml"), metadata);
            return new ParsedDocument(fileName, "DOCX", docTitle, md, md.length(), metadata);
        }
        if ("xlsx".equals(ext) || zipEntries.containsKey("xl/workbook.xml")) {
            String md = parseXlsx(docTitle, zipEntries, metadata);
            return new ParsedDocument(fileName, "XLSX", docTitle, md, md.length(), metadata);
        }
        if ("pptx".equals(ext)
                || zipEntries.keySet().stream().anyMatch(k -> k.startsWith("ppt/slides/slide"))) {
            String md = parsePptx(docTitle, zipEntries, metadata);
            return new ParsedDocument(fileName, "PPTX", docTitle, md, md.length(), metadata);
        }

        throw IntelliHubExceptionUtil.exception(
                GlobalErrorCodeConstants.AI_UNSUPPORTED_FILE_FORMAT,
                "无法识别的 OOXML 文档结构: {}",
                fileName);
    }

    private static Map<String, byte[]> readZipEntries(String fileName, byte[] contentBytes) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(contentBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int read;
                while ((read = zis.read(buf)) != -1) {
                    bos.write(buf, 0, read);
                }
                entries.put(entry.getName(), bos.toByteArray());
            }
        } catch (IOException e) {
            throw IntelliHubExceptionUtil.exception(
                    GlobalErrorCodeConstants.AI_DOCUMENT_PARSE_ERROR,
                    "解压 Office 文档失败 (" + fileName + "): " + e.getMessage());
        }
        return entries;
    }

    private static XMLInputFactory createSafeXmlInputFactory() {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        return factory;
    }

    private static String extractCoreTitle(byte[] coreXml, Map<String, Object> metadata) {
        if (coreXml == null || coreXml.length == 0) {
            return null;
        }
        XMLInputFactory factory = createSafeXmlInputFactory();
        String title = null;
        try (InputStream in = new ByteArrayInputStream(coreXml)) {
            XMLStreamReader reader = factory.createXMLStreamReader(in);
            String currentTag = null;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    currentTag = reader.getLocalName();
                } else if (event == XMLStreamConstants.CHARACTERS && currentTag != null) {
                    String text = reader.getText().strip();
                    if (!text.isEmpty()) {
                        if ("title".equalsIgnoreCase(currentTag) && title == null) {
                            title = text;
                        } else if ("creator".equalsIgnoreCase(currentTag)) {
                            metadata.put("author", text);
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    currentTag = null;
                }
            }
        } catch (Exception ignored) {
            // 忽略 core.xml 元数据读取异常
        }
        return title;
    }

    /**
     * 解析 Word (.docx) {@code word/document.xml}。
     */
    private static String parseDocx(
            String docTitle, byte[] documentXml, Map<String, Object> metadata) {
        if (documentXml == null || documentXml.length == 0) {
            return "# " + docTitle;
        }
        XMLInputFactory factory = createSafeXmlInputFactory();
        StringBuilder md = new StringBuilder();
        md.append("# ").append(docTitle).append("\n\n");

        int paragraphCount = 0;
        int tableCount = 0;

        try (InputStream in = new ByteArrayInputStream(documentXml)) {
            XMLStreamReader reader = factory.createXMLStreamReader(in);

            boolean inTable = false;
            List<List<String>> currentTableRows = new ArrayList<>();
            List<String> currentRowCells = null;
            StringBuilder currentCellText = null;

            StringBuilder currentParaText = new StringBuilder();
            int headingLevel = 0;
            boolean isListParagraph = false;

            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String local = reader.getLocalName();
                    if ("tbl".equals(local)) {
                        inTable = true;
                        currentTableRows.clear();
                    } else if ("tr".equals(local) && inTable) {
                        currentRowCells = new ArrayList<>();
                    } else if ("tc".equals(local) && inTable) {
                        currentCellText = new StringBuilder();
                    } else if ("p".equals(local)) {
                        currentParaText.setLength(0);
                        headingLevel = 0;
                        isListParagraph = false;
                    } else if ("pStyle".equals(local)) {
                        String styleVal = getAttributeByLocalName(reader, "val");
                        headingLevel = resolveWordHeadingLevel(styleVal);
                    } else if ("numPr".equals(local)) {
                        isListParagraph = true;
                    } else if ("tab".equals(local)) {
                        if (inTable && currentCellText != null) {
                            currentCellText.append(" ");
                        } else {
                            currentParaText.append("\t");
                        }
                    } else if ("br".equals(local) || "cr".equals(local)) {
                        if (inTable && currentCellText != null) {
                            currentCellText.append(" ");
                        } else {
                            currentParaText.append("\n");
                        }
                    } else if ("t".equals(local)) {
                        String t = reader.getElementText();
                        if (t != null) {
                            if (inTable && currentCellText != null) {
                                currentCellText.append(t);
                            } else {
                                currentParaText.append(t);
                            }
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String local = reader.getLocalName();
                    if ("p".equals(local)) {
                        if (inTable && currentCellText != null) {
                            if (!currentCellText.isEmpty()
                                    && currentCellText.charAt(currentCellText.length() - 1)
                                            != ' ') {
                                currentCellText.append(" ");
                            }
                        } else {
                            String text = currentParaText.toString().strip();
                            if (!text.isEmpty()) {
                                paragraphCount++;
                                if (headingLevel >= 1 && headingLevel <= 6) {
                                    md.append("#".repeat(headingLevel))
                                            .append(" ")
                                            .append(text)
                                            .append("\n\n");
                                } else if (isListParagraph) {
                                    md.append("- ").append(text).append("\n");
                                } else {
                                    md.append(text).append("\n\n");
                                }
                            }
                        }
                    } else if ("tc".equals(local) && inTable && currentRowCells != null) {
                        currentRowCells.add(
                                currentCellText != null ? currentCellText.toString().strip() : "");
                        currentCellText = null;
                    } else if ("tr".equals(local) && inTable && currentRowCells != null) {
                        if (currentRowCells.stream().anyMatch(c -> !c.isBlank())) {
                            currentTableRows.add(currentRowCells);
                        }
                        currentRowCells = null;
                    } else if ("tbl".equals(local) && inTable) {
                        inTable = false;
                        if (!currentTableRows.isEmpty()) {
                            tableCount++;
                            md.append("\n")
                                    .append(
                                            TabularDocumentParser.renderMarkdownTable(
                                                    currentTableRows))
                                    .append("\n");
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw IntelliHubExceptionUtil.exception(
                    GlobalErrorCodeConstants.AI_DOCUMENT_PARSE_ERROR,
                    "解析 Word (DOCX) 内容失败: " + e.getMessage());
        }

        metadata.put("paragraph_count", paragraphCount);
        metadata.put("table_count", tableCount);
        return md.toString().strip();
    }

    private static int resolveWordHeadingLevel(String styleVal) {
        if (styleVal == null || styleVal.isBlank()) {
            return 0;
        }
        String normalized = styleVal.trim().toLowerCase();
        if ("title".equals(normalized) || "标题".equals(normalized)) {
            return 1;
        }
        if ("subtitle".equals(normalized) || "副标题".equals(normalized)) {
            return 2;
        }
        for (int i = 1; i <= 6; i++) {
            if (normalized.equals("heading" + i)
                    || normalized.equals("heading " + i)
                    || normalized.equals("标题" + i)
                    || normalized.equals("标题 " + i)
                    || normalized.equals(String.valueOf(i))) {
                return i;
            }
        }
        return 0;
    }

    /**
     * 解析 Excel (.xlsx) 工作簿与各工作表。
     */
    private static String parseXlsx(
            String docTitle, Map<String, byte[]> zipEntries, Map<String, Object> metadata) {
        List<String> sharedStrings = parseSharedStrings(zipEntries.get("xl/sharedStrings.xml"));
        List<String> sheetNames = parseWorkbookSheetNames(zipEntries.get("xl/workbook.xml"));

        Map<Integer, byte[]> sheetFiles = new TreeMap<>();
        for (Map.Entry<String, byte[]> entry : zipEntries.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("xl/worksheets/sheet") && key.endsWith(".xml")) {
                String numStr =
                        key.substring("xl/worksheets/sheet".length(), key.length() - 4).strip();
                try {
                    sheetFiles.put(Integer.parseInt(numStr), entry.getValue());
                } catch (NumberFormatException ignored) {
                    sheetFiles.put(sheetFiles.size() + 1, entry.getValue());
                }
            }
        }

        StringBuilder md = new StringBuilder();
        md.append("# ").append(docTitle).append("\n\n");

        int sheetCount = 0;
        int totalRows = 0;

        int idx = 0;
        for (Map.Entry<Integer, byte[]> sheetEntry : sheetFiles.entrySet()) {
            String sheetName =
                    idx < sheetNames.size() ? sheetNames.get(idx) : ("Sheet" + sheetEntry.getKey());
            idx++;
            List<List<String>> rows = parseWorksheetRows(sheetEntry.getValue(), sharedStrings);
            if (rows.isEmpty()) {
                continue;
            }
            sheetCount++;
            totalRows += rows.size();

            md.append("## Sheet: ").append(sheetName).append("\n\n");
            md.append(
                    TabularDocumentParser.renderBatchedMarkdownTables(
                            sheetName, rows, EXCEL_ROWS_PER_SECTION));
            md.append("\n");
        }

        metadata.put("sheet_count", sheetCount);
        metadata.put("row_count", totalRows);
        return md.toString().strip();
    }

    private static List<String> parseSharedStrings(byte[] sharedStringsXml) {
        if (sharedStringsXml == null || sharedStringsXml.length == 0) {
            return List.of();
        }
        List<String> strings = new ArrayList<>();
        XMLInputFactory factory = createSafeXmlInputFactory();
        try (InputStream in = new ByteArrayInputStream(sharedStringsXml)) {
            XMLStreamReader reader = factory.createXMLStreamReader(in);
            StringBuilder currentSi = null;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String local = reader.getLocalName();
                    if ("si".equals(local)) {
                        currentSi = new StringBuilder();
                    } else if ("t".equals(local) && currentSi != null) {
                        currentSi.append(reader.getElementText());
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if ("si".equals(reader.getLocalName()) && currentSi != null) {
                        strings.add(currentSi.toString().strip());
                        currentSi = null;
                    }
                }
            }
        } catch (Exception ignored) {
            // 忽略 sharedStrings 解析异常
        }
        return strings;
    }

    private static List<String> parseWorkbookSheetNames(byte[] workbookXml) {
        if (workbookXml == null || workbookXml.length == 0) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        XMLInputFactory factory = createSafeXmlInputFactory();
        try (InputStream in = new ByteArrayInputStream(workbookXml)) {
            XMLStreamReader reader = factory.createXMLStreamReader(in);
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT
                        && "sheet".equals(reader.getLocalName())) {
                    String name = getAttributeByLocalName(reader, "name");
                    if (name != null && !name.isBlank()) {
                        names.add(name.strip());
                    }
                }
            }
        } catch (Exception ignored) {
            // 忽略 workbook 解析异常
        }
        return names;
    }

    private static List<List<String>> parseWorksheetRows(
            byte[] sheetXml, List<String> sharedStrings) {
        List<List<String>> rows = new ArrayList<>();
        XMLInputFactory factory = createSafeXmlInputFactory();
        try (InputStream in = new ByteArrayInputStream(sheetXml)) {
            XMLStreamReader reader = factory.createXMLStreamReader(in);
            List<String> currentRow = null;
            String cellType = null;
            int currentColIndex = -1;
            StringBuilder cellValue = null;

            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String local = reader.getLocalName();
                    if ("row".equals(local)) {
                        currentRow = new ArrayList<>();
                    } else if ("c".equals(local) && currentRow != null) {
                        cellType = getAttributeByLocalName(reader, "t");
                        String cellRef = getAttributeByLocalName(reader, "r");
                        currentColIndex = parseExcelColIndex(cellRef, currentRow.size());
                        cellValue = new StringBuilder();
                    } else if (("v".equals(local) || "t".equals(local)) && cellValue != null) {
                        cellValue.append(reader.getElementText());
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String local = reader.getLocalName();
                    if ("c".equals(local) && currentRow != null && cellValue != null) {
                        String rawVal = cellValue.toString().strip();
                        String resolved = rawVal;
                        if ("s".equals(cellType)) {
                            try {
                                int ssIdx = Integer.parseInt(rawVal);
                                if (ssIdx >= 0 && ssIdx < sharedStrings.size()) {
                                    resolved = sharedStrings.get(ssIdx);
                                }
                            } catch (NumberFormatException ignored) {
                                // keep rawVal
                            }
                        } else if ("b".equals(cellType)) {
                            resolved = "1".equals(rawVal) ? "TRUE" : "FALSE";
                        }
                        while (currentRow.size() < currentColIndex) {
                            currentRow.add("");
                        }
                        currentRow.add(resolved);
                        cellValue = null;
                    } else if ("row".equals(local) && currentRow != null) {
                        if (currentRow.stream().anyMatch(c -> !c.isBlank())) {
                            rows.add(currentRow);
                        }
                        currentRow = null;
                    }
                }
            }
        } catch (XMLStreamException | IOException ignored) {
            // 忽略异常
        }
        return rows;
    }

    private static int parseExcelColIndex(String cellRef, int fallbackIndex) {
        if (cellRef == null || cellRef.isEmpty()) {
            return fallbackIndex;
        }
        int col = 0;
        int i = 0;
        while (i < cellRef.length() && Character.isLetter(cellRef.charAt(i))) {
            col = col * 26 + (Character.toUpperCase(cellRef.charAt(i)) - 'A' + 1);
            i++;
        }
        return col > 0 ? col - 1 : fallbackIndex;
    }

    /**
     * 解析 PowerPoint (.pptx) 各幻灯片。
     */
    private static String parsePptx(
            String docTitle, Map<String, byte[]> zipEntries, Map<String, Object> metadata) {
        List<Map.Entry<String, byte[]>> slideEntries =
                zipEntries.entrySet().stream()
                        .filter(e ->
                                e.getKey().startsWith("ppt/slides/slide")
                                        && e.getKey().endsWith(".xml"))
                        .sorted(Comparator.comparingInt(e -> extractSlideNumber(e.getKey())))
                        .toList();

        StringBuilder md = new StringBuilder();
        md.append("# ").append(docTitle).append("\n\n");

        XMLInputFactory factory = createSafeXmlInputFactory();
        int slideCount = 0;

        for (Map.Entry<String, byte[]> entry : slideEntries) {
            int slideNum = extractSlideNumber(entry.getKey());
            List<String> paragraphs = extractPptxSlideParagraphs(factory, entry.getValue());
            if (paragraphs.isEmpty()) {
                continue;
            }
            slideCount++;
            String firstLine = paragraphs.get(0);
            md.append("## Slide ").append(slideNum).append(": ").append(firstLine).append("\n\n");
            for (int i = 1; i < paragraphs.size(); i++) {
                md.append("- ").append(paragraphs.get(i)).append("\n");
            }
            md.append("\n");
        }

        metadata.put("slide_count", slideCount);
        return md.toString().strip();
    }

    private static int extractSlideNumber(String path) {
        String num = path.replaceAll("\\D+", "");
        try {
            return Integer.parseInt(num);
        } catch (NumberFormatException e) {
            return 999;
        }
    }

    private static List<String> extractPptxSlideParagraphs(
            XMLInputFactory factory, byte[] slideXml) {
        List<String> paragraphs = new ArrayList<>();
        try (InputStream in = new ByteArrayInputStream(slideXml)) {
            XMLStreamReader reader = factory.createXMLStreamReader(in);
            StringBuilder currentPara = null;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String local = reader.getLocalName();
                    if ("p".equals(local)) {
                        currentPara = new StringBuilder();
                    } else if ("t".equals(local) && currentPara != null) {
                        currentPara.append(reader.getElementText());
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if ("p".equals(reader.getLocalName()) && currentPara != null) {
                        String text = currentPara.toString().strip();
                        if (!text.isEmpty()) {
                            paragraphs.add(text);
                        }
                        currentPara = null;
                    }
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
        return paragraphs;
    }

    private static String getAttributeByLocalName(XMLStreamReader reader, String attrLocalName) {
        int count = reader.getAttributeCount();
        for (int i = 0; i < count; i++) {
            if (attrLocalName.equalsIgnoreCase(reader.getAttributeLocalName(i))) {
                return reader.getAttributeValue(i);
            }
        }
        return null;
    }
}
