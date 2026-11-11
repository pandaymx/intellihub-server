package dev.ppmb.intellihub.rag.parser;

import dev.ppmb.intellihub.core.exception.GlobalErrorCodeConstants;
import dev.ppmb.intellihub.core.exception.IntelliHubExceptionUtil;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * PDF 文档解析器（基于 Apache PDFBox 3.x）。
 *
 * <ul>
 *   <li>支持按页提取文本并生成 {@code ## Page N} 二级标题，使下游切分器自动记录页码溯源路径（如 {@code 员工手册 > Page 3}）；
 *   <li>自动提取 PDF 元数据（标题、作者、主题、总页数 {@code page_count}）；
 *   <li>对空白页自动跳过并清理多余连字符与空行。
 * </ul>
 */
public class PdfDocumentParser implements DocumentParser {

    @Override
    public String name() {
        return "PdfDocumentParser";
    }

    @Override
    public List<String> supportedExtensions() {
        return List.of("pdf");
    }

    @Override
    public boolean supports(String fileName, String contentType) {
        if (DocumentParser.super.supports(fileName, contentType)) {
            return true;
        }
        return contentType != null && contentType.toLowerCase().contains("application/pdf");
    }

    @Override
    public ParsedDocument parse(String fileName, String contentType, byte[] contentBytes) {
        if (contentBytes == null || contentBytes.length == 0) {
            throw IntelliHubExceptionUtil.exception(
                    GlobalErrorCodeConstants.AI_DOCUMENT_PARSE_ERROR, "PDF 文件内容为空: {}", fileName);
        }

        String baseTitle = CharsetEncodingSupport.extractBaseTitle(fileName);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("file_size_bytes", contentBytes.length);

        try (PDDocument document = Loader.loadPDF(contentBytes)) {
            int totalPages = document.getNumberOfPages();
            metadata.put("page_count", totalPages);

            String docTitle = baseTitle;
            PDDocumentInformation info = document.getDocumentInformation();
            if (info != null) {
                if (info.getTitle() != null && !info.getTitle().isBlank()) {
                    docTitle = info.getTitle().strip();
                }
                if (info.getAuthor() != null && !info.getAuthor().isBlank()) {
                    metadata.put("author", info.getAuthor().strip());
                }
                if (info.getSubject() != null && !info.getSubject().isBlank()) {
                    metadata.put("subject", info.getSubject().strip());
                }
            }

            StringBuilder md = new StringBuilder();
            md.append("# ").append(docTitle).append("\n\n");

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            int nonEmptyPages = 0;
            for (int page = 1; page <= totalPages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = normalizePageText(stripper.getText(document));
                if (pageText.isBlank()) {
                    continue;
                }
                nonEmptyPages++;
                if (totalPages > 1) {
                    md.append("## Page ").append(page).append("\n\n");
                }
                md.append(pageText).append("\n\n");
            }
            metadata.put("extracted_pages", nonEmptyPages);
            metadata.put("mime_type", "application/pdf");

            if (nonEmptyPages == 0) {
                metadata.put("scanned_pdf", true);
                md.append("> [多模态视觉 PDF：共 ")
                        .append(totalPages)
                        .append(" 页图像扫描层，支持通过 Gemini 原生多模态 Media 直接进行视觉解析]\n");
            }
            String finalMd = md.toString().strip();
            return new ParsedDocument(
                    fileName, "PDF", docTitle, finalMd, finalMd.length(), metadata);
        } catch (IOException e) {
            throw IntelliHubExceptionUtil.exception(
                    GlobalErrorCodeConstants.AI_DOCUMENT_PARSE_ERROR,
                    "解析 PDF 文件失败 (" + fileName + "): " + e.getMessage());
        }
    }

    private static String normalizePageText(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return raw.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\u0000', ' ')
                .replaceAll("\n{3,}", "\n\n")
                .strip();
    }
}
