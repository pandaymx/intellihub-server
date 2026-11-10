package dev.ppmb.intellihub.app.dto.rag;

import java.util.List;

/**
 * RAG 知识问答增强响应 DTO。
 */
public class RagQueryApiResponse {

    /** 模型结合参考切片生成的最终回答 */
    private String answer;

    /** 检索实际使用的查询词 */
    private String query;

    /** 本次回答实际引用的切片（包含编号 [#1]、[#2] 对应关系与分数） */
    private List<ScoredDocumentDto> citations;

    /** 检索与重排阶段耗时（毫秒） */
    private Long retrievalLatencyMs;

    /** 大模型推理生成阶段耗时（毫秒） */
    private Long generationLatencyMs;

    /** 实际命中的模型渠道 */
    private String channel;

    public RagQueryApiResponse() {}

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public List<ScoredDocumentDto> getCitations() {
        return citations;
    }

    public void setCitations(List<ScoredDocumentDto> citations) {
        this.citations = citations;
    }

    public Long getRetrievalLatencyMs() {
        return retrievalLatencyMs;
    }

    public void setRetrievalLatencyMs(Long retrievalLatencyMs) {
        this.retrievalLatencyMs = retrievalLatencyMs;
    }

    public Long getGenerationLatencyMs() {
        return generationLatencyMs;
    }

    public void setGenerationLatencyMs(Long generationLatencyMs) {
        this.generationLatencyMs = generationLatencyMs;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }
}
