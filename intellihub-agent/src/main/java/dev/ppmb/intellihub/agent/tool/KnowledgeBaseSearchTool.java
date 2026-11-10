package dev.ppmb.intellihub.agent.tool;

import dev.ppmb.intellihub.rag.engine.RagQueryEngine;
import dev.ppmb.intellihub.rag.retrieval.RagSearchRequest;
import dev.ppmb.intellihub.rag.retrieval.ScoredDocument;
import dev.ppmb.intellihub.rag.splitter.SemanticMarkdownDocumentSplitter;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 内置的 Agent 知识库主动检索工具（Knowledge Base Search Tool）。
 *
 * <p>将 {@link RagQueryEngine#retrieveAndRerank(RagSearchRequest)} 封装为标准 Spring AI {@link Tool @Tool}，
 * 使 Agent 在多步 ReAct 推理过程中能够自主改写查询词并检索企业知识库。
 */
public class KnowledgeBaseSearchTool {

    public static final String TOOL_NAME = "search_knowledge_base";

    private final RagQueryEngine ragQueryEngine;

    public KnowledgeBaseSearchTool(RagQueryEngine ragQueryEngine) {
        this.ragQueryEngine = Objects.requireNonNull(ragQueryEngine, "ragQueryEngine 不能为空");
    }

    @Tool(
            name = TOOL_NAME,
            description =
                    "Search the enterprise knowledge base using hybrid vector and BM25 retrieval with semantic reranking. Returns the top relevant passages with source and section metadata.")
    public String searchKnowledgeBase(
            @ToolParam(description = "Search query keywords or natural language question")
                    String query,
            @ToolParam(description = "Maximum number of passages to return (1 to 10)")
                    Integer topK) {
        if (query == null || query.isBlank()) {
            return "No query provided.";
        }
        int effectiveTopK = (topK == null || topK <= 0) ? 3 : Math.min(topK, 10);
        List<ScoredDocument> hits =
                ragQueryEngine.retrieveAndRerank(RagSearchRequest.of(query.strip(), effectiveTopK));
        if (hits.isEmpty()) {
            return "No relevant knowledge base passages found for query: " + query;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            ScoredDocument hit = hits.get(i);
            Object source =
                    hit.document()
                            .getMetadata()
                            .getOrDefault(
                                    SemanticMarkdownDocumentSplitter.META_SOURCE_DOC_ID,
                                    hit.document().getId());
            sb.append("[Passage #")
                    .append(i + 1)
                    .append(" | source=")
                    .append(source)
                    .append(" | score=")
                    .append(String.format("%.4f", hit.score()))
                    .append("]\n")
                    .append(hit.document().getText())
                    .append("\n\n");
        }
        return sb.toString().strip();
    }
}
