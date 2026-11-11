package dev.ppmb.intellihub.rag.parser;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * 文本字节流智能字符集检测与解码工具。
 *
 * <p>优先识别 UTF-8 / UTF-16 BOM，再使用严格模式验证 UTF-8 字节序列；若包含非 UTF-8 的中文多字节序列（如 Windows 下导出的 GBK/GB18030
 * CSV/TXT），自动回退至 {@code GB18030} 解码，彻底避免中文乱码。
 */
public final class CharsetEncodingSupport {

    private static final Charset GB18030 = Charset.forName("GB18030");

    private CharsetEncodingSupport() {}

    public static String decodeToString(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        // 1. UTF-8 BOM (EF BB BF)
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        // 2. UTF-16BE BOM (FE FF)
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        // 3. UTF-16LE BOM (FF FE)
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        // 4. 严格校验是否为合法 UTF-8
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            // 5. 回退至超集 GB18030（兼容 GBK / GB2312）
            return new String(bytes, GB18030);
        }
    }

    /**
     * 提取不带路径与扩展名的文件标题。
     */
    public static String extractBaseTitle(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "未命名文档";
        }
        String normalized = fileName.replace('\\', '/').strip();
        int slashIdx = normalized.lastIndexOf('/');
        String simpleName = slashIdx >= 0 ? normalized.substring(slashIdx + 1) : normalized;
        int dotIdx = simpleName.lastIndexOf('.');
        String base = dotIdx > 0 ? simpleName.substring(0, dotIdx) : simpleName;
        return base.isBlank() ? "未命名文档" : base.strip();
    }

    /**
     * 提取小写文件扩展名（不含点号）。
     */
    public static String extractExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        String normalized = fileName.replace('\\', '/').strip();
        int slashIdx = normalized.lastIndexOf('/');
        String simpleName = slashIdx >= 0 ? normalized.substring(slashIdx + 1) : normalized;
        int dotIdx = simpleName.lastIndexOf('.');
        if (dotIdx < 0 || dotIdx == simpleName.length() - 1) {
            return "";
        }
        return simpleName.substring(dotIdx + 1).toLowerCase();
    }
}
