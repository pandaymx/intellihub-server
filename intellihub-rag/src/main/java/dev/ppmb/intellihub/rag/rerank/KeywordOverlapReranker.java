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
 * 开箱即用的轻量特征融合精排器（Keyword & Section Overlap Reranker）。
 *
 * <p>在保留初筛 RRF / 向量基础分的同时，叠加以下高置信度精排信号：
 *
 * <ul>
 *   <li><b>查询词元覆盖率（Query Token Coverage）</b>：衡量切片对查询中关键术语的完整覆盖比例；
 *   <li><b>连续短语精确命中（Exact Substring Boost）</b>：当切片正文中完整出现用户核心短语时显著提权；
 *   <li><b>章节标题路径命中（Section Path Boost）</b>：当 {@code section_path} 标题命中查询词时额外提权。
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
                    (normalizedQuery.length() >= 2 && text.contains(normalizedQuery)) ? 0.25 : 0.0;

            double finalScore =
                    candidate.score()
                            + (0.50 * coverageScore)
                            + (0.25 * sectionBoost)
                            + exactPhraseBoost;
            reranked.add(candidate.withRerankScore(finalScore));
        }

        reranked.sort(Comparator.comparingDouble(ScoredDocument::score).reversed());
        return reranked.size() <= topN ? reranked : reranked.subList(0, topN);
    }
}
