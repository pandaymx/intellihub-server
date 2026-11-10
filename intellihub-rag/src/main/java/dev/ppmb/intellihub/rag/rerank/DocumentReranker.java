package dev.ppmb.intellihub.rag.rerank;

import dev.ppmb.intellihub.rag.retrieval.ScoredDocument;
import java.util.List;

/**
 * 知识库粗排候选集精排（Reranker）SPI 契约。
 *
 * <p>在 {@code HybridKnowledgeStore} 粗召回（如 Top-20）后，对候选切片与用户 {@code query} 进行交叉重打分并截取最终 {@code
 * topN}。可对接本地特征重排器（{@link KeywordOverlapReranker}）或远端 Cross-Encoder 模型（如 BGE-Reranker、Cohere
 * Rerank、Jina Reranker）。
 */
@FunctionalInterface
public interface DocumentReranker {

    /**
     * 对候选切片列表执行重排序并截取前 {@code topN} 条。
     *
     * @param query 用户查询词
     * @param candidates 初筛召回的候选文档切片
     * @param topN 精排后保留的最大条数
     * @return 重排后的文档切片列表（按 {@link ScoredDocument#score()} 降序）
     */
    List<ScoredDocument> rerank(String query, List<ScoredDocument> candidates, int topN);
}
