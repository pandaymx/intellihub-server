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
 * 混合知识检索存储引擎（Hybrid Knowledge Store：Dense Vector + BM25 + RRF 倒数秩融合）。
 *
 * <p>核心工程特性：
 *
 * <ul>
 *   <li><b>多租户元数据强隔离（Pre-Filtering）</b>：在计算向量距离与 BM25 前优先过滤 {@code metadataFilters}（如 {@code
 *       tenant_id}、{@code kb_id}），杜绝跨租户知识越权召回；
 *   <li><b>中英文混合 BM25 词法引擎</b>：内置英文词元提取 + 中文单字/双字（Unigram + Bigram）切分，精确召回错误码、类名与专有名词；
 *   <li><b>稠密向量余弦相似度（Dense Cosine Similarity）</b>：当配置了 {@link EmbeddingModel} 时自动生成向量并计算余弦相似度；若未配置或远端
 *       Embedding 瞬时故障，自动平滑降级至 BM25 检索；
 *   <li><b>倒数秩融合（Reciprocal Rank Fusion, RRF）</b>：免归一化融合向量召回序与词法召回序，兼顾语义泛化与精确关键词匹配。
 * </ul>
 */
public class HybridKnowledgeStore {

    private static final Logger log = LoggerFactory.getLogger(HybridKnowledgeStore.class);

    private static final double BM25_K1 = 1.5;
    private static final double BM25_B = 0.75;

