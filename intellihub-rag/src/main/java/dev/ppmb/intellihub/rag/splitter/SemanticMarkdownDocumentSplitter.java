package dev.ppmb.intellihub.rag.splitter;

import dev.ppmb.intellihub.core.exception.IntelliHubExceptionUtil;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;

/**
 * 语义感知型 Markdown 文档切分器（Semantic Markdown Document Splitter）。
 *
 * <p>相比单纯按固定字符/Token 截断的切分器，本实现针对技术文档与知识库场景进行了专门强化：
 *
 * <ul>
 *   <li><b>标题层级追踪</b>：自动维护 {@code # H1 > ## H2 > ### H3} 标题路径并写入元数据 {@code section_path}；
 *   <li><b>代码块完整性保护</b>：识别 {@code ```...```} 围栏代码块，避免在代码中间硬切导致语法残缺；
 *   <li><b>滑动窗口重叠（Overlap）</b>：超长段落在切分时自动保留尾部重叠上下文，避免跨块语义断裂；
 *   <li><b>确定性内容指纹</b>：为每个切片计算 SHA-256 {@code content_hash}，便于幂等入库与去重。
 * </ul>
 */
public class SemanticMarkdownDocumentSplitter implements DocumentTransformer {

    public static final int DEFAULT_MAX_CHUNK_CHARS = 800;
    public static final int DEFAULT_CHUNK_OVERLAP_CHARS = 120;

    public static final String META_SOURCE_DOC_ID = "source_doc_id";
    public static final String META_SECTION_PATH = "section_path";
    public static final String META_CHUNK_INDEX = "chunk_index";
    public static final String META_CHUNK_TOTAL = "chunk_total";
    public static final String META_CONTENT_HASH = "content_hash";

    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$");

    private final int maxChunkChars;
    private final int chunkOverlapChars;
    private final boolean prependSectionHeader;

    public SemanticMarkdownDocumentSplitter() {
        this(DEFAULT_MAX_CHUNK_CHARS, DEFAULT_CHUNK_OVERLAP_CHARS, true);
    }

    public SemanticMarkdownDocumentSplitter(
            int maxChunkChars, int chunkOverlapChars, boolean prependSectionHeader) {
        if (maxChunkChars < 64) {
            throw IntelliHubExceptionUtil.invalidParamException(
                    "maxChunkChars 不能小于 64，当前值: {}", maxChunkChars);
        }
        if (chunkOverlapChars < 0 || chunkOverlapChars >= maxChunkChars) {
            throw IntelliHubExceptionUtil.invalidParamException(
                    "chunkOverlapChars 必须介于 [0, maxChunkChars) 之间，当前值: {}", chunkOverlapChars);
        }
        this.maxChunkChars = maxChunkChars;
        this.chunkOverlapChars = chunkOverlapChars;
        this.prependSectionHeader = prependSectionHeader;
    }

    @Override
    public List<Document> apply(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        List<Document> result = new ArrayList<>();
        for (Document doc : documents) {
            if (doc != null) {
                result.addAll(splitDocument(doc));
            }
        }
        return result;
    }

