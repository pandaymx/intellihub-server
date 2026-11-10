package dev.ppmb.intellihub.rag.retrieval;

import java.util.Objects;
import org.springframework.ai.document.Document;

/**
 * 携带检索与重排得分明细的文档切片封装。
 *
 * @param document 原始 Spring AI {@link Document}
 * @param score 当前生效的综合得分（RRF 融合分、单路召回分或重排后得分）
 * @param vectorScore 稠密向量余弦相似度得分（未启用向量检索时为 {@code null}）
 * @param bm25Score BM25 词法匹配原始得分（未启用词法检索时为 {@code null}）
 * @param rerankScore 重排序（Reranker）得分（未经过重排时为 {@code null}）
 */
public record ScoredDocument(
        Document document, double score, Double vectorScore, Double bm25Score, Double rerankScore) {

    public ScoredDocument {
        Objects.requireNonNull(document, "document 不能为空");
    }

    public ScoredDocument withRerankScore(double newRerankScore) {
        return new ScoredDocument(document, newRerankScore, vectorScore, bm25Score, newRerankScore);
    }
}
