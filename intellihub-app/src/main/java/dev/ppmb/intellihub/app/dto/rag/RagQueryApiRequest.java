package dev.ppmb.intellihub.app.dto.rag;

import dev.ppmb.intellihub.rag.retrieval.RagSearchMode;

/**
 * RAG 知识问答增强请求 DTO。
 */
public class RagQueryApiRequest {

    /** 用户提问内容 */
    private String question;

    /** 可选召回参考切片上限（默认 5） */
    private Integer topK;

    /** 可选指定模型渠道或高可用路由组 */
    private String channel;

    /** 可选检索模式（默认 HYBRID） */
    private RagSearchMode searchMode;

    /** 可选多租户隔离标识符 */
    private String tenantId;

    /** 可选知识库隔离标识符 */
    private String kbId;

    public RagQueryApiRequest() {}

    public RagQueryApiRequest(String question) {
        this.question = question;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public Integer getTopK() {
        return topK;
    }

    public void setTopK(Integer topK) {
        this.topK = topK;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
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
