package dev.ppmb.intellihub.rag.rerank;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.ppmb.intellihub.prompt.structured.StructuredOutputExecutor;
import dev.ppmb.intellihub.prompt.template.PromptTemplateSpec;
import dev.ppmb.intellihub.rag.retrieval.ScoredDocument;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;

/**
 * 基于大语言模型 Listwise 批量语义评估的精排器（LLM Listwise Semantic Reranker）。
 *
 * <p>当团队未单独部署 BGE-Reranker 等专用 Cross-Encoder 模型时，可直接复用快速 {@link ChatModel} 与 {@link
 * StructuredOutputExecutor}，在单次推理中对全部粗召回切片进行深度语义相关性打分（{@code 0.0 ~ 1.0}）。
 *
 * <p>若大模型调用超时或解析失败，自动平滑回退至 {@link #fallbackReranker}。
 */
public class LlmListwiseReranker implements DocumentReranker {

    private static final Logger log = LoggerFactory.getLogger(LlmListwiseReranker.class);

    public static final int DEFAULT_MAX_SNIPPET_CHARS = 400;

    private static final PromptTemplateSpec LISTWISE_RERANK_SPEC =
            PromptTemplateSpec.builder("intellihub-rag-llm-rerank")
                    .version("v1")
                    .systemTemplate(
                            "You are a semantic relevance reranker. Evaluate how relevant each candidate passage is to answering the user query, focusing on deep semantic meaning rather than surface keyword overlap. Assign each passage a relevanceScore between 0.0 (irrelevant) and 1.0 (directly answers the query).")
                    .userTemplate("Query:\n{query}\n\nCandidate Passages:\n{passages}")
                    .wrapUserVariablesInXmlTag(true)
                    .build();

    private final ChatModel chatModel;
    private final StructuredOutputExecutor structuredOutputExecutor;
    private final DocumentReranker fallbackReranker;
    private final int maxSnippetChars;

    public LlmListwiseReranker(ChatModel chatModel) {
        this(
                chatModel,
                new StructuredOutputExecutor(),
                new KeywordOverlapReranker(),
                DEFAULT_MAX_SNIPPET_CHARS);
    }

    public LlmListwiseReranker(
            ChatModel chatModel,
            StructuredOutputExecutor structuredOutputExecutor,
            DocumentReranker fallbackReranker,
            int maxSnippetChars) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel 不能为空");
        this.structuredOutputExecutor =
                Objects.requireNonNull(structuredOutputExecutor, "structuredOutputExecutor 不能为空");
        this.fallbackReranker =
                fallbackReranker != null ? fallbackReranker : new KeywordOverlapReranker();
        this.maxSnippetChars = Math.max(64, maxSnippetChars);
    }

    @Override
    public List<ScoredDocument> rerank(String query, List<ScoredDocument> candidates, int topN) {
        if (candidates == null || candidates.isEmpty() || topN <= 0) {
            return List.of();
        }
        if (query == null || query.isBlank()) {
            return candidates.size() <= topN ? candidates : candidates.subList(0, topN);
        }

        StringBuilder passagesBuilder = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            String raw = candidates.get(i).document().getText();
            String snippet = raw == null ? "" : raw.strip();
            if (snippet.length() > maxSnippetChars) {
                snippet = snippet.substring(0, maxSnippetChars) + "...";
            }
            passagesBuilder.append('[').append(i).append("] ").append(snippet).append("\n\n");
        }

        try {
            ListwiseRerankEvaluation eval =
                    structuredOutputExecutor.execute(
                            chatModel,
                            LISTWISE_RERANK_SPEC,
                            Map.of(
                                    "query", query.strip(),
                                    "passages", passagesBuilder.toString().strip()),
                            ListwiseRerankEvaluation.class);

            if (eval == null || eval.items() == null || eval.items().isEmpty()) {
                return fallbackReranker.rerank(query, candidates, topN);
            }

            Map<Integer, Double> scoreByIndex = new HashMap<>();
            for (PassageScore item : eval.items()) {
                if (item != null && item.index() >= 0 && item.index() < candidates.size()) {
                    scoreByIndex.put(item.index(), Math.clamp(item.relevanceScore(), 0.0, 1.0));
                }
            }
            if (scoreByIndex.isEmpty()) {
                return fallbackReranker.rerank(query, candidates, topN);
            }

            List<ScoredDocument> reranked = new ArrayList<>(candidates.size());
            for (int i = 0; i < candidates.size(); i++) {
                Double llmScore = scoreByIndex.get(i);
                if (llmScore != null) {
                    reranked.add(candidates.get(i).withRerankScore(llmScore));
                }
            }
            reranked.sort(Comparator.comparingDouble(ScoredDocument::score).reversed());
            return reranked.size() <= topN ? reranked : reranked.subList(0, topN);
        } catch (RuntimeException ex) {
            log.warn("[LlmListwiseReranker] LLM 语义重排失败，自动降级至兜底精排器: {}", ex.getMessage());
            return fallbackReranker.rerank(query, candidates, topN);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ListwiseRerankEvaluation(List<PassageScore> items) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PassageScore(int index, double relevanceScore) {}
}
