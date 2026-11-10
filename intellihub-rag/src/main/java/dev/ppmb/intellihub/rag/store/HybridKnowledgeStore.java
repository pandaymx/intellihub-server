package dev.ppmb.intellihub.rag.store;

import dev.ppmb.intellihub.rag.retrieval.RagSearchMode;
import dev.ppmb.intellihub.rag.retrieval.RagSearchRequest;
import dev.ppmb.intellihub.rag.retrieval.ScoredDocument;
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
 * 混合知识检索与双路下推编排引擎（Hybrid Knowledge Store：Vector Pushdown + Lexical Pushdown + RRF 融合）。
 *
 * <p>架构分层：
 *
 * <ul>
 *   <li><b>可插拔双路下推 SPI（{@link VectorSearchBackend} + {@link LexicalSearchBackend}）</b>：十万/百万级生产规模下，
 *       可直接注入基于 PostgreSQL {@code pgvector}（HNSW 索引）与 {@code pg_search / tsvector}（GIN 倒排索引）或
 *       Elasticsearch 的实现，将元数据过滤、向量最近邻搜索与 BM25 计算 100% 下推到数据库完成；
 *   <li><b>零外部依赖开箱即用默认后端</b>：未注入外部数据库后端时，默认使用内置的线程安全内存双路索引，满足本地研发、单测与中小型知识库极速检索；
 *   <li><b>O(K log K) 轻量级倒数秩融合（RRF）</b>：仅对两路下推返回的 Top-K 候选并集执行 RRF 排序融合，杜绝应用层全表扫描。
 * </ul>
 */
public class HybridKnowledgeStore {

    private static final Logger log = LoggerFactory.getLogger(HybridKnowledgeStore.class);

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

    public HybridKnowledgeStore() {
        this((EmbeddingModel) null);
    }

    public HybridKnowledgeStore(EmbeddingModel embeddingModel) {
        InMemoryHybridIndex sharedMemoryIndex = new InMemoryHybridIndex(embeddingModel);
        this.vectorBackend = sharedMemoryIndex;
        this.lexicalBackend = sharedMemoryIndex;
    }

    public HybridKnowledgeStore(
            VectorSearchBackend vectorBackend, LexicalSearchBackend lexicalBackend) {
        this.vectorBackend = Objects.requireNonNull(vectorBackend, "vectorBackend 不能为空");
        this.lexicalBackend = Objects.requireNonNull(lexicalBackend, "lexicalBackend 不能为空");
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
     * 默认内置的双路内存索引实现（同时实现 {@link VectorSearchBackend} 与 {@link LexicalSearchBackend}）。
     */
    private static final class InMemoryHybridIndex
            implements VectorSearchBackend, LexicalSearchBackend {

        private final EmbeddingModel embeddingModel;
        private final Map<String, IndexedEntry> entries = new ConcurrentHashMap<>();

        private InMemoryHybridIndex(EmbeddingModel embeddingModel) {
            this.embeddingModel = embeddingModel;
        }

        @Override
        public void upsert(Collection<Document> documents) {
            if (documents == null || documents.isEmpty()) {
                return;
            }
            for (Document doc : documents) {
                if (doc == null || doc.getText() == null || doc.getText().isBlank()) {
                    continue;
                }
                String id =
                        doc.getId() != null && !doc.getId().isBlank()
                                ? doc.getId()
                                : UUID.randomUUID().toString();
                Document normalizedDoc =
                        (doc.getId() != null && !doc.getId().isBlank())
                                ? doc
                                : Document.builder()
                                        .id(id)
                                        .text(doc.getText())
                                        .metadata(doc.getMetadata())
                                        .build();
                List<String> tokens = tokenize(normalizedDoc.getText());
                Map<String, Integer> termFreq = new HashMap<>();
                for (String token : tokens) {
                    termFreq.merge(token, 1, Integer::sum);
                }
                float[] vector = computeEmbeddingSafely(normalizedDoc.getText());
                entries.put(
                        id, new IndexedEntry(id, normalizedDoc, termFreq, tokens.size(), vector));
            }
        }

        @Override
        public boolean delete(String documentId) {
            return entries.remove(documentId) != null;
        }

        @Override
        public void clear() {
            entries.clear();
        }

        @Override
        public int size() {
            return entries.size();
        }

        @Override
        public boolean isAvailable() {
            return embeddingModel != null;
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
    }

    private record IndexedEntry(
            String id,
            Document document,
            Map<String, Integer> termFreq,
            int docLength,
            float[] vector) {}
}
