package dev.ppmb.intellihub.app.dto.rag;

import dev.ppmb.intellihub.rag.retrieval.RagSearchMode;

/**
 * 知识库检索请求 DTO。
 */
public class RagSearchApiRequest {

    /** 检索提问词或搜索关键字 */
    private String query;

    /** 召回切片数量（默认 5） */
    private Integer topK;

    /** 检索模式（HYBRID、VECTOR_ONLY、BM25_ONLY，默认 HYBRID） */
    private RagSearchMode searchMode;

    /** 可选多租户隔离标识符过滤 */
    private String tenantId;

    /** 可选知识库隔离标识符过滤 */
    private String kbId;

    public RagSearchApiRequest() {}

    public RagSearchApiRequest(String query) {
        this.query = query;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public Integer getTopK() {
        return topK;
    }

    public void setTopK(Integer topK) {
        this.topK = topK;
    }

    public RagSearchMode getSearchMode() {
        return searchMode;
    }

    public void setSearchMode(RagSearchMode searchMode) {
        this.searchMode = searchMode;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getKbId() {
        return kbId;
    }

    public void setKbId(String kbId) {
        this.kbId = kbId;
    }
}
