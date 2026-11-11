package dev.ppmb.intellihub.rag.ingest;

import dev.ppmb.intellihub.core.exception.IntelliHubExceptionUtil;
import dev.ppmb.intellihub.rag.parser.MultiFormatDocumentParserRegistry;
import dev.ppmb.intellihub.rag.parser.ParsedDocument;
import dev.ppmb.intellihub.rag.splitter.SemanticMarkdownDocumentSplitter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;

/**
 * 知识库文档摄取与清洗流水线（Document Ingestion Pipeline）。
 *
 * <ul>
 *   <li>支持将原始文档经由 {@link DocumentTransformer}（默认 {@link SemanticMarkdownDocumentSplitter}）切分为语义块；
 *   <li>自动附加租户与知识库隔离元数据（{@code tenant_id}、{@code kb_id}、{@code ingested_at}）；
 *   <li>基于 SHA-256 内容哈希（{@code content_hash}）执行幂等去重，防止重复摄取相同文档块污染向量库。
 * </ul>
 */
public class DocumentIngestionPipeline {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionPipeline.class);

    public static final String META_TENANT_ID = "tenant_id";
    public static final String META_KB_ID = "kb_id";
    public static final String META_INGESTED_AT = "ingested_at";

    private final DocumentTransformer splitter;
    private final MultiFormatDocumentParserRegistry parserRegistry;
    private final Set<String> ingestedContentHashes = ConcurrentHashMap.newKeySet();

    public DocumentIngestionPipeline() {
        this(new SemanticMarkdownDocumentSplitter(), new MultiFormatDocumentParserRegistry());
    }

    public DocumentIngestionPipeline(DocumentTransformer splitter) {
        this(splitter, new MultiFormatDocumentParserRegistry());
    }

    public DocumentIngestionPipeline(
            DocumentTransformer splitter, MultiFormatDocumentParserRegistry parserRegistry) {
        this.splitter = Objects.requireNonNull(splitter, "splitter 不能为空");
        this.parserRegistry =
                parserRegistry != null ? parserRegistry : new MultiFormatDocumentParserRegistry();
    }

    public MultiFormatDocumentParserRegistry getParserRegistry() {
        return parserRegistry;
    }

    /**
     * 解析任意受支持格式的原始文件（PDF / DOCX / XLSX / PPTX / CSV / HTML / JSON / YAML / 源码 / Markdown / TXT）。
     */
    public ParsedDocument parseFile(String fileName, String contentType, byte[] contentBytes) {
        return parserRegistry.parse(fileName, contentType, contentBytes);
    }

    /**
     * 一站式解析多格式文件并切分、去重后写入目标知识库接收器。
     */
    public FileIngestionResult ingestFile(
            String fileName,
            String contentType,
            byte[] contentBytes,
            Map<String, Object> commonMetadata,
            Consumer<List<Document>> sink) {
        if (sink == null) {
            throw IntelliHubExceptionUtil.invalidParamException("文档写入接收器 sink 不能为空");
        }
        ParsedDocument parsed = parserRegistry.parse(fileName, contentType, contentBytes);
        Map<String, Object> safeMeta =
                commonMetadata != null
                        ? new LinkedHashMap<>(commonMetadata)
                        : new LinkedHashMap<>();
        if (!safeMeta.containsKey("title")
                || safeMeta.get("title") == null
                || safeMeta.get("title").toString().isBlank()) {
            safeMeta.put("title", parsed.title());
        }
        Document rawDoc = parsed.toSpringAiDocument(safeMeta);
        List<Document> chunks = process(List.of(rawDoc), safeMeta);
        if (!chunks.isEmpty()) {
            sink.accept(chunks);
        }
        return new FileIngestionResult(parsed, chunks);
    }

    /**
     * 摄取单篇或多篇原始文档，返回去重并富化元数据后的切片列表。
     */
    public List<Document> process(List<Document> rawDocuments, Map<String, Object> commonMetadata) {
        if (rawDocuments == null || rawDocuments.isEmpty()) {
            return List.of();
        }
        Map<String, Object> safeMeta = commonMetadata != null ? commonMetadata : Map.of();

        List<Document> enrichedDocs = new ArrayList<>(rawDocuments.size());
        String nowIso = Instant.now().toString();
        for (Document doc : rawDocuments) {
            if (doc == null || doc.getText() == null || doc.getText().isBlank()) {
                continue;
            }
            Map<String, Object> mergedMeta = new LinkedHashMap<>(doc.getMetadata());
            mergedMeta.putAll(safeMeta);
            mergedMeta.putIfAbsent(META_INGESTED_AT, nowIso);
            enrichedDocs.add(
                    Document.builder()
                            .id(doc.getId())
                            .text(doc.getText())
                            .metadata(mergedMeta)
                            .build());
        }

        List<Document> splitChunks = splitter.apply(enrichedDocs);
        List<Document> deduplicated = new ArrayList<>(splitChunks.size());

        for (Document chunk : splitChunks) {
            String hash = resolveDeduplicationKey(chunk);
            if (ingestedContentHashes.add(hash)) {
                deduplicated.add(chunk);
            } else {
                log.debug(
                        "[DocumentIngestionPipeline] 跳过重复知识切片: id={}, hash={}",
                        chunk.getId(),
                        hash);
            }
        }
        return deduplicated;
    }

    /**
     * 摄取文档并直接写入目标接收器（如 {@code HybridKnowledgeStore::addAll}）。
     */
    public IngestionResult ingest(
            List<Document> rawDocuments,
            Map<String, Object> commonMetadata,
            Consumer<List<Document>> sink) {
        if (sink == null) {
            throw IntelliHubExceptionUtil.invalidParamException("文档写入接收器 sink 不能为空");
        }
        int inputCount = rawDocuments == null ? 0 : rawDocuments.size();
        List<Document> chunks = process(rawDocuments, commonMetadata);
        if (!chunks.isEmpty()) {
            sink.accept(chunks);
        }
        return new IngestionResult(inputCount, chunks.size());
    }

    /**
     * 清空已记录的去重指纹缓存。
     */
    public void clearDeduplicationCache() {
        ingestedContentHashes.clear();
    }

    /**
     * 将持久化存储中已存在的文档切片指纹同步注册至去重表（用于服务重启后恢复幂等状态）。
     */
    public void registerExistingDocuments(java.util.Collection<Document> existingDocuments) {
        if (existingDocuments == null || existingDocuments.isEmpty()) {
            return;
        }
        for (Document chunk : existingDocuments) {
            if (chunk != null) {
                ingestedContentHashes.add(resolveDeduplicationKey(chunk));
            }
        }
    }

    private static String resolveDeduplicationKey(Document chunk) {
        Map<String, Object> meta = chunk.getMetadata();
        Object tenant = meta.getOrDefault(META_TENANT_ID, "default");
        Object kb = meta.getOrDefault(META_KB_ID, "default");
        Object hash = meta.get(SemanticMarkdownDocumentSplitter.META_CONTENT_HASH);
        String contentHash =
                hash != null
                        ? hash.toString()
                        : SemanticMarkdownDocumentSplitter.sha256Hex(
                                chunk.getText() == null ? "" : chunk.getText());
        return tenant + ":" + kb + ":" + contentHash;
    }

    public record IngestionResult(int sourceDocumentCount, int ingestedChunkCount) {}

    public record FileIngestionResult(ParsedDocument parsedDocument, List<Document> chunks) {}
}
