package dev.ppmb.intellihub.agent.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.ppmb.intellihub.agent.tool.GovernedToolRegistry;
import dev.ppmb.intellihub.core.clean.AiOutputUtil;
import dev.ppmb.intellihub.core.exception.GlobalErrorCodeConstants;
import dev.ppmb.intellihub.core.exception.IntelliHubExceptionUtil;
import dev.ppmb.intellihub.prompt.render.SafePromptRenderer;
import dev.ppmb.intellihub.prompt.template.PromptTemplateSpec;
import dev.ppmb.intellihub.rag.splitter.SemanticMarkdownDocumentSplitter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

/**
 * 生产级多步 ReAct（Reason + Act + Observe）智能体执行引擎（ReAct Agent Executor）。
 *
 * <p>核心工程防护机制：
 *
 * <ul>
 *   <li><b>键序无关规范化 JSON 指纹与防死循环（Canonical JSON Loop Guard）</b>：基于 Jackson {@link JsonNode}
 *       递归对所有 JSON 对象属性名按字典序重排（使 {@code {"a":1,"b":2}} 与 {@code {"b":2,"a":1}} 生成完全相同的规范指纹），超长参数自动计算
 *       SHA-256 摘要；重复调用超过 {@link AgentExecutionBudget#maxDuplicateToolCalls()} 时触发 {@code
 *       [LOOP_GUARD]}；
 *   <li><b>非阻塞异步人工审批挂起与恢复（Human-in-the-Loop Checkpointing）</b>：遇到 {@code AWAITING_HUMAN_APPROVAL}
 *       时立即保存 {@link AgentCheckpoint} 并返回 {@link AgentStopReason#AWAITING_APPROVAL} 释放 Web 工作线程，支持通过
 *       {@link #resume} 随时恢复；
 *   <li><b>Token 预算（{@code maxTotalTokens}）与全局 Deadline 下传</b>：实时累加每轮推理的 Token 消耗并在工具上下文中注入剩余毫秒数
 *       {@link GovernedToolRegistry#CONTEXT_REMAINING_DEADLINE_MS}；
 *   <li><b>协议完备的多工具并行对齐与收尾总结</b>：确保每个 {@code ToolCall} 均有匹配的 {@code ToolResponse} ID，并在预算触顶时自动汇总。
 * </ul>
 */