    /** 匹配连续的 ASCII 字母数字标识符（含下划线、点、连字符，如错误码或类名） */
    private static final Pattern ASCII_TOKEN_PATTERN =
            Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.-]*");

    /** 匹配连续的中文字符序列 */
    private static final Pattern CJK_SEQUENCE_PATTERN = Pattern.compile("[\\u4e00-\\u9fa5]+");

    private final EmbeddingModel embeddingModel;
    private final Map<String, IndexedEntry> entries = new ConcurrentHashMap<>();

    public HybridKnowledgeStore() {
        this(null);
    }

    public HybridKnowledgeStore(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
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
     * 批量写入文档切片并建立 BM25 倒排词频与可选的稠密向量索引。
     */
    public void addAll(Collection<Document> documents) {
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
            List<String> tokens = tokenize(doc.getText());
            Map<String, Integer> termFreq = new HashMap<>();
            for (String token : tokens) {
                termFreq.merge(token, 1, Integer::sum);
            }
            float[] vector = computeEmbeddingSafely(doc.getText());
            entries.put(id, new IndexedEntry(id, doc, termFreq, tokens.size(), vector));
        }
    }

    /**
     * 按文档 ID 删除切片。
     */
    public boolean delete(String documentId) {
        if (documentId == null || documentId.isBlank()) {
            return false;
        }
        return entries.remove(documentId) != null;
    }

    /**
     * 清空存储中的所有文档切片。
     */
    public void clear() {
        entries.clear();
    }

    /**
     * 当前已索引的切片总数。
     */
    public int size() {
        return entries.size();
    }

    /**
     * 执行知识检索（支持 {@code HYBRID}、{@code VECTOR_ONLY}、{@code BM25_ONLY}）。
     */
    public List<ScoredDocument> search(RagSearchRequest request) {
        Objects.requireNonNull(request, "RagSearchRequest 不能为空");
        if (entries.isEmpty()) {
            return List.of();
        }

        // 1. 元数据前置强过滤（Pre-Filtering）
        List<IndexedEntry> candidates = filterByMetadata(request.metadataFilters());
        if (candidates.isEmpty()) {
            return List.of();
        }

        RagSearchMode mode = request.searchMode();
        float[] queryVector = null;
        if (mode == RagSearchMode.HYBRID || mode == RagSearchMode.VECTOR_ONLY) {
            queryVector = computeEmbeddingSafely(request.query());
            if (queryVector == null && mode == RagSearchMode.VECTOR_ONLY) {
                log.warn("[HybridKnowledgeStore] 请求 VECTOR_ONLY 但未配置可用 EmbeddingModel，自动降级为 BM25");
                mode = RagSearchMode.BM25_ONLY;
            } else if (queryVector == null) {
                mode = RagSearchMode.BM25_ONLY;
            }
        }

        // 2. 计算 BM25 得分与排名
        Map<String, Double> bm25Scores =
                (mode == RagSearchMode.BM25_ONLY || mode == RagSearchMode.HYBRID)
                        ? scoreBm25(request.query(), candidates)
                        : Map.of();

        // 3. 计算稠密向量余弦相似度与排名
        Map<String, Double> vectorScores =
                (queryVector != null
                                && (mode == RagSearchMode.VECTOR_ONLY
                                        || mode == RagSearchMode.HYBRID))
                        ? scoreVector(queryVector, candidates)
                        : Map.of();

        // 4. 单路或 RRF 倒数秩融合排序
        List<ScoredDocument> ranked;
        if (mode == RagSearchMode.BM25_ONLY) {
            ranked = buildSingleModeResult(candidates, bm25Scores, true, request.minScore());
        } else if (mode == RagSearchMode.VECTOR_ONLY) {
            ranked = buildSingleModeResult(candidates, vectorScores, false, request.minScore());
        } else {
            ranked =
                    fuseWithRrf(
                            candidates,
                            vectorScores,
                            bm25Scores,
                            request.vectorWeight(),
                            request.bm25Weight(),
                            request.rrfK(),
                            request.minScore());
        }

        return ranked.size() <= request.topK() ? ranked : ranked.subList(0, request.topK());
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

    private Map<String, Double> scoreBm25(String query, List<IndexedEntry> candidates) {
        List<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty() || candidates.isEmpty()) {
            return Map.of();
        }

        int docCount = candidates.size();
        double avgDocLen =
                candidates.stream().mapToInt(IndexedEntry::docLength).average().orElse(1.0);
        if (avgDocLen <= 0.0) {
            avgDocLen = 1.0;
        }

        // 统计每个查询词在候选集中的文档频率 df
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

        Map<String, Double> scores = new LinkedHashMap<>();
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
                                                                * (entry.docLength() / avgDocLen)));
                score += idf * normTf;
            }
            if (score > 0.0) {
                scores.put(entry.id(), score);
            }
        }
        return scores;
    }

    private Map<String, Double> scoreVector(float[] queryVector, List<IndexedEntry> candidates) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (IndexedEntry entry : candidates) {
            if (entry.vector() == null || entry.vector().length != queryVector.length) {
                continue;
            }
            double sim = cosineSimilarity(queryVector, entry.vector());
            if (sim > 0.0) {
                scores.put(entry.id(), sim);
            }
        }
        return scores;
    }

    private static List<ScoredDocument> buildSingleModeResult(
            List<IndexedEntry> candidates,
            Map<String, Double> scoreMap,
            boolean isBm25,
            double minScore) {
        List<ScoredDocument> results = new ArrayList<>();
        for (IndexedEntry entry : candidates) {
            Double s = scoreMap.get(entry.id());
            if (s != null && s >= minScore) {
                results.add(
                        new ScoredDocument(
                                entry.document(), s, isBm25 ? null : s, isBm25 ? s : null, null));
            }
        }
        results.sort(Comparator.comparingDouble(ScoredDocument::score).reversed());
        return results;
    }

    private static List<ScoredDocument> fuseWithRrf(
            List<IndexedEntry> candidates,
            Map<String, Double> vectorScores,
            Map<String, Double> bm25Scores,
            double vectorWeight,
            double bm25Weight,
            int rrfK,
            double minScore) {
        Map<String, Integer> vectorRanks = computeRankMap(vectorScores);
        Map<String, Integer> bm25Ranks = computeRankMap(bm25Scores);

        List<ScoredDocument> fused = new ArrayList<>();
        for (IndexedEntry entry : candidates) {
            Integer vRank = vectorRanks.get(entry.id());
            Integer bRank = bm25Ranks.get(entry.id());
            if (vRank == null && bRank == null) {
                continue;
            }
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
                                entry.document(),
                                rrfScore,
                                vectorScores.get(entry.id()),
                                bm25Scores.get(entry.id()),
                                null));
            }
        }
        fused.sort(Comparator.comparingDouble(ScoredDocument::score).reversed());
        return fused;
    }

    private static Map<String, Integer> computeRankMap(Map<String, Double> scores) {
        if (scores.isEmpty()) {
            return Map.of();
        }
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(scores.entrySet());
        sorted.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        Map<String, Integer> ranks = new HashMap<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            ranks.put(sorted.get(i).getKey(), i + 1);
        }
        return ranks;
    }

    private float[] computeEmbeddingSafely(String text) {
        if (embeddingModel == null || text == null || text.isBlank()) {
            return null;
        }
        try {
            return embeddingModel.embed(text);
        } catch (RuntimeException ex) {
            log.warn("[HybridKnowledgeStore] 生成 Embedding 向量失败，已自动降级为词法检索: {}", ex.getMessage());
            return null;
        }
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

    private record IndexedEntry(
            String id,
            Document document,
            Map<String, Integer> termFreq,
            int docLength,
            float[] vector) {}
}
