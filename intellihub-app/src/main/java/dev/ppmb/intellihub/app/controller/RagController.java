package dev.ppmb.intellihub.app.controller;

import dev.ppmb.intellihub.app.common.ApiResponse;
import dev.ppmb.intellihub.app.dto.rag.DocumentIngestRequest;
import dev.ppmb.intellihub.app.dto.rag.IngestResultResponse;
import dev.ppmb.intellihub.app.dto.rag.RagQueryApiRequest;
import dev.ppmb.intellihub.app.dto.rag.RagQueryApiResponse;
import dev.ppmb.intellihub.app.dto.rag.RagSearchApiRequest;
import dev.ppmb.intellihub.app.dto.rag.ScoredDocumentDto;
import dev.ppmb.intellihub.core.exception.GlobalErrorCodeConstants;
import dev.ppmb.intellihub.core.exception.IntelliHubExceptionUtil;
import dev.ppmb.intellihub.llm.registry.IntelliHubModelRegistry;
import dev.ppmb.intellihub.rag.engine.RagAnswer;
import dev.ppmb.intellihub.rag.engine.RagQueryEngine;
import dev.ppmb.intellihub.rag.ingest.DocumentIngestionPipeline;
import dev.ppmb.intellihub.rag.retrieval.RagSearchRequest;
import dev.ppmb.intellihub.rag.retrieval.ScoredDocument;
import dev.ppmb.intellihub.rag.store.HybridKnowledgeStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RAG 知识库管理、语义切分入库、多模态混合检索与增强问答 REST 控制器。
 */
@RestController
@RequestMapping("/api/v1/rag")
public class RagController {

    private final DocumentIngestionPipeline ingestionPipeline;
    private final HybridKnowledgeStore knowledgeStore;
    private final RagQueryEngine ragQueryEngine;
    private final IntelliHubModelRegistry modelRegistry;

    public RagController(
            DocumentIngestionPipeline ingestionPipeline,
            HybridKnowledgeStore knowledgeStore,
            RagQueryEngine ragQueryEngine,
            IntelliHubModelRegistry modelRegistry) {
        this.ingestionPipeline = ingestionPipeline;
        this.knowledgeStore = knowledgeStore;
        this.ragQueryEngine = ragQueryEngine;
        this.modelRegistry = modelRegistry;
    }

    /**
     * 文档摄取入库：支持 Markdown 语义切块、SHA-256 幂等去重与多租户元数据自动注入。
     */
    @PostMapping("/documents")
    public ApiResponse<IngestResultResponse> ingestDocument(
            @RequestBody DocumentIngestRequest request) {
        if (request == null || request.getContent() == null || request.getContent().isBlank()) {
            throw IntelliHubExceptionUtil.invalidParamException("摄取文档 content 不能为空");
        }

        Map<String, Object> commonMetadata = new LinkedHashMap<>();
        if (request.getTitle() != null && !request.getTitle().isBlank()) {
            commonMetadata.put("title", request.getTitle().strip());
        }
        if (request.getTenantId() != null && !request.getTenantId().isBlank()) {
            commonMetadata.put(
                    DocumentIngestionPipeline.META_TENANT_ID, request.getTenantId().strip());
        }
        if (request.getKbId() != null && !request.getKbId().isBlank()) {
            commonMetadata.put(DocumentIngestionPipeline.META_KB_ID, request.getKbId().strip());
        }
        if (request.getMetadata() != null) {
            commonMetadata.putAll(request.getMetadata());
        }

        Document rawDoc = new Document(request.getContent().strip(), commonMetadata);
        List<Document> chunks = ingestionPipeline.process(List.of(rawDoc), commonMetadata);
        knowledgeStore.addAll(chunks);

        List<String> chunkIds = chunks.stream().map(Document::getId).toList();
        return ApiResponse.success(new IngestResultResponse(chunks.size(), chunkIds));
    }

