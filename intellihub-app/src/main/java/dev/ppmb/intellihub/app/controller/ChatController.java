package dev.ppmb.intellihub.app.controller;

import dev.ppmb.intellihub.agent.engine.AgentExecutionBudget;
import dev.ppmb.intellihub.agent.engine.AgentRunResult;
import dev.ppmb.intellihub.agent.engine.AgentStopReason;
import dev.ppmb.intellihub.agent.engine.ReActAgentExecutor;
import dev.ppmb.intellihub.app.common.ApiResponse;
import dev.ppmb.intellihub.app.dto.agent.AgentStepTraceDto;
import dev.ppmb.intellihub.app.dto.chat.ChannelInfoResponse;
import dev.ppmb.intellihub.app.dto.chat.ChatCompletionRequest;
import dev.ppmb.intellihub.app.dto.chat.ChatCompletionResponse;
import dev.ppmb.intellihub.app.dto.chat.UnifiedChatTurnRequest;
import dev.ppmb.intellihub.app.dto.chat.UnifiedChatTurnResponse;
import dev.ppmb.intellihub.app.dto.rag.ScoredDocumentDto;
import dev.ppmb.intellihub.core.clean.AiOutputUtil;
import dev.ppmb.intellihub.core.exception.GlobalErrorCodeConstants;
import dev.ppmb.intellihub.core.exception.IntelliHubExceptionUtil;
import dev.ppmb.intellihub.llm.registry.IntelliHubModelRegistry;
import dev.ppmb.intellihub.prompt.render.SafePromptRenderer;
import dev.ppmb.intellihub.prompt.structured.StructuredOutputExecutor;
import dev.ppmb.intellihub.prompt.template.PromptRegistry;
import dev.ppmb.intellihub.prompt.template.PromptTemplateSpec;
import dev.ppmb.intellihub.rag.engine.RagQueryEngine;
import dev.ppmb.intellihub.rag.ingest.DocumentIngestionPipeline;
import dev.ppmb.intellihub.rag.retrieval.RagSearchRequest;
import dev.ppmb.intellihub.rag.retrieval.ScoredDocument;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 核心聊天对话、多模型渠道调度与一体化对话工作台 REST 控制器。
 */
