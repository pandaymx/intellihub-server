package dev.ppmb.intellihub.rag.parser;

import dev.ppmb.intellihub.core.exception.GlobalErrorCodeConstants;
import dev.ppmb.intellihub.core.exception.IntelliHubExceptionUtil;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 多格式文档解析器 SPI 接口。
 *
 * <p>各实现类负责将特定格式的文件（PDF、Word、Excel、PPT、CSV、HTML、JSON/YAML、源码、Markdown/TXT 等）解析并转换为结构化 Markdown
 * 文本与元数据，使下游 {@link dev.ppmb.intellihub.rag.splitter.SemanticMarkdownDocumentSplitter} 能够保留章节路径、表格表头与代码块。
 */
public interface DocumentParser {

    /**
     * 解析器名称。
     */
    String name();

    /**
     * 支持的文件扩展名列表（小写，不含点号，如 {@code ["pdf"]}）。
     */
    List<String> supportedExtensions();

    /**
     * 判断当前解析器是否支持给定的文件名或 MIME 类型。
     */
    default boolean supports(String fileName, String contentType) {
        String ext = CharsetEncodingSupport.extractExtension(fileName);
        if (!ext.isEmpty() && supportedExtensions().contains(ext)) {
            return true;
        }
        return false;
    }

    /**
     * 将字节数组解析为标准化 {@link ParsedDocument}。
     */
    ParsedDocument parse(String fileName, String contentType, byte[] contentBytes);

    /**
     * 将输入流解析为标准化 {@link ParsedDocument}。
     */
    default ParsedDocument parse(String fileName, String contentType, InputStream inputStream) {
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
}
