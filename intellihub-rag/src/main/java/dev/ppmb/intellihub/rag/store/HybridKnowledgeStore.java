package dev.ppmb.intellihub.rag.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ppmb.intellihub.rag.retrieval.RagSearchMode;
import dev.ppmb.intellihub.rag.retrieval.RagSearchRequest;
import dev.ppmb.intellihub.rag.retrieval.ScoredDocument;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * 混合知识检索与双路下推编排引擎（Hybrid Knowledge Store：Vector Pushdown + Lexical Pushdown + RRF 融合 + 本地磁盘持久化）。
 *
 * <p>架构分层：
 *
 * <ul>
 *   <li><b>可插拔双路下推 SPI（{@link VectorSearchBackend} + {@link LexicalSearchBackend}）</b>：十万/百万级生产规模下，
 *       可直接注入基于 PostgreSQL {@code pgvector}（HNSW 索引）与 {@code pg_search / tsvector}（GIN 倒排索引）或
 *       Elasticsearch 的实现，将元数据过滤、向量最近邻搜索与 BM25 计算 100% 下推到数据库完成；
 *   <li><b>本地持久化双路索引（File-Backed Hybrid Index）</b>：当配置了 {@code persistencePath} 时，所有文档切片、元数据及
 *       预计算的稠密向量（{@code float[]}）均以原子写时替换（Write-Temp-and-Rename）方式持久化至磁盘，重启服务无需重复请求上游 Embedding API；
 *   <li><b>向量缺失自动回填（Auto-Backfill）</b>：若历史切片在无 Embedding 模型时写入，当后续接入 Gemini Embedding 启动时会自动为缺失向量的切片补齐稠密向量并落盘；
 *   <li><b>O(K log K) 轻量级倒数秩融合（RRF）</b>：仅对两路下推返回的 Top-K 候选并集执行 RRF 排序融合，杜绝应用层全表扫描。
 * </ul>
 */
public class HybridKnowledgeStore {

    private static final Logger log = LoggerFactory.getLogger(HybridKnowledgeStore.class);
    private static final ObjectMapper PERSISTENCE_MAPPER = new ObjectMapper();

    private static final double BM25_K1 = 1.5;
    private static final double BM25_B = 0.75;
    private static final int MIN_BACKEND_RECALL_WINDOW = 20;

