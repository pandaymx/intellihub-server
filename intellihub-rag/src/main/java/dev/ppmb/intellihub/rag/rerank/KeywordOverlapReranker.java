package dev.ppmb.intellihub.rag.rerank;

import dev.ppmb.intellihub.rag.retrieval.ScoredDocument;
import dev.ppmb.intellihub.rag.splitter.SemanticMarkdownDocumentSplitter;
import dev.ppmb.intellihub.rag.store.HybridKnowledgeStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 归一化语义保底 + 词法特征融合精排器（Normalized Semantic & Lexical Overlap Reranker）。
 *
 * <p>专门解决 RRF 分数（量纲约 {@code 0.01~0.03}）与字面重合度（量纲 {@code 0.0~1.0}）直接相加导致的量纲失衡问题：
 *
 * <ul>
 *   <li><b>基础分批次内归一化（Batch Min-Max / Max Normalization）</b>：将粗召回分数投射到 {@code [0, 1]} 区间；
 *   <li><b>向量语义锚定（Vector Semantic Anchor）</b>：当切片携带 {@code vectorScore}（余弦相似度）时，以 {@code 0.70 *
 *       vectorScore + 0.30 * normalizedBaseScore} 作为语义主信号（占比 {@code 65%}），确保“字面零重合但向量高相似”的语义切片绝不会被误杀；
 *   <li><b>词元覆盖、章节路径与连续短语增益</b>：在同等语义相关度下，优先提权命中核心术语、标题路径或完整短语的切片（占比 {@code 35%}）。
 * </ul>
 */
public class KeywordOverlapReranker implements DocumentReranker {

    @Override
    public List<ScoredDocument> rerank(String query, List<ScoredDocument> candidates, int topN) {
        if (candidates == null || candidates.isEmpty() || topN <= 0) {
            return List.of();
        }
        if (query == null || query.isBlank()) {
            return candidates.size() <= topN ? candidates : candidates.subList(0, topN);
        }

        Set<String> queryTokens = new LinkedHashSet<>(HybridKnowledgeStore.tokenize(query));
        String normalizedQuery = query.strip().toLowerCase(Locale.ROOT);

        double maxBaseScore =
                candidates.stream()
                        .mapToDouble(ScoredDocument::score)
                        .filter(s -> s > 0.0)
                        .max()
                        .orElse(1.0);

        List<ScoredDocument> reranked = new ArrayList<>(candidates.size());
        for (ScoredDocument candidate : candidates) {
            String text =
                    candidate.document().getText() == null
                            ? ""
                            : candidate.document().getText().toLowerCase(Locale.ROOT);
            Object secObj =
                    candidate
                            .document()
                            .getMetadata()
                            .get(SemanticMarkdownDocumentSplitter.META_SECTION_PATH);
            String sectionPath = secObj == null ? "" : secObj.toString().toLowerCase(Locale.ROOT);

            double coverageScore = 0.0;
            double sectionBoost = 0.0;
            if (!queryTokens.isEmpty()) {
                int matchedInText = 0;
                int matchedInSection = 0;
                for (String token : queryTokens) {
                    if (text.contains(token)) {
                        matchedInText++;
                    }
                    if (!sectionPath.isEmpty() && sectionPath.contains(token)) {
                        matchedInSection++;
                    }
                }
                coverageScore = (double) matchedInText / queryTokens.size();
                sectionBoost = (double) matchedInSection / queryTokens.size();
            }

            double exactPhraseBoost =
                    (normalizedQuery.length() >= 2 && text.contains(normalizedQuery)) ? 0.20 : 0.0;
            double lexicalSignal =
                    Math.min(1.0, 0.55 * coverageScore + 0.25 * sectionBoost + exactPhraseBoost);

            double normalizedBase =
                    maxBaseScore > 0.0
                            ? Math.clamp(candidate.score() / maxBaseScore, 0.0, 1.0)
                            : 0.0;

            double finalScore;
            if (candidate.vectorScore() != null) {
                double vectorSim = Math.clamp(candidate.vectorScore(), 0.0, 1.0);
                double semanticSignal = 0.70 * vectorSim + 0.30 * normalizedBase;
                finalScore = 0.65 * semanticSignal + 0.35 * lexicalSignal;
            } else {
                finalScore = 0.60 * normalizedBase + 0.40 * lexicalSignal;
            }

            reranked.add(candidate.withRerankScore(finalScore));
        }

        reranked.sort(Comparator.comparingDouble(ScoredDocument::score).reversed());
        return reranked.size() <= topN ? reranked : reranked.subList(0, topN);
    }
}