    /**
     * 混合检索切片：支持向量余弦 + BM25 词法倒排与 RRF（Reciprocal Rank Fusion）融合重排。
     */
    @PostMapping("/search")
    public ApiResponse<List<ScoredDocumentDto>> searchKnowledge(
            @RequestBody RagSearchApiRequest request) {
        if (request == null || request.getQuery() == null || request.getQuery().isBlank()) {
            throw IntelliHubExceptionUtil.exception(
                    GlobalErrorCodeConstants.AI_PROMPT_EMPTY, "检索查询词 query 不能为空");
        }

        RagSearchRequest.Builder builder = RagSearchRequest.builder(request.getQuery().strip());
        if (request.getTopK() != null && request.getTopK() > 0) {
            builder.topK(request.getTopK());
        }
        if (request.getSearchMode() != null) {
            builder.searchMode(request.getSearchMode());
        }
        if (request.getTenantId() != null && !request.getTenantId().isBlank()) {
            builder.filterMetadata(
                    DocumentIngestionPipeline.META_TENANT_ID, request.getTenantId().strip());
        }
        if (request.getKbId() != null && !request.getKbId().isBlank()) {
            builder.filterMetadata(DocumentIngestionPipeline.META_KB_ID, request.getKbId().strip());
        }

        List<ScoredDocument> hits = knowledgeStore.search(builder.build());
        List<ScoredDocumentDto> dtoList = hits.stream().map(ScoredDocumentDto::fromDomain).toList();
        return ApiResponse.success(dtoList);
    }

    /**
     * 增强问答（RAG Query）：检索候选知识切片 -> 重排精筛 -> 上下文预算裁剪 -> 提示词防注入 -> 模型生成。
     */
    @PostMapping("/query")
    public ApiResponse<RagQueryApiResponse> queryRag(@RequestBody RagQueryApiRequest request) {
        if (request == null || request.getQuestion() == null || request.getQuestion().isBlank()) {
            throw IntelliHubExceptionUtil.exception(
                    GlobalErrorCodeConstants.AI_PROMPT_EMPTY, "用户提问 question 不能为空");
        }

        String targetChannel =
                (request.getChannel() != null && !request.getChannel().isBlank())
                        ? request.getChannel().strip()
                        : modelRegistry.getDefaultChannelOrRoute();
        ChatModel chatModel = modelRegistry.getChatModel(targetChannel);

        RagSearchRequest.Builder searchBuilder =
                RagSearchRequest.builder(request.getQuestion().strip());
        if (request.getTopK() != null && request.getTopK() > 0) {
            searchBuilder.topK(request.getTopK());
        }
        if (request.getSearchMode() != null) {
            searchBuilder.searchMode(request.getSearchMode());
        }
        if (request.getTenantId() != null && !request.getTenantId().isBlank()) {
            searchBuilder.filterMetadata(
                    DocumentIngestionPipeline.META_TENANT_ID, request.getTenantId().strip());
        }
        if (request.getKbId() != null && !request.getKbId().isBlank()) {
            searchBuilder.filterMetadata(
                    DocumentIngestionPipeline.META_KB_ID, request.getKbId().strip());
        }

        RagAnswer answer = ragQueryEngine.ask(chatModel, searchBuilder.build());

        RagQueryApiResponse response = new RagQueryApiResponse();
        response.setAnswer(answer.answer());
        response.setQuery(answer.query());
        response.setRetrievalLatencyMs(answer.retrievalLatencyMs());
        response.setGenerationLatencyMs(answer.generationLatencyMs());
        response.setChannel(targetChannel);
        response.setCitations(
                answer.citations().stream().map(ScoredDocumentDto::fromDomain).toList());

        return ApiResponse.success(response);
    }

    /**
     * 删除指定切片（支持路径变量与查询参数两种方式，兼容含 # 的分片 ID）。
     */
    @DeleteMapping("/documents/{id}")
    public ApiResponse<Boolean> deleteDocument(@PathVariable("id") String id) {
        boolean deleted = knowledgeStore.delete(id);
        return ApiResponse.success(deleted);
    }

    /**
     * 按查询参数 id 删除指定切片（兼容包含特殊字符如 #chunk-0 的切片 ID）。
     */
    @DeleteMapping("/documents")
    public ApiResponse<Boolean> deleteDocumentByParam(
            @org.springframework.web.bind.annotation.RequestParam("id") String id) {
        boolean deleted = knowledgeStore.delete(id);
        return ApiResponse.success(deleted);
    }

    /**
     * 查询知识库当前统计数据（总切片数等）。
     */
    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> getKnowledgeStats() {
        return ApiResponse.success(Map.of("totalChunks", knowledgeStore.size()));
    }
}
