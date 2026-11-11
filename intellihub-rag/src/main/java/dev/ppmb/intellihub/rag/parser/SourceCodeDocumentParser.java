package dev.ppmb.intellihub.rag.parser;

import dev.ppmb.intellihub.core.exception.GlobalErrorCodeConstants;
import dev.ppmb.intellihub.core.exception.IntelliHubExceptionUtil;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 源代码文档解析器（支持 Java、Kotlin、Python、TypeScript/JavaScript、Go、Rust、SQL、Shell、C/C++ 等）。
 *
 * <p>自动提取源码文件中的类型/函数/表结构签名摘要，并使用对应语言的 Markdown 围栏代码块包裹，配合 {@link
 * dev.ppmb.intellihub.rag.splitter.SemanticMarkdownDocumentSplitter} 实现代码块完整性保护。
 */
public class SourceCodeDocumentParser implements DocumentParser {

    private static final Map<String, String> EXT_TO_LANG =
            Map.ofEntries(
                    Map.entry("java", "java"),
                    Map.entry("kt", "kotlin"),
                    Map.entry("kts", "kotlin"),
                    Map.entry("py", "python"),
                    Map.entry("ts", "typescript"),
                    Map.entry("tsx", "tsx"),
                    Map.entry("js", "javascript"),
                    Map.entry("jsx", "jsx"),
                    Map.entry("go", "go"),
                    Map.entry("rs", "rust"),
                    Map.entry("sql", "sql"),
                    Map.entry("sh", "bash"),
                    Map.entry("bash", "bash"),
                    Map.entry("c", "c"),
                    Map.entry("cpp", "cpp"),
                    Map.entry("h", "c"),
                    Map.entry("cs", "csharp"),
                    Map.entry("vue", "vue"),
                    Map.entry("css", "css"),
                    Map.entry("scss", "scss"));

    private static final Pattern DECLARATION_PATTERN =
            Pattern.compile(
                    "^\\s*(?:public\\s+|private\\s+|protected\\s+|export\\s+|async\\s+)*"
                            + "(?:class|interface|record|enum|def|function|func|struct|CREATE\\s+TABLE)\\s+([A-Za-z0-9_\\.]+)",
                    Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    @Override
    public String name() {
        return "SourceCodeDocumentParser";
    }

    @Override
    public List<String> supportedExtensions() {
        return List.copyOf(EXT_TO_LANG.keySet());
    }

    @Override
    public ParsedDocument parse(String fileName, String contentType, byte[] contentBytes) {
        if (contentBytes == null || contentBytes.length == 0) {
            throw IntelliHubExceptionUtil.exception(
                    GlobalErrorCodeConstants.AI_DOCUMENT_PARSE_ERROR, "源代码文件内容为空: {}", fileName);
        }

        String rawCode = CharsetEncodingSupport.decodeToString(contentBytes).strip();
        String ext = CharsetEncodingSupport.extractExtension(fileName);
        String lang = EXT_TO_LANG.getOrDefault(ext, "text");

        List<String> symbols = new ArrayList<>();
        Matcher matcher = DECLARATION_PATTERN.matcher(rawCode);
        while (matcher.find() && symbols.size() < 20) {
            String sym = matcher.group(1);
            if (sym != null && !sym.isBlank() && !symbols.contains(sym)) {
                symbols.add(sym);
            }
        }

        int lineCount = rawCode.replace("\r\n", "\n").split("\n", -1).length;

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("file_size_bytes", contentBytes.length);
        metadata.put("language", lang);
        metadata.put("line_count", lineCount);
        if (!symbols.isEmpty()) {
            metadata.put("declared_symbols", String.join(", ", symbols));
        }

        StringBuilder md = new StringBuilder();
        md.append("# ").append(fileName).append("\n\n");
        if (!symbols.isEmpty()) {
            md.append("- **Language**: `").append(lang).append("`\n");
            md.append("- **Declared Symbols**: ").append(String.join(", ", symbols)).append("\n\n");
        }
        md.append("## Source Code\n\n");
        md.append("```").append(lang).append("\n").append(rawCode).append("\n```\n");

        String finalMd = md.toString().strip();
        return new ParsedDocument(
                fileName,
                "CODE:" + lang.toUpperCase(),
                fileName,
                finalMd,
                finalMd.length(),
                metadata);
    }
}
