package dev.ppmb.intellihub.rag.engine;

import dev.ppmb.intellihub.core.clean.AiOutputUtil;
import dev.ppmb.intellihub.prompt.render.SafePromptRenderer;
import dev.ppmb.intellihub.prompt.template.PromptTemplateSpec;
import dev.ppmb.intellihub.rag.rerank.DocumentReranker;
import dev.ppmb.intellihub.rag.rerank.KeywordOverlapReranker;
import dev.ppmb.intellihub.rag.retrieval.RagSearchRequest;
import dev.ppmb.intellihub.rag.retrieval.ScoredDocument;
import dev.ppmb.intellihub.rag.splitter.SemanticMarkdownDocumentSplitter;
import dev.ppmb.intellihub.rag.store.HybridKnowledgeStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 企业级 RAG 知识检索增强问答引擎（RAG Query Engine）。
 *
 * <p>完整串联 4 大核心阶段：
 *
 * <ol>
 *   <li><b>自适应宽窗口粗召回（Coarse Recall）</b>：按 {@code Math.max(topK * recallMultiplier,
 *       minRecallCandidates)}（默认 {@code Math.max(topK * 4, 20)}）从 {@link HybridKnowledgeStore}
 *       召回充足候选池，避免因粗筛过窄漏掉第 11~20 名的长尾高相关切片；
 *   <li><b>交叉精排（Reranking）</b>：通过 {@link DocumentReranker}（支持 {@code CrossEncoderHttpReranker}、
 *       {@code LlmListwiseReranker} 或归一化 {@code KeywordOverlapReranker}）重打分并截取 Top-K 高置信度切片；
 *   <li><b>上下文预算裁剪（Context Budget Guard）</b>：严格控制拼装进 Prompt 的总字符数不超过 {@code maxContextChars}，为每个切片标注
 *       {@code [#1]}、{@code [#2]} 引用编号与章节来源；
 *   <li><b>防幻觉约束生成（Grounded Synthesis）</b>：使用 {@link SafePromptRenderer} 渲染防注入提示词，驱动 {@link
 *       ChatModel} 生成带角标引用的回答。
 * </ol>
 */
public class RagQueryEngine {

    public static final int DEFAULT_MAX_CONTEXT_CHARS = 6000;
    public static final int DEFAULT_RECALL_MULTIPLIER = 4;
    public static final int DEFAULT_MIN_RECALL_CANDIDATES = 20;

    public static final String NO_KNOWLEDGE_FALLBACK = "抱歉，当前知识库中未检索到与您问题直接相关的参考资料，无法基于已知事实给出确切回答。";

    private static final PromptTemplateSpec DEFAULT_RAG_PROMPT_SPEC =
            PromptTemplateSpec.builder("intellihub-rag-grounded-qa")
                    .version("v1")
                    .systemTemplate(
                            "你是一个严谨的企业级知识库助手。请严格依据下方提供的【参考知识库切片】回答用户问题。\n"
                                    + "规则：\n"
                                    + "1. 在陈述关键结论时，请在句末使用角标（如 [#1]、[#2]）标明引用的知识切片编号；\n"
                                    + "2. 如果【参考知识库切片】中不包含回答该问题所需的信息，请明确说明依据现有资料无法回答，严禁编造事实。")
                    .userTemplate("【参考知识库切片】\n{context}\n\n【用户问题】\n{question}")
                    .wrapUserVariablesInXmlTag(true)
                    .build();

    private final HybridKnowledgeStore knowledgeStore;
    private final DocumentReranker reranker;
    private final SafePromptRenderer promptRenderer;
    private final int maxContextChars;
    private final int recallMultiplier;
    private final int minRecallCandidates;

    public RagQueryEngine(HybridKnowledgeStore knowledgeStore) {
        this(
                knowledgeStore,
                new KeywordOverlapReranker(),
                new SafePromptRenderer(),
                DEFAULT_MAX_CONTEXT_CHARS,
                DEFAULT_RECALL_MULTIPLIER,
                DEFAULT_MIN_RECALL_CANDIDATES);
    }

    public RagQueryEngine(
            HybridKnowledgeStore knowledgeStore,
            DocumentReranker reranker,
            SafePromptRenderer promptRenderer,
            int maxContextChars) {
        this(
                knowledgeStore,
                reranker,
                promptRenderer,
                maxContextChars,
                DEFAULT_RECALL_MULTIPLIER,
                DEFAULT_MIN_RECALL_CANDIDATES);
    }

    public RagQueryEngine(
            HybridKnowledgeStore knowledgeStore,
            DocumentReranker reranker,
            SafePromptRenderer promptRenderer,
            int maxContextChars,
            int recallMultiplier,
            int minRecallCandidates) {
        this.knowledgeStore = Objects.requireNonNull(knowledgeStore, "knowledgeStore 不能为空");
        this.reranker = Objects.requireNonNull(reranker, "reranker 不能为空");
        this.promptRenderer = Objects.requireNonNull(promptRenderer, "promptRenderer 不能为空");
        if (maxContextChars < 256) {
            throw new IllegalArgumentException("maxContextChars 不能小于 256");
        }
        if (recallMultiplier < 1) {
            throw new IllegalArgumentException("recallMultiplier 不能小于 1");
        }
        if (minRecallCandidates < 1) {
            throw new IllegalArgumentException("minRecallCandidates 不能小于 1");
        }
        this.maxContextChars = maxContextChars;
        this.recallMultiplier = recallMultiplier;
        this.minRecallCandidates = minRecallCandidates;
    }

    /**
     * 计算粗召回阶段（Stage 1）的目标候选池大小：{@code Math.max(topK * recallMultiplier, minRecallCandidates)}。
     */
    public int computeCoarseRecallLimit(int topK) {
        return Math.max(Math.max(1, topK) * recallMultiplier, minRecallCandidates);
    }

    /**
     * 使用默认检索参数执行知识库问答。
     */
    public RagAnswer ask(ChatModel chatModel, String query) {
        return ask(chatModel, RagSearchRequest.of(query));
    }

    /**
     * 使用自定义 {@link RagSearchRequest}（支持租户过滤、Top-K、检索模式等）执行知识库问答。
     */
    public RagAnswer ask(ChatModel chatModel, RagSearchRequest request) {
        Objects.requireNonNull(chatModel, "chatModel 不能为空");
        Objects.requireNonNull(request, "request 不能为空");

        long t0 = System.currentTimeMillis();
        // 1. 宽窗口粗召回 + 2. Reranker 精排
        List<ScoredDocument> reranked = retrieveAndRerank(request);

        // 3. 上下文窗口预算裁剪与格式化拼装
        List<ScoredDocument> packedCitations = new ArrayList<>();
        String formattedContext = buildBudgetedContext(reranked, packedCitations);
        long retrievalMs = System.currentTimeMillis() - t0;

        if (packedCitations.isEmpty()) {
            return new RagAnswer(
                    NO_KNOWLEDGE_FALLBACK, List.of(), request.query(), retrievalMs, 0L);
        }

        // 4. 渲染防注入 RAG Prompt 并调用 ChatModel
        long t1 = System.currentTimeMillis();
        Prompt prompt =
                promptRenderer.render(
                        DEFAULT_RAG_PROMPT_SPEC,
                        Map.of("context", formattedContext, "question", request.query()));
        ChatResponse response = chatModel.call(prompt);
        String rawAnswer = extractText(response);
        String cleanAnswer = AiOutputUtil.stripThink(rawAnswer);
        long generationMs = System.currentTimeMillis() - t1;

        return new RagAnswer(
                cleanAnswer, packedCitations, request.query(), retrievalMs, generationMs);
    }

    /**
     * 仅执行「宽窗口混合粗召回 + Rerank 精排」而不调用大模型生成（适用于 Agent 工具调用或搜索预览）。
     */
    public List<ScoredDocument> retrieveAndRerank(RagSearchRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        int coarseLimit = computeCoarseRecallLimit(request.topK());
        RagSearchRequest recallRequest =
                RagSearchRequest.builder(request.query())
                        .topK(coarseLimit)
                        .minScore(request.minScore())
                        .searchMode(request.searchMode())
                        .metadataFilters(request.metadataFilters())
                        .vectorWeight(request.vectorWeight())
                        .bm25Weight(request.bm25Weight())
                        .rrfK(request.rrfK())
                        .build();
        List<ScoredDocument> candidates = knowledgeStore.search(recallRequest);
        return reranker.rerank(request.query(), candidates, request.topK());
    }

    private String buildBudgetedContext(
            List<ScoredDocument> reranked, List<ScoredDocument> packedCitationsOut) {
        StringBuilder sb = new StringBuilder();
        int citationIndex = 1;
        for (ScoredDocument scored : reranked) {
            String docText =
                    scored.document().getText() == null ? "" : scored.document().getText().strip();
            if (docText.isEmpty()) {
                continue;
            }
            Map<String, Object> meta = scored.document().getMetadata();
            Object sourceId =
                    meta.getOrDefault(
                            SemanticMarkdownDocumentSplitter.META_SOURCE_DOC_ID,
                            scored.document().getId());
            Object section =
                    meta.getOrDefault(SemanticMarkdownDocumentSplitter.META_SECTION_PATH, "");

            String header =
                    "[#"
                            + citationIndex
                            + " | source="
                            + sourceId
                            + (section != null && !section.toString().isBlank()
                                    ? " | section=" + section
                                    : "")
                            + "]\n";
            String block = header + docText + "\n\n";

            if (!sb.isEmpty() && sb.length() + block.length() > maxContextChars) {
                break;
            }
            if (sb.isEmpty() && block.length() > maxContextChars) {
                sb.append(block, 0, maxContextChars);
                packedCitationsOut.add(scored);
                break;
            }
            sb.append(block);
            packedCitationsOut.add(scored);
            citationIndex++;
        }
        return sb.toString().strip();
    }

    private static String extractText(ChatResponse response) {
        if (response == null
                || response.getResult() == null
                || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null) {
            return "";
        }
        return response.getResult().getOutput().getText();
    }

    public int getMaxContextChars() {
        return maxContextChars;
    }

    public int getRecallMultiplier() {
        return recallMultiplier;
    }

    public int getMinRecallCandidates() {
        return minRecallCandidates;
    }
}
