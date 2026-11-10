package dev.ppmb.intellihub.rag.store;

import dev.ppmb.intellihub.rag.retrieval.ScoredDocument;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;

/**
 * 稠密向量检索下推后端 SPI（Vector Search Pushdown Backend）。
 *
 * <p>用于将元数据过滤（{@code WHERE metadata @> ...}）与近似最近邻向量检索（如 PostgreSQL {@code pgvector} HNSW 索引、
 * Milvus、Qdrant、Spring AI {@code VectorStore}）完全下推至外部向量数据库执行，仅向 JVM 返回 Top-K 召回结果，突破十万/百万级切片下的
 * JVM 内存与全表扫描瓶颈。
 */
public interface VectorSearchBackend {

    /**
     * 批量写入或更新文档切片（外部实现可在此完成 Embedding 计算并写入向量表）。
     */
    void upsert(Collection<Document> documents);

    /**
     * 按文档 ID 删除向量记录。
     */
    boolean delete(String documentId);

    /**
     * 清空向量索引。
     */
    void clear();

    /**
     * 当前向量检索后端是否已就绪可用（例如已配置 {@code EmbeddingModel} 或向量库连接正常）。
     */
    boolean isAvailable();

    /**
     * 下推执行元数据过滤 + 向量相似度 Top-K 检索。
     *
     * @param query 用户查询词
     * @param topK 单路最大召回条数
     * @param metadataFilters 多租户/知识库等前置过滤条件
     * @return 按向量余弦相似度降序排列的候选文档列表（需填充 {@link ScoredDocument#vectorScore()}）
     */
    List<ScoredDocument> searchSimilar(String query, int topK, Map<String, Object> metadataFilters);
}
