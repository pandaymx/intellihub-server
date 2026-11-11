package dev.ppmb.intellihub.rag.parser;

import dev.ppmb.intellihub.core.exception.GlobalErrorCodeConstants;
import dev.ppmb.intellihub.core.exception.IntelliHubExceptionUtil;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * 图像多模态文档解析器（支持 {@code .png}、{@code .jpg}、{@code .jpeg}、{@code .webp}、{@code .gif}、{@code .bmp}）。
 *
 * <p>自动提取图片分辨率（宽高像素）、MIME 类型与字节大小元数据，生成多模态视觉占位描述，配合后端 {@link
 * org.springframework.ai.content.Media} 原生多模态通道将原始图像字节流直接传入 {@code gemini-3.5-flash-lite} / {@code
 * gemini-3.8-flash} 进行架构图解析、报错截图诊断与视觉问答。
 */
public class ImageDocumentParser implements DocumentParser {

    @Override
    public String name() {
        return "ImageDocumentParser";
    }

    @Override
    public List<String> supportedExtensions() {
        return List.of("png", "jpg", "jpeg", "webp", "gif", "bmp");
    }

    @Override
    public boolean supports(String fileName, String contentType) {
        if (DocumentParser.super.supports(fileName, contentType)) {
            return true;
        }
        return contentType != null && contentType.toLowerCase().startsWith("image/");
    }

    @Override
    public ParsedDocument parse(String fileName, String contentType, byte[] contentBytes) {
        if (contentBytes == null || contentBytes.length == 0) {
            throw IntelliHubExceptionUtil.exception(
                    GlobalErrorCodeConstants.AI_DOCUMENT_PARSE_ERROR, "图片文件内容为空: {}", fileName);
        }

        String ext = CharsetEncodingSupport.extractExtension(fileName);
        String resolvedMime = resolveImageMimeType(ext, contentType, contentBytes);
        String formatLabel =
                "IMAGE:"
                        + (ext.isEmpty()
                                ? resolvedMime.replace("image/", "").toUpperCase()
                                : ext.toUpperCase());
        String title = CharsetEncodingSupport.extractBaseTitle(fileName);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("file_size_bytes", contentBytes.length);
        metadata.put("mime_type", resolvedMime);
        metadata.put("multimodal_media", true);

        int width = -1;
        int height = -1;
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(contentBytes));
            if (img != null) {
                width = img.getWidth();
                height = img.getHeight();
                metadata.put("width", width);
                metadata.put("height", height);
            }
        } catch (Exception ignored) {
            // 部分 WebP 格式在标准 ImageIO 无插件时跳过宽高读取
        }

        StringBuilder md = new StringBuilder();
        md.append("# ").append(title).append("\n\n");
        md.append("- **多模态视觉附件**: `").append(fileName).append("`\n");
        md.append("- **MIME 类型**: `").append(resolvedMime).append("`\n");
        if (width > 0 && height > 0) {
            md.append("- **图像分辨率**: ").append(width).append(" × ").append(height).append(" px\n");
        }
        md.append("- **文件大小**: ").append(contentBytes.length).append(" bytes\n");

        String finalMd = md.toString().strip();
        return new ParsedDocument(
                fileName, formatLabel, title, finalMd, finalMd.length(), metadata);
    }

    public static String resolveImageMimeType(String ext, String contentType, byte[] bytes) {
        if (contentType != null && contentType.toLowerCase().startsWith("image/")) {
            return contentType.split(";")[0].strip().toLowerCase();
        }
        if (bytes != null && bytes.length >= 12) {
            // PNG: 89 50 4E 47
            if ((bytes[0] & 0xFF) == 0x89
                    && bytes[1] == 'P'
                    && bytes[2] == 'N'
                    && bytes[3] == 'G') {
                return "image/png";
            }
            // JPEG: FF D8 FF
            if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
                return "image/jpeg";
            }
            // GIF: GIF8
            if (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8') {
                return "image/gif";
            }
            // WEBP: RIFF....WEBP
            if (bytes[0] == 'R'
                    && bytes[1] == 'I'
                    && bytes[2] == 'F'
                    && bytes[3] == 'F'
                    && bytes[8] == 'W'
                    && bytes[9] == 'E'
                    && bytes[10] == 'B'
                    && bytes[11] == 'P') {
                return "image/webp";
            }
        }
        return switch (ext != null ? ext.toLowerCase() : "") {
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            default -> "image/png";
        };
    }
}
