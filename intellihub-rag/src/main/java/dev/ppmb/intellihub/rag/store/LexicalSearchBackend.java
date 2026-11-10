package dev.ppmb.intellihub.rag.store;

import dev.ppmb.intellihub.rag.retrieval.ScoredDocument;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;

/**
 * 词法全文检索（BM25 / 倒排索引）下推后端 SPI（Lexical Search Pushdown Backend）。
 *
 * <p>用于将多租户元数据过滤与 BM25 词法打分下推至外部全文检索引擎（如 PostgreSQL {@code pg_search} / {@code tsvector} GIN
 * 倒排索引、Elasticsearch、OpenSearch），仅向应用层返回 Top-K 词法命中结果。
 */
public interface LexicalSearchBackend {

    /**
     * 批量写入或更新文档切片至倒排索引。
     */
    void upsert(Collection<Document> documents);

    /**
     * 按文档 ID 删除词法索引记录。
     */
    boolean delete(String documentId);

    /**
     * 清空词法索引。
     */
    void clear();

    /**
     * 当前已索引的文档切片总数。
     */
    int size();

    /**
     * 下推执行元数据过滤 + BM25 词法相关性 Top-K 检索。
     *
     * @param query 用户查询词
     * @param topK 单路最大召回条数
     * @param metadataFilters 多租户/知识库等前置过滤条件
     * @return 按 BM25 得分降序排列的候选文档列表（需填充 {@link ScoredDocument#bm25Score()}）
     */
    List<ScoredDocument> searchLexical(String query, int topK, Map<String, Object> metadataFilters);
}