    /**
     * 切分单个 {@link Document} 并注入切片元数据。
     */
    public List<Document> splitDocument(Document document) {
        Objects.requireNonNull(document, "document 不能为空");
        String rawText = document.getText();
        if (rawText == null || rawText.isBlank()) {
            return List.of();
        }

        String sourceDocId =
                document.getId() != null && !document.getId().isBlank()
                        ? document.getId()
                        : UUID.randomUUID().toString();

        List<SectionBlock> blocks = parseMarkdownBlocks(rawText);
        List<RawChunk> rawChunks = packBlocksIntoChunks(blocks);

        int total = rawChunks.size();
        List<Document> chunks = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            RawChunk rawChunk = rawChunks.get(i);
            String finalText =
                    prependSectionHeader && !rawChunk.sectionPath().isEmpty()
                            ? "[Section: " + rawChunk.sectionPath() + "]\n" + rawChunk.content()
                            : rawChunk.content();
            String contentHash = sha256Hex(finalText);
            String chunkId = sourceDocId + "#chunk-" + i;

            Map<String, Object> metadata = new LinkedHashMap<>(document.getMetadata());
            metadata.put(META_SOURCE_DOC_ID, sourceDocId);
            metadata.put(META_SECTION_PATH, rawChunk.sectionPath());
            metadata.put(META_CHUNK_INDEX, i);
            metadata.put(META_CHUNK_TOTAL, total);
            metadata.put(META_CONTENT_HASH, contentHash);

            chunks.add(Document.builder().id(chunkId).text(finalText).metadata(metadata).build());
        }
        return chunks;
    }

    private List<SectionBlock> parseMarkdownBlocks(String text) {
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        String[] headingHierarchy = new String[6];
        List<SectionBlock> blocks = new ArrayList<>();

        StringBuilder currentParagraph = new StringBuilder();
        boolean inCodeFence = false;

        for (String line : lines) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("```")) {
                if (!inCodeFence) {
                    flushBlock(blocks, currentParagraph, buildSectionPath(headingHierarchy), false);
                    inCodeFence = true;
                    currentParagraph.append(line).append('\n');
                } else {
                    currentParagraph.append(line);
                    flushBlock(blocks, currentParagraph, buildSectionPath(headingHierarchy), true);
                    inCodeFence = false;
                }
                continue;
            }

            if (inCodeFence) {
                currentParagraph.append(line).append('\n');
                continue;
            }

            Matcher headingMatcher = HEADING_PATTERN.matcher(trimmed);
            if (headingMatcher.matches()) {
                flushBlock(blocks, currentParagraph, buildSectionPath(headingHierarchy), false);
                int level = headingMatcher.group(1).length();
                String title = headingMatcher.group(2).strip();
                headingHierarchy[level - 1] = title;
                for (int i = level; i < 6; i++) {
                    headingHierarchy[i] = null;
                }
                continue;
            }

            if (trimmed.isEmpty()) {
                flushBlock(blocks, currentParagraph, buildSectionPath(headingHierarchy), false);
            } else {
                if (!currentParagraph.isEmpty()) {
                    currentParagraph.append('\n');
                }
                currentParagraph.append(line);
            }
        }

        flushBlock(blocks, currentParagraph, buildSectionPath(headingHierarchy), inCodeFence);
        return blocks;
    }

    private static void flushBlock(
            List<SectionBlock> blocks,
            StringBuilder buffer,
            String sectionPath,
            boolean codeBlock) {
        String content = buffer.toString().strip();
        buffer.setLength(0);
        if (!content.isEmpty()) {
            blocks.add(new SectionBlock(sectionPath, content, codeBlock));
        }
    }

    private static String buildSectionPath(String[] hierarchy) {
        StringBuilder sb = new StringBuilder();
        for (String h : hierarchy) {
            if (h != null && !h.isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append(" > ");
                }
                sb.append(h);
            }
        }
        return sb.toString();
    }

    private List<RawChunk> packBlocksIntoChunks(List<SectionBlock> blocks) {
        List<RawChunk> chunks = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        String currentSection = "";

        for (SectionBlock block : blocks) {
            boolean sectionChanged =
                    !buffer.isEmpty() && !Objects.equals(currentSection, block.sectionPath());
            boolean exceedsLimit =
                    !buffer.isEmpty()
                            && (buffer.length() + 2 + block.content().length() > maxChunkChars);

            if (sectionChanged || exceedsLimit) {
                String emitted = buffer.toString().strip();
                if (!emitted.isEmpty()) {
                    chunks.add(new RawChunk(currentSection, emitted));
                }
                buffer.setLength(0);
                if (!sectionChanged && chunkOverlapChars > 0 && !block.codeBlock()) {
                    String overlap = extractTailOverlap(emitted, chunkOverlapChars);
                    if (!overlap.isEmpty()) {
                        buffer.append(overlap).append("\n\n");
                    }
                }
            }

            currentSection = block.sectionPath();
            if (block.content().length() > maxChunkChars && !block.codeBlock()) {
                if (!buffer.isEmpty()) {
                    chunks.add(new RawChunk(currentSection, buffer.toString().strip()));
                    buffer.setLength(0);
                }
                for (String piece : splitLongTextWithOverlap(block.content())) {
                    chunks.add(new RawChunk(currentSection, piece));
                }
            } else {
                if (!buffer.isEmpty()) {
                    buffer.append("\n\n");
                }
                buffer.append(block.content());
            }
        }

        if (!buffer.isEmpty()) {
            String remaining = buffer.toString().strip();
            if (!remaining.isEmpty()) {
                chunks.add(new RawChunk(currentSection, remaining));
            }
        }
        return chunks;
    }

    private List<String> splitLongTextWithOverlap(String text) {
        List<String> pieces = new ArrayList<>();
        int step = Math.max(1, maxChunkChars - chunkOverlapChars);
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + maxChunkChars);
            String slice = text.substring(start, end).strip();
            if (!slice.isEmpty()) {
                pieces.add(slice);
            }
            if (end >= text.length()) {
                break;
            }
            start += step;
        }
        return pieces;
    }

    private static String extractTailOverlap(String text, int maxOverlap) {
        if (text == null || text.length() <= maxOverlap) {
            return text == null ? "" : text;
        }
        return text.substring(text.length() - maxOverlap).stripLeading();
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }

    public int getMaxChunkChars() {
        return maxChunkChars;
    }

    public int getChunkOverlapChars() {
        return chunkOverlapChars;
    }

    public boolean isPrependSectionHeader() {
        return prependSectionHeader;
    }

    private record SectionBlock(String sectionPath, String content, boolean codeBlock) {}

    private record RawChunk(String sectionPath, String content) {}
}