    /** 匹配连续的 ASCII 字母数字标识符（含下划线、点、连字符，如错误码或类名） */
    private static final Pattern ASCII_TOKEN_PATTERN =
            Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.-]*");

    /** 匹配连续的中文字符序列 */
    private static final Pattern CJK_SEQUENCE_PATTERN = Pattern.compile("[\\u4e00-\\u9fa5]+");

    private final VectorSearchBackend vectorBackend;
    private final LexicalSearchBackend lexicalBackend;
    private final InMemoryHybridIndex builtinIndex;

    public HybridKnowledgeStore() {
        this((EmbeddingModel) null, null);
    }

    public HybridKnowledgeStore(EmbeddingModel embeddingModel) {
        this(embeddingModel, null);
    }

    public HybridKnowledgeStore(EmbeddingModel embeddingModel, Path persistencePath) {
        InMemoryHybridIndex sharedMemoryIndex =
                new InMemoryHybridIndex(embeddingModel, persistencePath);
        this.vectorBackend = sharedMemoryIndex;
        this.lexicalBackend = sharedMemoryIndex;
        this.builtinIndex = sharedMemoryIndex;
    }

    public HybridKnowledgeStore(
            VectorSearchBackend vectorBackend, LexicalSearchBackend lexicalBackend) {
        this.vectorBackend = Objects.requireNonNull(vectorBackend, "vectorBackend 不能为空");
        this.lexicalBackend = Objects.requireNonNull(lexicalBackend, "lexicalBackend 不能为空");
        this.builtinIndex = (vectorBackend instanceof InMemoryHybridIndex idx) ? idx : null;
    }

    /**
     * 写入单条文档切片。
     */
    public void add(Document document) {
        if (document == null || document.getText() == null || document.getText().isBlank()) {
            return;
        }
        addAll(List.of(document));
    }

    /**
     * 批量写入文档切片并同步至向量与词法检索后端。
     */
    public void addAll(Collection<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        lexicalBackend.upsert(documents);
        if (vectorBackend != lexicalBackend) {
            vectorBackend.upsert(documents);
        }
    }

    /**
     * 按文档 ID 删除切片。
     */
    public boolean delete(String documentId) {
        if (documentId == null || documentId.isBlank()) {
            return false;
        }
        boolean deletedLex = lexicalBackend.delete(documentId);
        boolean deletedVec =
                (vectorBackend == lexicalBackend) ? deletedLex : vectorBackend.delete(documentId);
        return deletedLex || deletedVec;
    }

    /**
     * 清空存储中的所有文档切片。
     */
    public void clear() {
        lexicalBackend.clear();
        if (vectorBackend != lexicalBackend) {
            vectorBackend.clear();
        }
    }

    /**
     * 当前已索引的切片总数。
     */
    public int size() {
        return lexicalBackend.size();
    }

    public boolean isVectorAvailable() {
        return vectorBackend.isAvailable();
    }

    public int getVectorizedCount() {
        return builtinIndex != null ? builtinIndex.vectorizedCount() : 0;
    }

    public int getVectorDimensions() {
        return builtinIndex != null ? builtinIndex.vectorDimensions() : 0;
    }

    public boolean isPersistent() {
        return builtinIndex != null && builtinIndex.persistencePath != null;
    }

    public String getPersistencePath() {
        return (builtinIndex != null && builtinIndex.persistencePath != null)
                ? builtinIndex.persistencePath.toAbsolutePath().toString()
                : null;
    }

    /**
     * 获取当前存储中的所有原始 {@link Document} 列表（用于重启后同步去重哈希或后台巡检）。
     */
    public List<Document> listDocuments() {
        return builtinIndex != null ? builtinIndex.listDocuments() : List.of();
    }

    /**
     * 列出当前存储中的知识切片（无需搜索关键词，按摄取顺序返回）。
     */
    public List<ScoredDocument> listAll(int limit, Map<String, Object> metadataFilters) {
        if (builtinIndex == null) {
            return List.of();
        }
        return builtinIndex.listAll(limit, metadataFilters);
    }

    public VectorSearchBackend getVectorBackend() {
        return vectorBackend;
    }

    public LexicalSearchBackend getLexicalBackend() {
        return lexicalBackend;
    }

    /**
     * 执行知识检索（支持 {@code HYBRID}、{@code VECTOR_ONLY}、{@code BM25_ONLY}）。
     */
    public List<ScoredDocument> search(RagSearchRequest request) {
        Objects.requireNonNull(request, "RagSearchRequest 不能为空");
        RagSearchMode mode = request.searchMode();
        int backendWindow = Math.max(request.topK() * 2, MIN_BACKEND_RECALL_WINDOW);

        List<ScoredDocument> vectorHits = List.of();
        if (mode == RagSearchMode.HYBRID || mode == RagSearchMode.VECTOR_ONLY) {
            if (vectorBackend.isAvailable()) {
                vectorHits =
                        vectorBackend.searchSimilar(
                                request.query(), backendWindow, request.metadataFilters());
            }
            if (vectorHits.isEmpty()
                    && mode == RagSearchMode.VECTOR_ONLY
                    && !vectorBackend.isAvailable()) {
                log.warn("[HybridKnowledgeStore] 请求 VECTOR_ONLY 但向量后端未就绪，自动降级为 BM25_ONLY");
                mode = RagSearchMode.BM25_ONLY;
            } else if (vectorHits.isEmpty()
                    && mode == RagSearchMode.HYBRID
                    && !vectorBackend.isAvailable()) {
                mode = RagSearchMode.BM25_ONLY;
            }
        }

        List<ScoredDocument> lexicalHits =
                (mode == RagSearchMode.BM25_ONLY || mode == RagSearchMode.HYBRID)
                        ? lexicalBackend.searchLexical(
                                request.query(), backendWindow, request.metadataFilters())
                        : List.of();

        List<ScoredDocument> ranked;
        if (mode == RagSearchMode.BM25_ONLY) {
            ranked = filterByMinScore(lexicalHits, request.minScore());
        } else if (mode == RagSearchMode.VECTOR_ONLY) {
            ranked = filterByMinScore(vectorHits, request.minScore());
        } else {
            ranked =
                    fuseBackendHitsWithRrf(
                            vectorHits,
                            lexicalHits,
                            request.vectorWeight(),
                            request.bm25Weight(),
                            request.rrfK(),
                            request.minScore());
        }

        return ranked.size() <= request.topK() ? ranked : ranked.subList(0, request.topK());
    }

    private static List<ScoredDocument> filterByMinScore(
            List<ScoredDocument> hits, double minScore) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<ScoredDocument> filtered = new ArrayList<>(hits.size());
        for (ScoredDocument hit : hits) {
            if (hit.score() >= minScore) {
                filtered.add(hit);
            }
        }
        filtered.sort(Comparator.comparingDouble(ScoredDocument::score).reversed());
        return filtered;
    }

    private static List<ScoredDocument> fuseBackendHitsWithRrf(
            List<ScoredDocument> vectorHits,
            List<ScoredDocument> lexicalHits,
            double vectorWeight,
            double bm25Weight,
            int rrfK,
            double minScore) {
        Map<String, Document> docById = new LinkedHashMap<>();
        Map<String, Integer> vectorRankMap = new HashMap<>();
        Map<String, Double> vectorScoreMap = new HashMap<>();
        for (int i = 0; i < vectorHits.size(); i++) {
            ScoredDocument sd = vectorHits.get(i);
            String id = sd.document().getId();
            docById.put(id, sd.document());
            vectorRankMap.put(id, i + 1);
            vectorScoreMap.put(id, sd.vectorScore() != null ? sd.vectorScore() : sd.score());
        }

        Map<String, Integer> bm25RankMap = new HashMap<>();
        Map<String, Double> bm25ScoreMap = new HashMap<>();
        for (int i = 0; i < lexicalHits.size(); i++) {
            ScoredDocument sd = lexicalHits.get(i);
            String id = sd.document().getId();
            docById.putIfAbsent(id, sd.document());
            bm25RankMap.put(id, i + 1);
            bm25ScoreMap.put(id, sd.bm25Score() != null ? sd.bm25Score() : sd.score());
        }

        List<ScoredDocument> fused = new ArrayList<>(docById.size());
        for (Map.Entry<String, Document> entry : docById.entrySet()) {
            String id = entry.getKey();
            Integer vRank = vectorRankMap.get(id);
            Integer bRank = bm25RankMap.get(id);
            double rrfScore = 0.0;
            if (vRank != null) {
                rrfScore += vectorWeight / (rrfK + vRank);
            }
            if (bRank != null) {
                rrfScore += bm25Weight / (rrfK + bRank);
            }
            if (rrfScore >= minScore) {
                fused.add(
                        new ScoredDocument(
                                entry.getValue(),
                                rrfScore,
                                vectorScoreMap.get(id),
                                bm25ScoreMap.get(id),
                                null));
            }
        }
        fused.sort(Comparator.comparingDouble(ScoredDocument::score).reversed());
        return fused;
    }

    public static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA <= 0.0 || normB <= 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * 中英文混合分词（ASCII 词元小写化 + 中文单字 Unigram 与双字 Bigram 组合，兼顾召回率与精确度）。
     */
    public static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>();

        Matcher asciiMatcher = ASCII_TOKEN_PATTERN.matcher(lower);
        while (asciiMatcher.find()) {
            tokens.add(asciiMatcher.group());
        }

        Matcher cjkMatcher = CJK_SEQUENCE_PATTERN.matcher(lower);
        while (cjkMatcher.find()) {
            String seq = cjkMatcher.group();
            for (int i = 0; i < seq.length(); i++) {
                tokens.add(String.valueOf(seq.charAt(i)));
                if (i + 1 < seq.length()) {
                    tokens.add(seq.substring(i, i + 2));
                }
            }
        }
        return tokens;
    }

    /**
     * 默认内置的双路索引实现（同时实现 {@link VectorSearchBackend} 与 {@link LexicalSearchBackend}，支持本地文件持久化）。
     */
    private static final class InMemoryHybridIndex
            implements VectorSearchBackend, LexicalSearchBackend {

        private final EmbeddingModel embeddingModel;
        private final Path persistencePath;
        private final Map<String, IndexedEntry> entries = new ConcurrentHashMap<>();
        private final Object persistLock = new Object();

        private InMemoryHybridIndex(EmbeddingModel embeddingModel, Path persistencePath) {
            this.embeddingModel = embeddingModel;
            this.persistencePath = persistencePath;
            if (this.persistencePath != null) {
                loadFromDisk();
            }
        }

        @Override
        public void upsert(Collection<Document> documents) {
            if (documents == null || documents.isEmpty()) {
                return;
            }
            boolean changed = false;
            for (Document doc : documents) {
                if (doc == null || doc.getText() == null || doc.getText().isBlank()) {
                    continue;
                }
                String id =
                        doc.getId() != null && !doc.getId().isBlank()
                                ? doc.getId()
                                : UUID.randomUUID().toString();
                float[] vector = computeEmbeddingSafely(doc.getText());
                Map<String, Object> enrichedMeta = new LinkedHashMap<>(doc.getMetadata());
                if (vector != null && vector.length > 0) {
                    enrichedMeta.put("vector_dim", vector.length);
                }
                Document normalizedDoc =
                        Document.builder()
                                .id(id)
                                .text(doc.getText())
                                .metadata(enrichedMeta)
                                .build();
                List<String> tokens = tokenize(normalizedDoc.getText());
                Map<String, Integer> termFreq = new HashMap<>();
                for (String token : tokens) {
                    termFreq.merge(token, 1, Integer::sum);
                }
                entries.put(
                        id, new IndexedEntry(id, normalizedDoc, termFreq, tokens.size(), vector));
                changed = true;
            }
            if (changed) {
                saveToDisk();
            }
        }

        @Override
        public boolean delete(String documentId) {
            boolean removed = entries.remove(documentId) != null;
            if (removed) {
                saveToDisk();
            }
            return removed;
        }

        @Override
        public void clear() {
            entries.clear();
            saveToDisk();
        }

        @Override
        public int size() {
            return entries.size();
        }

        @Override
        public boolean isAvailable() {
            return embeddingModel != null;
        }

        int vectorizedCount() {
            int count = 0;
            for (IndexedEntry e : entries.values()) {
                if (e.vector() != null && e.vector().length > 0) {
                    count++;
                }
            }
            return count;
        }

        int vectorDimensions() {
            for (IndexedEntry e : entries.values()) {
                if (e.vector() != null && e.vector().length > 0) {
                    return e.vector().length;
                }
            }
            return embeddingModel != null ? embeddingModel.dimensions() : 0;
        }

        List<Document> listDocuments() {
            return entries.values().stream().map(IndexedEntry::document).toList();
        }

        List<ScoredDocument> listAll(int limit, Map<String, Object> metadataFilters) {
            List<IndexedEntry> candidates = filterByMetadata(metadataFilters);
            candidates.sort(Comparator.comparing(IndexedEntry::id));
            int cap = limit > 0 ? Math.min(limit, candidates.size()) : candidates.size();
            List<ScoredDocument> result = new ArrayList<>(cap);
            for (int i = 0; i < cap; i++) {
                IndexedEntry entry = candidates.get(i);
                Double vScore = (entry.vector() != null && entry.vector().length > 0) ? 1.0 : null;
                result.add(new ScoredDocument(entry.document(), 1.0, vScore, 1.0, 1.0));
            }
            return result;
        }

        @Override
        public List<ScoredDocument> searchSimilar(
                String query, int topK, Map<String, Object> metadataFilters) {
            float[] queryVector = computeEmbeddingSafely(query);
            if (queryVector == null) {
                return List.of();
            }
            List<IndexedEntry> candidates = filterByMetadata(metadataFilters);
            List<ScoredDocument> scored = new ArrayList<>();
            for (IndexedEntry entry : candidates) {
                if (entry.vector() == null || entry.vector().length != queryVector.length) {
                    continue;
                }
                double sim = cosineSimilarity(queryVector, entry.vector());
                if (sim > 0.0) {
                    scored.add(new ScoredDocument(entry.document(), sim, sim, null, null));
                }
            }
            scored.sort(Comparator.comparingDouble(ScoredDocument::score).reversed());
            return scored.size() <= topK ? scored : scored.subList(0, topK);
        }

        @Override
        public List<ScoredDocument> searchLexical(
                String query, int topK, Map<String, Object> metadataFilters) {
            List<String> queryTokens = tokenize(query);
            if (queryTokens.isEmpty()) {
                return List.of();
            }
            List<IndexedEntry> candidates = filterByMetadata(metadataFilters);
            if (candidates.isEmpty()) {
                return List.of();
            }

            int docCount = candidates.size();
            double avgDocLen =
                    candidates.stream().mapToInt(IndexedEntry::docLength).average().orElse(1.0);
            if (avgDocLen <= 0.0) {
                avgDocLen = 1.0;
            }

            Map<String, Integer> docFreq = new HashMap<>();
            for (String qToken : queryTokens) {
                if (docFreq.containsKey(qToken)) {
                    continue;
                }
                int df = 0;
                for (IndexedEntry entry : candidates) {
                    if (entry.termFreq().containsKey(qToken)) {
                        df++;
                    }
                }
                docFreq.put(qToken, df);
            }

            List<ScoredDocument> scored = new ArrayList<>();
            for (IndexedEntry entry : candidates) {
                double score = 0.0;
                for (String qToken : queryTokens) {
                    int tf = entry.termFreq().getOrDefault(qToken, 0);
                    if (tf == 0) {
                        continue;
                    }
                    int df = docFreq.getOrDefault(qToken, 0);
                    double idf = Math.log(1.0 + (docCount - df + 0.5) / (df + 0.5));
                    double normTf =
                            (tf * (BM25_K1 + 1.0))
                                    / (tf
                                            + BM25_K1
                                                    * (1.0
                                                            - BM25_B
                                                            + BM25_B
                                                                    * (entry.docLength()
                                                                            / avgDocLen)));
                    score += idf * normTf;
                }
                if (score > 0.0) {
                    scored.add(new ScoredDocument(entry.document(), score, null, score, null));
                }
            }
            scored.sort(Comparator.comparingDouble(ScoredDocument::score).reversed());
            return scored.size() <= topK ? scored : scored.subList(0, topK);
        }

        private List<IndexedEntry> filterByMetadata(Map<String, Object> filters) {
            if (filters == null || filters.isEmpty()) {
                return new ArrayList<>(entries.values());
            }
            List<IndexedEntry> matched = new ArrayList<>();
            for (IndexedEntry entry : entries.values()) {
                Map<String, Object> docMeta = entry.document().getMetadata();
                boolean ok = true;
                for (Map.Entry<String, Object> filter : filters.entrySet()) {
                    Object actual = docMeta.get(filter.getKey());
                    if (actual == null
                            || !Objects.equals(actual.toString(), filter.getValue().toString())) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    matched.add(entry);
                }
            }
            return matched;
        }

        private float[] computeEmbeddingSafely(String text) {
            if (embeddingModel == null || text == null || text.isBlank()) {
                return null;
            }
            try {
                return embeddingModel.embed(text);
            } catch (RuntimeException ex) {
                log.warn(
                        "[HybridKnowledgeStore] 生成 Embedding 向量失败，已自动降级为词法检索: {}", ex.getMessage());
                return null;
            }
        }

        private void loadFromDisk() {
            if (persistencePath == null || !Files.exists(persistencePath)) {
                return;
            }
            synchronized (persistLock) {
                try {
                    byte[] raw = Files.readAllBytes(persistencePath);
                    if (raw.length == 0) {
                        return;
                    }
                    List<PersistedChunkRecord> records =
                            PERSISTENCE_MAPPER.readValue(
                                    raw, new TypeReference<List<PersistedChunkRecord>>() {});
                    boolean backfilledAny = false;
                    for (PersistedChunkRecord rec : records) {
                        if (rec == null || rec.text() == null || rec.text().isBlank()) {
                            continue;
                        }
                        String id =
                                (rec.id() != null && !rec.id().isBlank())
                                        ? rec.id()
                                        : UUID.randomUUID().toString();
                        float[] vec = rec.vector();
                        if ((vec == null || vec.length == 0) && embeddingModel != null) {
                            vec = computeEmbeddingSafely(rec.text());
                            if (vec != null && vec.length > 0) {
                                backfilledAny = true;
                            }
                        }
                        Map<String, Object> meta =
                                rec.metadata() != null
                                        ? new LinkedHashMap<>(rec.metadata())
                                        : new LinkedHashMap<>();
                        if (vec != null && vec.length > 0) {
                            meta.put("vector_dim", vec.length);
                        }
                        Document doc =
                                Document.builder().id(id).text(rec.text()).metadata(meta).build();
                        List<String> tokens = tokenize(doc.getText());
                        Map<String, Integer> termFreq = new HashMap<>();
                        for (String token : tokens) {
                            termFreq.merge(token, 1, Integer::sum);
                        }
                        entries.put(id, new IndexedEntry(id, doc, termFreq, tokens.size(), vec));
                    }
                    log.info(
                            "[HybridKnowledgeStore] 已从持久化文件 [{}] 恢复 {} 条知识切片（其中已向量化 {} 条）",
                            persistencePath.toAbsolutePath(),
                            entries.size(),
                            vectorizedCount());
                    if (backfilledAny) {
                        saveToDisk();
                    }
                } catch (Exception ex) {
                    log.warn(
                            "[HybridKnowledgeStore] 读取持久化索引文件 [{}] 失败，将使用空索引启动: {}",
                            persistencePath,
                            ex.getMessage());
                }
            }
        }

        private void saveToDisk() {
            if (persistencePath == null) {
                return;
            }
            synchronized (persistLock) {
                try {
                    Path parent = persistencePath.toAbsolutePath().getParent();
                    if (parent != null && !Files.exists(parent)) {
                        Files.createDirectories(parent);
                    }
                    List<PersistedChunkRecord> snapshot =
                            entries.values().stream()
                                    .sorted(Comparator.comparing(IndexedEntry::id))
                                    .map(e ->
                                            new PersistedChunkRecord(
                                                    e.id(),
                                                    e.document().getText(),
                                                    e.document().getMetadata(),
                                                    e.vector()))
                                    .toList();
                    Path tmpFile =
                            persistencePath.resolveSibling(
                                    persistencePath.getFileName().toString() + ".tmp");
                    PERSISTENCE_MAPPER
                            .writerWithDefaultPrettyPrinter()
                            .writeValue(tmpFile.toFile(), snapshot);
                    Files.move(
                            tmpFile,
                            persistencePath,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException ex) {
                    log.warn(
                            "[HybridKnowledgeStore] 持久化写入知识索引文件 [{}] 失败: {}",
                            persistencePath,
                            ex.getMessage());
                }
            }
        }
    }

    private record IndexedEntry(
            String id,
            Document document,
            Map<String, Integer> termFreq,
            int docLength,
            float[] vector) {}

    public record PersistedChunkRecord(
            String id, String text, Map<String, Object> metadata, float[] vector) {}
}
