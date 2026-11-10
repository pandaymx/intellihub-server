package dev.ppmb.intellihub.rag.retrieval;

/**
 * 知识库检索模式枚举。
 */
public enum RagSearchMode {

    /** 混合检索（Dense 向量余弦相似度 + BM25 稀疏词法检索 + RRF 倒数秩融合，推荐默认） */
    HYBRID,

    /** 仅使用稠密向量语义检索（需提供 {@code EmbeddingModel}） */
    VECTOR_ONLY,

    /** 仅使用 BM25 词法全文检索（支持中英文分词，无需外部 Embedding 模型即可开箱即用） */
    BM25_ONLY
}
