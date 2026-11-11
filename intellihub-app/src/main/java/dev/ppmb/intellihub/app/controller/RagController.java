package dev.ppmb.intellihub.app.controller;

import dev.ppmb.intellihub.app.common.ApiResponse;
import dev.ppmb.intellihub.app.dto.rag.BatchFileIngestResponse;
import dev.ppmb.intellihub.app.dto.rag.DocumentIngestRequest;
import dev.ppmb.intellihub.app.dto.rag.FileParseResultDto;
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
import dev.ppmb.intellihub.rag.parser.ParsedDocument;
import dev.ppmb.intellihub.rag.retrieval.RagSearchRequest;
import dev.ppmb.intellihub.rag.retrieval.ScoredDocument;
import dev.ppmb.intellihub.rag.store.HybridKnowledgeStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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
     * 查询当前受支持的多格式文件解析扩展名列表。
     */
    @GetMapping("/supported-formats")
    public ApiResponse<List<String>> getSupportedFormats() {
        return ApiResponse.success(ingestionPipeline.getParserRegistry().getSupportedExtensions());
    }

    /**
     * 多格式文件仅解析预览接口（Multipart 上传：支持 PDF、Word(.docx)、Excel(.xlsx)、PPT(.pptx)、CSV/TSV、HTML、JSON/YAML、源码与
     * Markdown/TXT，将其转化为结构化 Markdown 返回，不直接写入知识库）。
     */
    @PostMapping(value = "/documents/parse", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<List<FileParseResultDto>> parseUploadedFiles(
            @RequestParam(value = "files", required = false) List<MultipartFile> files,
            @RequestParam(value = "file", required = false) MultipartFile singleFile) {
        List<MultipartFile> resolvedFiles = collectMultipartFiles(files, singleFile);
        List<FileParseResultDto> results = new ArrayList<>(resolvedFiles.size());
        for (MultipartFile mf : resolvedFiles) {
            byte[] bytes = readMultipartBytes(mf);
            String fileName =
                    mf.getOriginalFilename() != null ? mf.getOriginalFilename() : mf.getName();
            ParsedDocument parsed =
                    ingestionPipeline.parseFile(fileName, mf.getContentType(), bytes);
            String mimeType = resolveMultimodalMimeType(parsed, mf.getContentType());
            String base64 =
                    (mimeType != null && bytes.length <= 20 * 1024 * 1024)
                            ? java.util.Base64.getEncoder().encodeToString(bytes)
                            : null;
            results.add(FileParseResultDto.fromParsed(parsed, 0, List.of(), mimeType, base64));
        }
        return ApiResponse.success(results);
    }

    /**
     * 多格式文件批量上传、自动解析、语义切分与向量化入库一站式接口（Multipart 上传）。
     */
    @PostMapping(value = "/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<BatchFileIngestResponse> uploadAndIngestFiles(
            @RequestParam(value = "files", required = false) List<MultipartFile> files,
            @RequestParam(value = "file", required = false) MultipartFile singleFile,
            @RequestParam(value = "title", required = false) String customTitle,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "kbId", required = false) String kbId) {
        List<MultipartFile> resolvedFiles = collectMultipartFiles(files, singleFile);

        Map<String, Object> baseMeta = new LinkedHashMap<>();
        if (tenantId != null && !tenantId.isBlank()) {
            baseMeta.put(DocumentIngestionPipeline.META_TENANT_ID, tenantId.strip());
        } else {
            baseMeta.put(DocumentIngestionPipeline.META_TENANT_ID, "default");
        }
        if (kbId != null && !kbId.isBlank()) {
            baseMeta.put(DocumentIngestionPipeline.META_KB_ID, kbId.strip());
        } else {
            baseMeta.put(DocumentIngestionPipeline.META_KB_ID, "builtin");
        }

        List<FileParseResultDto> fileResults = new ArrayList<>(resolvedFiles.size());
        List<String> allChunkIds = new ArrayList<>();

        for (MultipartFile mf : resolvedFiles) {
            byte[] bytes = readMultipartBytes(mf);
            String fileName =
                    mf.getOriginalFilename() != null ? mf.getOriginalFilename() : mf.getName();
            Map<String, Object> fileMeta = new LinkedHashMap<>(baseMeta);
            if (resolvedFiles.size() == 1 && customTitle != null && !customTitle.isBlank()) {
                fileMeta.put("title", customTitle.strip());
            }

            DocumentIngestionPipeline.FileIngestionResult ingestResult =
                    ingestionPipeline.ingestFile(
                            fileName,
                            mf.getContentType(),
                            bytes,
                            fileMeta,
                            chunks -> {
                                if (!ingestResultIsPureImage(fileName, mf.getContentType())) {
                                    knowledgeStore.addAll(chunks);
                                }
                            });

            ParsedDocument parsed = ingestResult.parsedDocument();
            boolean isImage = parsed.fileFormat().startsWith("IMAGE:");
            List<String> chunkIds =
                    isImage
                            ? List.of()
                            : ingestResult.chunks().stream().map(Document::getId).toList();
            allChunkIds.addAll(chunkIds);
            String mimeType = resolveMultimodalMimeType(parsed, mf.getContentType());
            String base64 =
                    (mimeType != null && bytes.length <= 20 * 1024 * 1024)
                            ? java.util.Base64.getEncoder().encodeToString(bytes)
                            : null;
            fileResults.add(
                    FileParseResultDto.fromParsed(
                            parsed, chunkIds.size(), chunkIds, mimeType, base64));
        }

        return ApiResponse.success(
                new BatchFileIngestResponse(
                        fileResults.size(), allChunkIds.size(), allChunkIds, fileResults));
    }

    private static boolean ingestResultIsPureImage(String fileName, String contentType) {
        if (contentType != null && contentType.toLowerCase().startsWith("image/")) {
            return true;
        }
        String ext =
                dev.ppmb.intellihub.rag.parser.CharsetEncodingSupport.extractExtension(fileName);
        return List.of("png", "jpg", "jpeg", "webp", "gif", "bmp").contains(ext);
    }

    private static String resolveMultimodalMimeType(ParsedDocument parsed, String rawContentType) {
        if (parsed.metadata() != null && parsed.metadata().get("mime_type") instanceof String m) {
            return m;
        }
        if ("PDF".equalsIgnoreCase(parsed.fileFormat())) {
            return "application/pdf";
        }
        if (rawContentType != null) {
            String lower = rawContentType.toLowerCase();
            if (lower.startsWith("image/") || lower.startsWith("application/pdf")) {
                return lower.split(";")[0].strip();
            }
        }
        return null;
    }

    private static List<MultipartFile> collectMultipartFiles(
            List<MultipartFile> files, MultipartFile singleFile) {
        List<MultipartFile> list = new ArrayList<>();
        if (files != null) {
            for (MultipartFile f : files) {
                if (f != null && !f.isEmpty()) {
                    list.add(f);
                }
            }
        }
        if (singleFile != null && !singleFile.isEmpty() && !list.contains(singleFile)) {
            list.add(singleFile);
        }
        if (list.isEmpty()) {
            throw IntelliHubExceptionUtil.invalidParamException("请至少选择一个非空文件进行上传");
        }
        return list;
    }

    private static byte[] readMultipartBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw IntelliHubExceptionUtil.exception(
                    GlobalErrorCodeConstants.AI_DOCUMENT_PARSE_ERROR,
                    "读取上传文件字节流失败: " + e.getMessage());
        }
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
     * 按摄取顺序分页列出已持久化的知识切片（无需搜索关键词）。
     */
    @GetMapping("/documents")
    public ApiResponse<List<ScoredDocumentDto>> listDocuments(
            @org.springframework.web.bind.annotation.RequestParam(
                            value = "limit",
                            defaultValue = "50")
                    int limit,
            @org.springframework.web.bind.annotation.RequestParam(
                            value = "tenantId",
                            required = false)
                    String tenantId,
            @org.springframework.web.bind.annotation.RequestParam(value = "kbId", required = false)
                    String kbId) {
        Map<String, Object> filters = new LinkedHashMap<>();
        if (tenantId != null && !tenantId.isBlank()) {
            filters.put(DocumentIngestionPipeline.META_TENANT_ID, tenantId.strip());
        }
        if (kbId != null && !kbId.isBlank()) {
            filters.put(DocumentIngestionPipeline.META_KB_ID, kbId.strip());
        }
        List<ScoredDocument> docs = knowledgeStore.listAll(limit, filters);
        return ApiResponse.success(docs.stream().map(ScoredDocumentDto::fromDomain).toList());
    }

    /**
     * 查询知识库当前统计数据（总切片数、已向量化切片数、向量维度与持久化状态等）。
     */
    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> getKnowledgeStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalChunks", knowledgeStore.size());
        stats.put("vectorizedChunks", knowledgeStore.getVectorizedCount());
        stats.put("vectorAvailable", knowledgeStore.isVectorAvailable());
        stats.put("vectorDimensions", knowledgeStore.getVectorDimensions());
        stats.put("persistent", knowledgeStore.isPersistent());
        stats.put(
                "persistencePath",
                knowledgeStore.getPersistencePath() != null
                        ? knowledgeStore.getPersistencePath()
                        : "");
        stats.put(
                "supportedFormats", ingestionPipeline.getParserRegistry().getSupportedExtensions());
        return ApiResponse.success(stats);
    }
}