@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final IntelliHubModelRegistry modelRegistry;
    private final ReActAgentExecutor agentExecutor;
    private final RagQueryEngine ragQueryEngine;
    private final PromptRegistry promptRegistry;
    private final SafePromptRenderer safePromptRenderer;
    private final StructuredOutputExecutor structuredOutputExecutor;

    public ChatController(
            IntelliHubModelRegistry modelRegistry,
            ReActAgentExecutor agentExecutor,
            RagQueryEngine ragQueryEngine,
            PromptRegistry promptRegistry,
            SafePromptRenderer safePromptRenderer,
            StructuredOutputExecutor structuredOutputExecutor) {
        this.modelRegistry = modelRegistry;
        this.agentExecutor = agentExecutor;
        this.ragQueryEngine = ragQueryEngine;
        this.promptRegistry = promptRegistry;
        this.safePromptRenderer = safePromptRenderer;
        this.structuredOutputExecutor = structuredOutputExecutor;
    }

    /**
     * 查询当前已注册的模型渠道与高可用路由拓扑信息。
     */
    @GetMapping("/channels")
    public ApiResponse<ChannelInfoResponse> getChannels() {
        return ApiResponse.success(
                new ChannelInfoResponse(
                        modelRegistry.getDefaultChannelOrRoute(),
                        modelRegistry.getChannelNames(),
                        modelRegistry.getRouteNames()));
    }

    /**
     * 一体化智能对话工作台回合接口（单一聊天窗口融合：多轮记忆、安全防注入护栏、RAG 知识溯源、ReAct 工具调用、人工审批挂起与结构化提取）。
     */
    @PostMapping("/turn")
    public ApiResponse<UnifiedChatTurnResponse> unifiedChatTurn(
            @RequestBody UnifiedChatTurnRequest request) {
        boolean hasAttachments =
                request != null
                        && request.getAttachments() != null
                        && !request.getAttachments().isEmpty();
        if (request == null
                || ((request.getMessage() == null || request.getMessage().isBlank())
                        && !hasAttachments)) {
            throw IntelliHubExceptionUtil.exception(
                    GlobalErrorCodeConstants.AI_PROMPT_EMPTY, "对话消息内容不能为空");
        }

        String userMsg =
                (request.getMessage() != null && !request.getMessage().isBlank())
                        ? request.getMessage().strip()
                        : "请详细分析并解读我上传的图片或文档内容。";
        // 1. 提示词防注入安全护栏实时校验（越狱攻击直接返回 1001000006）
        safePromptRenderer.getInjectionGuard().validate("message", userMsg);

        long startTime = System.currentTimeMillis();
        String targetName = resolveTargetChannel(request.getChannel());
        ChatModel chatModel = modelRegistry.getChatModel(targetName);
        List<Media> multimodalMediaList = buildSpringAiMediaList(request.getAttachments());

        UnifiedChatTurnResponse response = new UnifiedChatTurnResponse();
        response.setChannel(targetName);

        // 2. 如果开启了 RAG 知识库增强，先执行混合检索 + Rerank 精排获取参考切片
        List<ScoredDocument> ragCitations = List.of();
        if (Boolean.TRUE.equals(request.getEnableRag())) {
            RagSearchRequest.Builder searchBuilder = RagSearchRequest.builder(userMsg).topK(4);
            if (request.getTenantId() != null && !request.getTenantId().isBlank()) {
                searchBuilder.filterMetadata(
                        DocumentIngestionPipeline.META_TENANT_ID, request.getTenantId().strip());
            }
            if (request.getKbId() != null && !request.getKbId().isBlank()) {
                searchBuilder.filterMetadata(
                        DocumentIngestionPipeline.META_KB_ID, request.getKbId().strip());
            }
            ragCitations = ragQueryEngine.retrieveAndRerank(searchBuilder.build());
            response.setCitations(
                    ragCitations.stream().map(ScoredDocumentDto::fromDomain).toList());
        } else {
            response.setCitations(List.of());
        }

        // 3. 如果开启了结构化 JSON 提取模式，执行容错结构化抽取并生成卡片数据
        if (Boolean.TRUE.equals(request.getEnableStructuredExtract())) {
            StringBuilder extractPromptText = new StringBuilder();
            extractPromptText.append("请从以下输入内容中精准提取关键结构化信息：\n");
            if (request.getTargetFields() != null && !request.getTargetFields().isEmpty()) {
                extractPromptText
                        .append("重点提取字段：")
                        .append(String.join(", ", request.getTargetFields()))
                        .append("\n");
            }
            extractPromptText.append("\n【输入文本】\n").append(userMsg);
            String attachmentsBlock = formatAttachmentsBlock(request.getAttachments());
            if (!attachmentsBlock.isEmpty()) {
                extractPromptText.append("\n\n").append(attachmentsBlock);
            }

            UserMessage.Builder extractMsgBuilder =
                    UserMessage.builder().text(extractPromptText.toString());
            if (!multimodalMediaList.isEmpty()) {
                extractMsgBuilder.media(multimodalMediaList);
            }

            Map<String, Object> extracted =
                    structuredOutputExecutor.execute(
                            chatModel,
                            new Prompt(extractMsgBuilder.build()),
                            new ParameterizedTypeReference<Map<String, Object>>() {});

            response.setStructuredData(extracted);
            response.setContent(
                    "已为您从输入内容中精准提取出 **"
                            + (extracted != null ? extracted.size() : 0)
                            + " 项**结构化字段，详情请查看下方结构化数据卡片：");
            response.setReasoningContent(
                    "正在解析非结构化文本 -> 识别目标 Schema 字段 -> 执行容错型 JSON 反序列化校验 -> 提取完成。");
            response.setStopReason(AgentStopReason.COMPLETED);
            response.setSteps(List.of());
            response.setLatencyMs(System.currentTimeMillis() - startTime);
            return ApiResponse.success(response);
        }

        // 4. 组装多轮消息上下文（System 由后端 PromptRegistry 统一管控 + History + 带 RAG 知识引用的当前 UserMessage）
        List<Message> messages = new ArrayList<>();
        String effectiveSystemPrompt = resolveBackendSystemPrompt();
        if (effectiveSystemPrompt != null && !effectiveSystemPrompt.isBlank()) {
            messages.add(new SystemMessage(effectiveSystemPrompt));
        }

        if (request.getHistory() != null) {
            for (UnifiedChatTurnRequest.HistoryMessage hm : request.getHistory()) {
                if (hm == null || hm.content() == null || hm.content().isBlank()) {
                    continue;
                }
                if ("assistant".equalsIgnoreCase(hm.role())) {
                    messages.add(new AssistantMessage(hm.content()));
                } else if ("user".equalsIgnoreCase(hm.role())) {
                    messages.add(new UserMessage(hm.content()));
                }
            }
        }

        String enrichedUserPrompt =
                buildEnrichedUserMessage(userMsg, ragCitations, request.getAttachments());
        UserMessage.Builder userMessageBuilder = UserMessage.builder().text(enrichedUserPrompt);
        if (!multimodalMediaList.isEmpty()) {
            userMessageBuilder.media(multimodalMediaList);
        }
        messages.add(userMessageBuilder.build());

        // 5. 默认使用 ReAct 智能体引擎执行（自动按需调用工具或直接回答，并支持高风险工具挂起审批）
        boolean useAgent =
                request.getEnableAgent() == null || Boolean.TRUE.equals(request.getEnableAgent());
        if (useAgent) {
            AgentRunResult runResult =
                    agentExecutor.run(
                            chatModel,
                            new Prompt(messages),
                            null,
                            Map.of(),
                            AgentExecutionBudget.defaults());

            String rawOut = runResult.output() != null ? runResult.output() : "";
            response.setContent(AiOutputUtil.stripThink(rawOut));
            String think = AiOutputUtil.extractThink(rawOut);
            if ((think == null || think.isBlank()) && runResult.steps() != null) {
                for (var step : runResult.steps()) {
                    if (step.thought() != null && !step.thought().isBlank()) {
                        String stepThink = AiOutputUtil.extractThink(step.thought());
                        think = !stepThink.isBlank() ? stepThink : step.thought();
                        break;
                    }
                }
            }
            response.setReasoningContent(think);
            response.setStopReason(runResult.stopReason());
            response.setAwaitingApproval(runResult.isAwaitingApproval());
            if (runResult.checkpoint() != null) {
                response.setCheckpointId(runResult.checkpoint().checkpointId());
            }
            if (runResult.steps() != null) {
                response.setSteps(
                        runResult.steps().stream().map(AgentStepTraceDto::fromDomain).toList());
            } else {
                response.setSteps(List.of());
            }
            response.setTotalTokens(runResult.totalTokens());
            response.setLatencyMs(System.currentTimeMillis() - startTime);
            return ApiResponse.success(response);
        }

        // 6. 纯对话模式（关闭 Agent 工具时直接调用底层 ChatModel）
        ChatResponse chatResp = chatModel.call(new Prompt(messages));
        if (chatResp != null && chatResp.getResults() != null && !chatResp.getResults().isEmpty()) {
            String rawText = extractMainResponseText(chatResp);
            response.setContent(AiOutputUtil.stripThink(rawText));
            String nativeThought = extractThoughtFromResponse(chatResp);
            String extractedThink =
                    !nativeThought.isEmpty() ? nativeThought : AiOutputUtil.extractThink(rawText);
            if (!extractedThink.isEmpty()) {
                response.setReasoningContent(extractedThink);
            }
            if (chatResp.getMetadata() != null) {
                response.setModel(chatResp.getMetadata().getModel());
                Usage usage = chatResp.getMetadata().getUsage();
                if (usage != null && usage.getTotalTokens() != null) {
                    response.setTotalTokens(usage.getTotalTokens().longValue());
                }
            }
        }
        response.setStopReason(AgentStopReason.COMPLETED);
        response.setSteps(List.of());
        response.setLatencyMs(System.currentTimeMillis() - startTime);
        return ApiResponse.success(response);
    }

    private String resolveBackendSystemPrompt() {
        try {
            PromptTemplateSpec spec = promptRegistry.get("default-copilot-system");
            if (spec != null && spec.systemTemplate() != null && !spec.systemTemplate().isBlank()) {
                return spec.systemTemplate();
            }
        } catch (Exception ignored) {
            // 模板不存在时回退默认系统提示词
        }
        return "你是 IntelliHub 企业级智能助手。你可以在同一对话窗口中协助用户解答架构规范问题、调用工具查询时间或计算税费、执行带人工审批的敏感操作，并在引用知识库时标注 [#1] 角标。";
    }

    private String buildEnrichedUserMessage(
            String userMsg,
            List<ScoredDocument> citations,
            List<UnifiedChatTurnRequest.AttachedDocumentContext> attachments) {
        String attachmentsBlock = formatAttachmentsBlock(attachments);
        if ((citations == null || citations.isEmpty()) && attachmentsBlock.isEmpty()) {
            return userMsg;
        }
        StringBuilder sb = new StringBuilder();
        if (citations != null && !citations.isEmpty()) {
            sb.append("【参考知识库切片】\n");
            int idx = 1;
            for (ScoredDocument doc : citations) {
                String text =
                        doc.document().getText() != null ? doc.document().getText().strip() : "";
                if (text.isEmpty()) {
                    continue;
                }
                Object section = doc.document().getMetadata().getOrDefault("section_path", "知识分片");
                sb.append("[#")
                        .append(idx)
                        .append(" | section=")
                        .append(section)
                        .append("]\n")
                        .append(text)
                        .append("\n\n");
                idx++;
            }
        }
        if (!attachmentsBlock.isEmpty()) {
            sb.append(attachmentsBlock).append("\n\n");
        }
        sb.append("【用户问题】\n").append(userMsg);
        return sb.toString();
    }

    private String formatAttachmentsBlock(
            List<UnifiedChatTurnRequest.AttachedDocumentContext> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (UnifiedChatTurnRequest.AttachedDocumentContext att : attachments) {
            if (att == null) {
                continue;
            }
            // 纯图片已通过 Spring AI Media 注入视觉通道，无需拼接冗余占位文本
            if (att.fileFormat() != null && att.fileFormat().startsWith("IMAGE:")) {
                continue;
            }
            if (att.content() == null || att.content().isBlank()) {
                continue;
            }
            if (sb.isEmpty()) {
                sb.append("【当前对话上传的附件解析内容】\n");
            }
            String name = att.fileName() != null ? att.fileName() : "unnamed";
            String fmt = att.fileFormat() != null ? att.fileFormat() : "TEXT";
            String truncated =
                    att.content().length() > 16000
                            ? att.content().substring(0, 16000) + "\n...(内容过长已截断)..."
                            : att.content();
            sb.append("<attached_document name=\"")
                    .append(name)
                    .append("\" format=\"")
                    .append(fmt)
                    .append("\">\n")
                    .append(truncated.strip())
                    .append("\n</attached_document>\n\n");
        }
        return sb.toString().strip();
    }

    /**
     * 将前端上传或粘贴的图片（PNG/JPEG/WebP/GIF）与 PDF 字节流转换为 Spring AI 原生 {@link Media} 对象列表，直接传入 {@code
     * gemini-3.5-flash-lite} / {@code gemini-3.8-flash} 进行多模态视觉推理。
     */
    public static List<Media> buildSpringAiMediaList(
            List<UnifiedChatTurnRequest.AttachedDocumentContext> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        List<Media> mediaList = new ArrayList<>();
        for (UnifiedChatTurnRequest.AttachedDocumentContext att : attachments) {
            if (att == null || att.base64Data() == null || att.base64Data().isBlank()) {
                continue;
            }
            String rawBase64 = att.base64Data().strip();
            String detectedMime = att.mimeType();
            if (rawBase64.startsWith("data:")) {
                int commaIdx = rawBase64.indexOf(',');
                if (commaIdx > 0) {
                    String header = rawBase64.substring(5, commaIdx);
                    int semiIdx = header.indexOf(';');
                    if ((detectedMime == null || detectedMime.isBlank()) && semiIdx > 0) {
                        detectedMime = header.substring(0, semiIdx).strip();
                    }
                    rawBase64 = rawBase64.substring(commaIdx + 1).strip();
                }
            }
            if (detectedMime == null || detectedMime.isBlank()) {
                if (att.fileFormat() != null && "PDF".equalsIgnoreCase(att.fileFormat().strip())) {
                    detectedMime = "application/pdf";
                } else {
                    detectedMime = "image/png";
                }
            }
            String lowerMime = detectedMime.toLowerCase();
            if (!lowerMime.startsWith("image/")
                    && !lowerMime.equals("application/pdf")
                    && !lowerMime.startsWith("audio/")
                    && !lowerMime.startsWith("video/")) {
                continue;
            }
            try {
                byte[] decoded = Base64.getDecoder().decode(rawBase64);
                if (decoded.length > 0) {
                    mediaList.add(
                            Media.builder()
                                    .mimeType(MimeTypeUtils.parseMimeType(lowerMime))
                                    .data(decoded)
                                    .name(att.fileName() != null ? att.fileName() : "media")
                                    .build());
                }
            } catch (Exception e) {
                log.warn(
                        "[ChatController] 忽略无法解码的多模态 Base64 附件 [{}]: {}",
                        att.fileName(),
                        e.getMessage());
            }
        }
        return mediaList;
    }

    /**
     * 同步非流式聊天补全接口。
     */
    @PostMapping("/completions")
    public ApiResponse<ChatCompletionResponse> chatCompletion(
            @RequestBody ChatCompletionRequest request) {
        validateRequest(request);

        String targetName = resolveTargetChannel(request.getChannel());
        ChatModel chatModel = modelRegistry.getChatModel(targetName);
        Prompt prompt = buildPrompt(request);

        long start = System.currentTimeMillis();
        ChatResponse response = chatModel.call(prompt);
        long latency = System.currentTimeMillis() - start;

        ChatCompletionResponse dto = new ChatCompletionResponse();
        dto.setChannel(targetName);
        dto.setLatencyMs(latency);

        if (response != null && response.getResults() != null && !response.getResults().isEmpty()) {
            String rawText = extractMainResponseText(response);
            dto.setRawContent(rawText);
            dto.setContent(AiOutputUtil.stripThink(rawText));

            String nativeThought = extractThoughtFromResponse(response);
            if (!nativeThought.isEmpty()) {
                dto.setReasoningContent(nativeThought);
            } else {
                String extractedThink = AiOutputUtil.extractThink(rawText);
                if (!extractedThink.isEmpty()) {
                    dto.setReasoningContent(extractedThink);
                }
            }

            if (response.getMetadata() != null) {
                dto.setModel(response.getMetadata().getModel());
                Usage usage = response.getMetadata().getUsage();
                if (usage != null) {
                    if (usage.getPromptTokens() != null) {
                        dto.setPromptTokens(usage.getPromptTokens().longValue());
                    }
                    if (usage.getCompletionTokens() != null) {
                        dto.setCompletionTokens(usage.getCompletionTokens().longValue());
                    }
                    if (usage.getTotalTokens() != null) {
                        dto.setTotalTokens(usage.getTotalTokens().longValue());
                    }
                }
            }
        }

        return ApiResponse.success(dto);
    }

    private static boolean isThoughtMessage(AssistantMessage msg) {
        if (msg == null || msg.getMetadata() == null) {
            return false;
        }
        return Boolean.TRUE.equals(msg.getMetadata().get("isThought"))
                || Boolean.TRUE.equals(msg.getMetadata().get("thought"));
    }

    private static String extractMainResponseText(ChatResponse response) {
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            return "";
        }
        for (var gen : response.getResults()) {
            if (gen != null
                    && gen.getOutput() != null
                    && !isThoughtMessage(gen.getOutput())
                    && gen.getOutput().getText() != null
                    && !gen.getOutput().getText().isBlank()) {
                return gen.getOutput().getText();
            }
        }
        return response.getResult() != null
                        && response.getResult().getOutput() != null
                        && response.getResult().getOutput().getText() != null
                ? response.getResult().getOutput().getText()
                : "";
    }

    private static String extractThoughtFromResponse(ChatResponse response) {
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (var gen : response.getResults()) {
            if (gen == null || gen.getOutput() == null) {
                continue;
            }
            AssistantMessage msg = gen.getOutput();
            if (isThoughtMessage(msg) && msg.getText() != null && !msg.getText().isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append("\n");
                }
                sb.append(msg.getText().strip());
            } else if (msg.getMetadata().get("reasoningContent") instanceof String rs
                    && !rs.isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append("\n");
                }
                sb.append(rs.strip());
            }
        }
        return sb.toString();
    }

    /**
     * Server-Sent Events (SSE) 流式聊天补全接口。
     */
    @PostMapping(value = "/completions/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamChatCompletion(
            @RequestBody ChatCompletionRequest request) {
        validateRequest(request);

        String targetName = resolveTargetChannel(request.getChannel());
        ChatModel chatModel = modelRegistry.getChatModel(targetName);
        Prompt prompt = buildPrompt(request);

        return chatModel.stream(prompt)
                .map(chunk -> {
                    String text = "";
                    if (chunk != null
                            && chunk.getResult() != null
                            && chunk.getResult().getOutput() != null
                            && chunk.getResult().getOutput().getText() != null) {
                        text = chunk.getResult().getOutput().getText();
                    }
                    return ServerSentEvent.<String>builder().event("message").data(text).build();
                })
                .concatWith(
                        Flux.just(
                                ServerSentEvent.<String>builder()
                                        .event("done")
                                        .data("[DONE]")
                                        .build()));
    }

    private String resolveTargetChannel(String requestedChannel) {
        if (requestedChannel != null && !requestedChannel.isBlank()) {
            return requestedChannel.strip();
        }
        return modelRegistry.getDefaultChannelOrRoute();
    }

    private void validateRequest(ChatCompletionRequest request) {
        if (request == null || request.getPrompt() == null || request.getPrompt().isBlank()) {
            throw IntelliHubExceptionUtil.exception(
                    GlobalErrorCodeConstants.AI_PROMPT_EMPTY, "提示词不能为空");
        }
    }

    private Prompt buildPrompt(ChatCompletionRequest request) {
        List<Message> messages = new ArrayList<>();
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
            messages.add(new SystemMessage(request.getSystemPrompt().strip()));
        }
        messages.add(new UserMessage(request.getPrompt().strip()));

        ChatOptions options = null;
        if (request.getModel() != null || request.getTemperature() != null) {
            ChatOptions.Builder optionsBuilder = ChatOptions.builder();
            if (request.getModel() != null && !request.getModel().isBlank()) {
                optionsBuilder.model(request.getModel().strip());
            }
            if (request.getTemperature() != null) {
                optionsBuilder.temperature(request.getTemperature());
            }
            options = optionsBuilder.build();
        }

        return options != null ? new Prompt(messages, options) : new Prompt(messages);
    }
}