public class ReActAgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(ReActAgentExecutor.class);
    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper();
    private static final int CANONICAL_HASH_THRESHOLD = 128;

    private final GovernedToolRegistry toolRegistry;
    private final SafePromptRenderer promptRenderer;
    private final AgentExecutionBudget defaultBudget;
    private final Map<String, AgentCheckpoint> checkpointStore = new ConcurrentHashMap<>();

    public ReActAgentExecutor(GovernedToolRegistry toolRegistry) {
        this(toolRegistry, new SafePromptRenderer(), AgentExecutionBudget.defaults());
    }

    public ReActAgentExecutor(
            GovernedToolRegistry toolRegistry,
            SafePromptRenderer promptRenderer,
            AgentExecutionBudget defaultBudget) {
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry 不能为空");
        this.promptRenderer = Objects.requireNonNull(promptRenderer, "promptRenderer 不能为空");
        this.defaultBudget = Objects.requireNonNull(defaultBudget, "defaultBudget 不能为空");
    }

    /**
     * 使用默认预算与全部已注册工具运行 Agent。
     */
    public AgentRunResult run(ChatModel chatModel, String userGoal) {
        return run(chatModel, userGoal, defaultBudget);
    }

    /**
     * 使用指定预算与全部已注册工具运行 Agent。
     */
    public AgentRunResult run(ChatModel chatModel, String userGoal, AgentExecutionBudget budget) {
        if (userGoal == null || userGoal.isBlank()) {
            throw IntelliHubExceptionUtil.invalidParamException("Agent userGoal 不能为空");
        }
        return run(chatModel, new Prompt(userGoal.strip()), null, Map.of(), budget);
    }

    /**
     * 基于 {@link PromptTemplateSpec} 渲染初始 Prompt 并运行 Agent。
     */
    public AgentRunResult run(
            ChatModel chatModel,
            PromptTemplateSpec spec,
            Map<String, Object> variables,
            Collection<String> enabledToolNames,
            Map<String, Object> toolContext,
            AgentExecutionBudget budget) {
        Prompt initialPrompt = promptRenderer.render(spec, variables);
        return run(chatModel, initialPrompt, enabledToolNames, toolContext, budget);
    }

    /**
     * 完整自定义参数运行多步 ReAct Agent。
     */
    public AgentRunResult run(
            ChatModel chatModel,
            Prompt initialPrompt,
            Collection<String> enabledToolNames,
            Map<String, Object> toolContext,
            AgentExecutionBudget budget) {
        Objects.requireNonNull(chatModel, "chatModel 不能为空");
        Objects.requireNonNull(initialPrompt, "initialPrompt 不能为空");
        AgentExecutionBudget effectiveBudget = budget != null ? budget : defaultBudget;
        List<String> enabledList = enabledToolNames == null ? null : List.copyOf(enabledToolNames);

        return executeLoop(
                chatModel,
                new ArrayList<>(initialPrompt.getInstructions()),
                initialPrompt.getOptions(),
                enabledList,
                toolContext != null ? toolContext : Map.of(),
                effectiveBudget,
                new ArrayList<>(),
                new HashMap<>(),
                1,
                0,
                0L);
    }

    /**
     * 从异步人工审批挂起快照（{@link AgentCheckpoint}）恢复执行。
     *
     * @param chatModel 聊天模型
     * @param checkpointId 挂起时返回的快照 ID
     * @param approved 人工审批是否批准
     * @param reviewerNote 审批人备注或拒绝原因
     * @param toolContext 附加工具上下文
     * @param budget 恢复执行后的预算配置
     */
    public AgentRunResult resume(
            ChatModel chatModel,
            String checkpointId,
            boolean approved,
            String reviewerNote,
            Map<String, Object> toolContext,
            AgentExecutionBudget budget) {
        Objects.requireNonNull(chatModel, "chatModel 不能为空");
        if (checkpointId == null || checkpointId.isBlank()) {
            throw IntelliHubExceptionUtil.invalidParamException("checkpointId 不能为空");
        }
        AgentCheckpoint cp = checkpointStore.remove(checkpointId.strip());
        if (cp == null) {
            throw IntelliHubExceptionUtil.exception(
                    GlobalErrorCodeConstants.NOT_FOUND, "AgentCheckpoint [" + checkpointId + "]");
        }

        AgentExecutionBudget effectiveBudget = budget != null ? budget : defaultBudget;
        Map<String, Object> safeContext =
                new LinkedHashMap<>(toolContext != null ? toolContext : Map.of());
        safeContext.putIfAbsent(
                GovernedToolRegistry.CONTEXT_REMAINING_DEADLINE_MS,
                effectiveBudget.maxExecutionDuration().toMillis());

        List<Message> conversation = new ArrayList<>(cp.conversationHistory());
        conversation.add(cp.pendingAssistantMessage());

        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        List<AgentStepTrace.ToolCallExecutionRecord> records = new ArrayList<>();
        for (AssistantMessage.ToolCall tc : cp.pendingAssistantMessage().getToolCalls()) {
            String callId = tc.id() != null ? tc.id() : "resume_call";
            String toolName = tc.name() != null ? tc.name() : "";
            String argsJson = tc.arguments() != null ? tc.arguments() : "{}";

            if (Objects.equals(callId, cp.pendingToolCall().id())
                    && Objects.equals(toolName, cp.pendingToolCall().name())) {
                if (approved) {
                    GovernedToolRegistry.ToolInvocationOutcome outcome =
                            toolRegistry.executeApprovedTool(toolName, argsJson, safeContext);
                    responses.add(
                            new ToolResponseMessage.ToolResponse(
                                    callId,
                                    toolName,
                                    ensureJsonToolResponse(outcome.observation())));
                    records.add(
                            new AgentStepTrace.ToolCallExecutionRecord(
                                    callId,
                                    toolName,
                                    argsJson,
                                    outcome.observation(),
                                    outcome.success(),
                                    false,
                                    false,
                                    outcome.durationMs()));
                } else {
                    String rejectMsg =
                            "{\"status\":\"HUMAN_APPROVAL_REJECTED\",\"tool\":\""
                                    + toolName
                                    + "\",\"reason\":\""
                                    + (reviewerNote != null
                                            ? reviewerNote
                                            : "Rejected by human reviewer")
                                    + "\"}";
                    responses.add(
                            new ToolResponseMessage.ToolResponse(callId, toolName, rejectMsg));
                    records.add(
                            new AgentStepTrace.ToolCallExecutionRecord(
                                    callId, toolName, argsJson, rejectMsg, false, false, true, 0L));
                }
            } else {
                GovernedToolRegistry.ToolInvocationOutcome outcome =
                        toolRegistry.executeTool(toolName, argsJson, safeContext);
                responses.add(
                        new ToolResponseMessage.ToolResponse(
                                callId, toolName, ensureJsonToolResponse(outcome.observation())));
                records.add(
                        new AgentStepTrace.ToolCallExecutionRecord(
                                callId,
                                toolName,
                                argsJson,
                                outcome.observation(),
                                outcome.success(),
                                false,
                                outcome.approvalRejected(),
                                outcome.durationMs()));
            }
        }

        conversation.add(ToolResponseMessage.builder().responses(responses).build());
        List<AgentStepTrace> steps = new ArrayList<>(cp.completedSteps());
        steps.add(
                new AgentStepTrace(
                        cp.suspendedAtIteration(), "Resumed from human approval", records, 0L));

        return executeLoop(
                chatModel,
                conversation,
                cp.baseOptions(),
                cp.enabledToolNames(),
                safeContext,
                effectiveBudget,
                steps,
                new HashMap<>(cp.callFingerprintCounts()),
                cp.suspendedAtIteration() + 1,
                cp.totalToolCalls(),
                cp.totalTokens());
    }

    public Optional<AgentCheckpoint> findCheckpoint(String checkpointId) {
        if (checkpointId == null || checkpointId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(checkpointStore.get(checkpointId.strip()));
    }

    private AgentRunResult executeLoop(
            ChatModel chatModel,
            List<Message> conversation,
            ChatOptions baseOptions,
            List<String> enabledToolNames,
            Map<String, Object> baseToolContext,
            AgentExecutionBudget effectiveBudget,
            List<AgentStepTrace> stepTraces,
            Map<String, Integer> callFingerprintCounts,
            int startIteration,
            int initialToolCalls,
            long initialTokens) {
        List<ToolCallback> activeTools = toolRegistry.resolveToolCallbacks(enabledToolNames);
        ChatOptions loopOptions =
                buildToolAwareOptions(chatModel, baseOptions, activeTools, baseToolContext);

        long startWallMs = System.currentTimeMillis();
        long deadlineMs = startWallMs + effectiveBudget.maxExecutionDuration().toMillis();
        int totalToolCalls = initialToolCalls;
        long totalTokens = initialTokens;
        AgentStopReason stopReason = AgentStopReason.MAX_ITERATIONS_REACHED;

        for (int iteration = startIteration;
                iteration <= effectiveBudget.maxIterations();
                iteration++) {
            long now = System.currentTimeMillis();
            if (now > deadlineMs) {
                log.warn(
                        "[ReActAgentExecutor] 执行超时（超过 {}ms），提前终止 ReAct 循环",
                        effectiveBudget.maxExecutionDuration().toMillis());
                return new AgentRunResult(
                        "Agent 执行已达最大超时限制，请缩小任务范围后重试。",
                        AgentStopReason.TIMEOUT,
                        stepTraces,
                        stepTraces.size(),
                        totalToolCalls,
                        totalTokens,
                        null,
                        now - startWallMs);
            }

            long stepStart = System.currentTimeMillis();
            ChatResponse response = chatModel.call(new Prompt(conversation, loopOptions));
            totalTokens += extractTokenUsage(response);

            AssistantMessage assistantMsg = extractAssistantMessage(response);
            String nativeThought = extractNativeThought(response);
            String rawText = assistantMsg.getText() == null ? "" : assistantMsg.getText();
            String inlineThink = AiOutputUtil.extractThink(rawText);
            String extractedThink = !nativeThought.isEmpty() ? nativeThought : inlineThink;
            String cleanText = AiOutputUtil.stripThink(rawText);

            // 若模型未发起任何工具调用，说明已完成推理并直接给出最终答案
            if (!assistantMsg.hasToolCalls()) {
                long stepCost = System.currentTimeMillis() - stepStart;
                stepTraces.add(new AgentStepTrace(iteration, extractedThink, List.of(), stepCost));
                long totalCost = System.currentTimeMillis() - startWallMs;
                return new AgentRunResult(
                        cleanText,
                        AgentStopReason.COMPLETED,
                        stepTraces,
                        stepTraces.size(),
                        totalToolCalls,
                        totalTokens,
                        null,
                        totalCost);
            }

            // 检查累计 Token 预算是否已超限
            boolean tokenBudgetExceeded = totalTokens >= effectiveBudget.maxTotalTokens();

            // 模型发起了 1 个或多个工具调用
            List<Message> conversationSnapshotBeforeTools = new ArrayList<>(conversation);
            conversation.add(assistantMsg);
            List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
            List<AgentStepTrace.ToolCallExecutionRecord> toolRecords = new ArrayList<>();
            boolean toolBudgetReached = false;

            for (AssistantMessage.ToolCall toolCall : assistantMsg.getToolCalls()) {
                String callId =
                        toolCall.id() != null && !toolCall.id().isBlank()
                                ? toolCall.id()
                                : "call_" + iteration + "_" + (toolRecords.size() + 1);
                String toolName = toolCall.name() == null ? "" : toolCall.name().strip();
                String argsJson =
                        toolCall.arguments() == null ? "{}" : toolCall.arguments().strip();

                if (tokenBudgetExceeded) {
                    String tokenMsg =
                            "[TOKEN_BUDGET_EXCEEDED] Maximum token budget ("
                                    + effectiveBudget.maxTotalTokens()
                                    + ") reached. Synthesize final answer immediately.";
                    toolResponses.add(
                            new ToolResponseMessage.ToolResponse(
                                    callId, toolName, ensureJsonToolResponse(tokenMsg)));
                    toolRecords.add(
                            new AgentStepTrace.ToolCallExecutionRecord(
                                    callId, toolName, argsJson, tokenMsg, false, false, false, 0L));
                    continue;
                }

                // 检查总工具调用次数预算（仍需生成占位 ToolResponse 以保证 tool_call_id 协议对齐）
                if (totalToolCalls >= effectiveBudget.maxTotalToolCalls()) {
                    toolBudgetReached = true;
                    String budgetMsg =
                            "[BUDGET_EXCEEDED] Maximum total tool calls ("
                                    + effectiveBudget.maxTotalToolCalls()
                                    + ") reached. Synthesize your final answer from existing observations.";
                    toolResponses.add(
                            new ToolResponseMessage.ToolResponse(
                                    callId, toolName, ensureJsonToolResponse(budgetMsg)));
                    toolRecords.add(
                            new AgentStepTrace.ToolCallExecutionRecord(
                                    callId, toolName, argsJson, budgetMsg, false, false, false,
                                    0L));
                    continue;
                }

                totalToolCalls++;
                String fingerprint = toolName + "::" + normalizeArguments(argsJson);
                int seenCount = callFingerprintCounts.merge(fingerprint, 1, Integer::sum);

                // 防死循环守卫：相同工具 + 相同规范化 JSON 参数调用超过上限时直接拦截
                if (seenCount > effectiveBudget.maxDuplicateToolCalls()) {
                    log.warn(
                            "[ReActAgentExecutor] 触发防死循环拦截 [LOOP_GUARD]: tool={}, fingerprint={}, count={}",
                            toolName,
                            fingerprint,
                            seenCount);
                    String loopObs =
                            "[LOOP_GUARD] Duplicate tool call blocked: tool '"
                                    + toolName
                                    + "' with identical arguments has already been called "
                                    + (seenCount - 1)
                                    + " time(s). Do NOT call it again with the same arguments; use previous observations to formulate your answer.";
                    toolResponses.add(
                            new ToolResponseMessage.ToolResponse(
                                    callId, toolName, ensureJsonToolResponse(loopObs)));
                    toolRecords.add(
                            new AgentStepTrace.ToolCallExecutionRecord(
                                    callId, toolName, argsJson, loopObs, false, true, false, 0L));
                    continue;
                }

                // 将剩余全局 Deadline 毫秒数注入工具执行上下文，防止工具调用无限阻塞
                long remainingMs = Math.max(1L, deadlineMs - System.currentTimeMillis());
                Map<String, Object> stepToolContext = new LinkedHashMap<>(baseToolContext);
                stepToolContext.put(
                        GovernedToolRegistry.CONTEXT_REMAINING_DEADLINE_MS, remainingMs);

                GovernedToolRegistry.ToolInvocationOutcome outcome =
                        toolRegistry.executeTool(toolName, argsJson, stepToolContext);

                // 若遇到异步人工审批（Human-in-the-Loop），立即保存 Checkpoint 快照并释放当前工作线程！
                if (outcome.awaitingHumanApproval()) {
                    String checkpointId = "chk_" + UUID.randomUUID();
                    AgentCheckpoint checkpoint =
                            new AgentCheckpoint(
                                    checkpointId,
                                    outcome.approvalTicketId(),
                                    iteration,
                                    assistantMsg,
                                    toolCall,
                                    conversationSnapshotBeforeTools,
                                    baseOptions,
                                    enabledToolNames,
                                    stepTraces,
                                    callFingerprintCounts,
                                    totalToolCalls,
                                    totalTokens);
                    checkpointStore.put(checkpointId, checkpoint);
                    long totalCost = System.currentTimeMillis() - startWallMs;
                    return new AgentRunResult(
                            outcome.observation(),
                            AgentStopReason.AWAITING_APPROVAL,
                            stepTraces,
                            iteration,
                            totalToolCalls,
                            totalTokens,
                            checkpoint,
                            totalCost);
                }

                toolResponses.add(
                        new ToolResponseMessage.ToolResponse(
                                callId, toolName, ensureJsonToolResponse(outcome.observation())));
                toolRecords.add(
                        new AgentStepTrace.ToolCallExecutionRecord(
                                callId,
                                toolName,
                                argsJson,
                                outcome.observation(),
                                outcome.success(),
                                false,
                                outcome.approvalRejected(),
                                outcome.durationMs()));
            }

            conversation.add(ToolResponseMessage.builder().responses(toolResponses).build());
            long stepCost = System.currentTimeMillis() - stepStart;
            String stepThought = !extractedThink.isEmpty() ? extractedThink : cleanText;
            stepTraces.add(new AgentStepTrace(iteration, stepThought, toolRecords, stepCost));

            if (tokenBudgetExceeded) {
                stopReason = AgentStopReason.MAX_TOKENS_REACHED;
                break;
            }
            if (toolBudgetReached || totalToolCalls >= effectiveBudget.maxTotalToolCalls()) {
                stopReason = AgentStopReason.MAX_TOOL_CALLS_REACHED;
                break;
            }
        }

        // 达到最大轮次、最大工具调用次数或最大 Token 预算后，剥离工具回调发起一次收尾总结生成
        ChatResponse synthResp =
                synthesizeFinalAnswerResponse(chatModel, conversation, baseOptions);
        totalTokens += extractTokenUsage(synthResp);
        AssistantMessage synthMsg = extractAssistantMessage(synthResp);
        String synthesized =
                AiOutputUtil.stripThink(synthMsg.getText() == null ? "" : synthMsg.getText());
        long totalCost = System.currentTimeMillis() - startWallMs;
        return new AgentRunResult(
                synthesized,
                stopReason,
                stepTraces,
                stepTraces.size(),
                totalToolCalls,
                totalTokens,
                null,
                totalCost);
    }

    private static ChatResponse synthesizeFinalAnswerResponse(
            ChatModel chatModel, List<Message> conversation, ChatOptions baseOptions) {
        List<Message> finalMessages = new ArrayList<>(conversation);
        finalMessages.add(
                new UserMessage(
                        "Execution budget has been reached. Based on all observations gathered above, please provide your best final answer directly without invoking any tools."));
        ChatOptions textOnlyOptions = stripToolsFromOptions(chatModel, baseOptions);
        return chatModel.call(new Prompt(finalMessages, textOnlyOptions));
    }

    private static long extractTokenUsage(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return 0L;
        }
        Usage usage = response.getMetadata().getUsage();
        if (usage == null || usage.getTotalTokens() == null) {
            return 0L;
        }
        return Math.max(0L, usage.getTotalTokens().longValue());
    }

    private static ChatOptions buildToolAwareOptions(
            ChatModel chatModel,
            ChatOptions baseOptions,
            List<ToolCallback> callbacks,
            Map<String, Object> toolContext) {
        ChatOptions modelDefault = chatModel != null ? chatModel.getOptions() : null;
        ToolCallingChatOptions.Builder<?> builder;
        if (baseOptions instanceof ToolCallingChatOptions baseToolOpts
                && (modelDefault == null
                        || modelDefault.getClass().isAssignableFrom(baseOptions.getClass()))) {
            builder = baseToolOpts.mutate();
        } else if (modelDefault instanceof ToolCallingChatOptions modelToolOpts) {
            builder = modelToolOpts.mutate();
        } else {
            builder = ToolCallingChatOptions.builder();
        }

        if (baseOptions != null) {
            if (baseOptions.getModel() != null) {
                builder.model(baseOptions.getModel());
            }
            if (baseOptions.getTemperature() != null) {
                builder.temperature(baseOptions.getTemperature());
            }
            if (baseOptions.getTopP() != null) {
                builder.topP(baseOptions.getTopP());
            }
            if (baseOptions.getTopK() != null) {
                builder.topK(baseOptions.getTopK());
            }
            if (baseOptions.getMaxTokens() != null) {
                builder.maxTokens(baseOptions.getMaxTokens());
            }
            if (baseOptions.getStopSequences() != null) {
                builder.stopSequences(baseOptions.getStopSequences());
            }
            if (baseOptions.getFrequencyPenalty() != null) {
                builder.frequencyPenalty(baseOptions.getFrequencyPenalty());
            }
            if (baseOptions.getPresencePenalty() != null) {
                builder.presencePenalty(baseOptions.getPresencePenalty());
            }
        }
        if (callbacks != null && !callbacks.isEmpty()) {
            builder.toolCallbacks(callbacks);
        }
        if (toolContext != null && !toolContext.isEmpty()) {
            builder.toolContext(toolContext);
        }
        return builder.build();
    }

    private static ChatOptions stripToolsFromOptions(ChatModel chatModel, ChatOptions baseOptions) {
        ChatOptions modelDefault = chatModel != null ? chatModel.getOptions() : null;
        ChatOptions seed = baseOptions != null ? baseOptions : modelDefault;
        if (seed == null) {
            return null;
        }
        if (modelDefault instanceof ToolCallingChatOptions modelToolOpts) {
            ToolCallingChatOptions.Builder<?> builder = modelToolOpts.mutate();
            if (baseOptions != null) {
                if (baseOptions.getModel() != null) {
                    builder.model(baseOptions.getModel());
                }
                if (baseOptions.getTemperature() != null) {
                    builder.temperature(baseOptions.getTemperature());
                }
                if (baseOptions.getTopP() != null) {
                    builder.topP(baseOptions.getTopP());
                }
                if (baseOptions.getTopK() != null) {
                    builder.topK(baseOptions.getTopK());
                }
                if (baseOptions.getMaxTokens() != null) {
                    builder.maxTokens(baseOptions.getMaxTokens());
                }
                if (baseOptions.getStopSequences() != null) {
                    builder.stopSequences(baseOptions.getStopSequences());
                }
            }
            builder.toolCallbacks(List.of());
            return builder.build();
        }
        return ChatOptions.builder()
                .model(seed.getModel())
                .temperature(seed.getTemperature())
                .topP(seed.getTopP())
                .topK(seed.getTopK())
                .maxTokens(seed.getMaxTokens())
                .stopSequences(seed.getStopSequences())
                .frequencyPenalty(seed.getFrequencyPenalty())
                .presencePenalty(seed.getPresencePenalty())
                .build();
    }

    private static boolean isThoughtMessage(AssistantMessage msg) {
        if (msg == null || msg.getMetadata() == null) {
            return false;
        }
        return Boolean.TRUE.equals(msg.getMetadata().get("isThought"))
                || Boolean.TRUE.equals(msg.getMetadata().get("thought"));
    }

    private static AssistantMessage extractAssistantMessage(ChatResponse response) {
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            return new AssistantMessage("");
        }
        for (var gen : response.getResults()) {
            if (gen != null && gen.getOutput() != null && gen.getOutput().hasToolCalls()) {
                return gen.getOutput();
            }
        }
        for (var gen : response.getResults()) {
            if (gen != null && gen.getOutput() != null && !isThoughtMessage(gen.getOutput())) {
                return gen.getOutput();
            }
        }
        return response.getResult() != null && response.getResult().getOutput() != null
                ? response.getResult().getOutput()
                : new AssistantMessage("");
    }

    private static String extractNativeThought(ChatResponse response) {
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
            } else if (msg.getMetadata().get("reasoningContent") instanceof String rc
                    && !rc.isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append("\n");
                }
                sb.append(rc.strip());
            }
        }
        return sb.toString();
    }

    private static String ensureJsonToolResponse(String observation) {
        if (observation == null || observation.isBlank()) {
            return "{\"result\":\"\"}";
        }
        String trimmed = observation.strip();
        if ((trimmed.startsWith("{") && trimmed.endsWith("}"))
                || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            try {
                CANONICAL_MAPPER.readTree(trimmed);
                return trimmed;
            } catch (Exception ignored) {
                // 非合法 JSON 时自动包装为 JSON 对象，兼容 GoogleGenAiChatModel 的 FunctionResponse.response 解析
            }
        }
        try {
            return CANONICAL_MAPPER.writeValueAsString(Map.of("result", observation));
        } catch (Exception ex) {
            return "{\"result\":\"tool execution completed\"}";
        }
    }

    /**
     * 生成与 JSON 属性顺序无关的规范化参数指纹（Canonical JSON Fingerprint）：
     *
     * <ul>
     *   <li>将 JSON 解析为 {@link JsonNode} 并递归对所有 {@link ObjectNode} 的 Key 按字典序重排（使 {@code
     *       {"a":1,"b":2}} 与 {@code {"b":2,"a":1}} 100% 映射为同一指纹）；
     *   <li>若规范化字符串超过 {@code 128} 字符，自动转为 SHA-256 十六进制摘要以节省内存；
     *   <li>若输入非合法 JSON，则回退为去空白字符串。
     * </ul>
     */
    public static String normalizeArguments(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return "{}";
        }
        String canonical;
        try {
            JsonNode tree = CANONICAL_MAPPER.readTree(rawJson);
            JsonNode sortedTree = sortJsonNodeKeysRecursively(tree);
            canonical = CANONICAL_MAPPER.writeValueAsString(sortedTree);
        } catch (Exception ex) {
            canonical = stripOutsideQuotesWhitespace(rawJson);
        }
        if (canonical.length() > CANONICAL_HASH_THRESHOLD) {
            return "sha256:" + SemanticMarkdownDocumentSplitter.sha256Hex(canonical);
        }
        return canonical;
    }

    private static JsonNode sortJsonNodeKeysRecursively(JsonNode node) {
        if (node == null) {
            return JsonNodeFactory.instance.nullNode();
        }
        if (node.isObject()) {
            TreeMap<String, JsonNode> sortedFields = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                sortedFields.put(entry.getKey(), sortJsonNodeKeysRecursively(entry.getValue()));
            }
            ObjectNode sortedObj = JsonNodeFactory.instance.objectNode();
            sortedFields.forEach(sortedObj::set);
            return sortedObj;
        }
        if (node.isArray()) {
            ArrayNode sortedArr = JsonNodeFactory.instance.arrayNode(node.size());
            for (JsonNode child : node) {
                sortedArr.add(sortJsonNodeKeysRecursively(child));
            }
            return sortedArr;
        }
        return node;
    }

    private static String stripOutsideQuotesWhitespace(String rawJson) {
        StringBuilder sb = new StringBuilder(rawJson.length());
        boolean inQuotes = false;
        boolean escaped = false;
        for (int i = 0; i < rawJson.length(); i++) {
            char c = rawJson.charAt(i);
            if (inQuotes) {
                sb.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inQuotes = false;
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                    sb.append(c);
                } else if (!Character.isWhitespace(c)) {
                    sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    public GovernedToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    public AgentExecutionBudget getDefaultBudget() {
        return defaultBudget;
    }
}
