package dev.ppmb.intellihub.rag.engine;

import dev.ppmb.intellihub.rag.retrieval.ScoredDocument;
import java.util.List;

/**
 * RAG 知识问答完整返回结果（包含生成回答、命中的引用源切片及阶段耗时指标）。
 *
 * @param answer 模型基于检索上下文生成的最终回答（已剥离 {@code <think>} 推理过程）
 * @param citations 本次回答实际注入 Prompt 上下文的引用文档切片列表（按相关性降序，对应 {@code [#1]}, {@code [#2]}）
 * @param query 实际执行的检索查询词
 * @param retrievalLatencyMs 检索与重排阶段耗时（毫秒）
 * @param generationLatencyMs 大模型生成阶段耗时（毫秒）
 */
public record RagAnswer(
        String answer,
        List<ScoredDocument> citations,
        String query,
        long retrievalLatencyMs,
        long generationLatencyMs) {

    public RagAnswer {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    public boolean hasCitations() {
        return !citations.isEmpty();
    }
}
