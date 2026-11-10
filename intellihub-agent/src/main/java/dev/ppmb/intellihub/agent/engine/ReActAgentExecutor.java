package dev.ppmb.intellihub.agent.engine;

import dev.ppmb.intellihub.agent.tool.GovernedToolRegistry;
import dev.ppmb.intellihub.core.clean.AiOutputUtil;
import dev.ppmb.intellihub.core.exception.IntelliHubExceptionUtil;
import dev.ppmb.intellihub.prompt.render.SafePromptRenderer;
import dev.ppmb.intellihub.prompt.template.PromptTemplateSpec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
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
 *   <li><b>防死循环指纹拦截（Duplicate Call Loop Guard）</b>：对每次工具调用计算规范化 {@code toolName::normalizedJsonArgs}
 *       指纹，当同一工具以相同参数重复调用超过 {@link AgentExecutionBudget#maxDuplicateToolCalls()} 时，立即阻断实际调用并向模型返回
 *       {@code [LOOP_GUARD]} 警告，迫使模型收敛；
 *   <li><b>协议完备的多工具并行对齐（ToolCall ID Alignment）</b>：即便在单步并行工具调用中途触发 {@code maxTotalToolCalls}
 *       上限，也会为剩余每个 {@code ToolCall} 补齐对应的 {@code ToolResponse}，严防因 {@code tool_call_id} 缺失导致上游模型报
 *       HTTP 400；
 *   <li><b>预算耗尽优雅收尾（Graceful Final Synthesis）</b>：达到最大轮次或工具调用上限后，自动剥离工具列表发起最后一次总结推理，确保始终向用户返回连贯的最终回答；
 *   <li><b>全链路可观测轨迹（{@link AgentStepTrace}）</b>：完整记录每一轮思考链、每个工具的入参、观察输出、耗时与拦截状态。
 * </ul>
 */
public class ReActAgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(ReActAgentExecutor.class);

    private final GovernedToolRegistry toolRegistry;
    private final SafePromptRenderer promptRenderer;
    private final AgentExecutionBudget defaultBudget;

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
        Map<String, Object> safeToolContext = toolContext != null ? toolContext : Map.of();

        List<ToolCallback> activeTools = toolRegistry.resolveToolCallbacks(enabledToolNames);
        ChatOptions loopOptions =
                buildToolAwareOptions(initialPrompt.getOptions(), activeTools, safeToolContext);

        List<Message> conversation = new ArrayList<>(initialPrompt.getInstructions());
        List<AgentStepTrace> stepTraces = new ArrayList<>();
        Map<String, Integer> callFingerprintCounts = new HashMap<>();

        long startWallMs = System.currentTimeMillis();
        long deadlineMs = startWallMs + effectiveBudget.maxExecutionDuration().toMillis();
        int totalToolCalls = 0;
        AgentStopReason stopReason = AgentStopReason.MAX_ITERATIONS_REACHED;

        for (int iteration = 1; iteration <= effectiveBudget.maxIterations(); iteration++) {
            if (System.currentTimeMillis() > deadlineMs) {
                log.warn(
                        "[ReActAgentExecutor] 执行超时（超过 {}ms），提前终止 ReAct 循环",
                        effectiveBudget.maxExecutionDuration().toMillis());
                long totalCost = System.currentTimeMillis() - startWallMs;
                return new AgentRunResult(
                        "Agent 执行已达最大超时限制，请缩小任务范围后重试。",
                        AgentStopReason.TIMEOUT,
                        stepTraces,
                        iteration - 1,
                        totalToolCalls,
                        totalCost);
            }

            long stepStart = System.currentTimeMillis();
            ChatResponse response = chatModel.call(new Prompt(conversation, loopOptions));
            AssistantMessage assistantMsg = extractAssistantMessage(response);
            String rawText = assistantMsg.getText() == null ? "" : assistantMsg.getText();
            String extractedThink = AiOutputUtil.extractThink(rawText);
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
                        iteration,
                        totalToolCalls,
                        totalCost);
            }

            // 模型发起了 1 个或多个工具调用
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

                // 检查总工具调用次数预算（仍需生成占位 ToolResponse 以保证 tool_call_id 协议对齐）
                if (totalToolCalls >= effectiveBudget.maxTotalToolCalls()) {
                    toolBudgetReached = true;
                    String budgetMsg =
                            "[BUDGET_EXCEEDED] Maximum total tool calls ("
                                    + effectiveBudget.maxTotalToolCalls()
                                    + ") reached. Synthesize your final answer from existing observations.";
                    toolResponses.add(
                            new ToolResponseMessage.ToolResponse(callId, toolName, budgetMsg));
                    toolRecords.add(
                            new AgentStepTrace.ToolCallExecutionRecord(
                                    callId, toolName, argsJson, budgetMsg, false, false, false,
                                    0L));
                    continue;
                }

                totalToolCalls++;
                String fingerprint = toolName + "::" + normalizeArguments(argsJson);
                int seenCount = callFingerprintCounts.merge(fingerprint, 1, Integer::sum);

                // 防死循环守卫：相同工具 + 相同参数调用超过上限时直接拦截
                if (seenCount > effectiveBudget.maxDuplicateToolCalls()) {
                    log.warn(
                            "[ReActAgentExecutor] 触发防死循环拦截 [LOOP_GUARD]: tool={}, args={}, count={}",
                            toolName,
                            argsJson,
                            seenCount);
                    String loopObs =
                            "[LOOP_GUARD] Duplicate tool call blocked: tool '"
                                    + toolName
                                    + "' with identical arguments has already been called "
                                    + (seenCount - 1)
                                    + " time(s). Do NOT call it again with the same arguments; use previous observations to formulate your answer.";
                    toolResponses.add(
                            new ToolResponseMessage.ToolResponse(callId, toolName, loopObs));
                    toolRecords.add(
                            new AgentStepTrace.ToolCallExecutionRecord(
                                    callId, toolName, argsJson, loopObs, false, true, false, 0L));
                    continue;
                }

                GovernedToolRegistry.ToolInvocationOutcome outcome =
                        toolRegistry.executeTool(toolName, argsJson, safeToolContext);
                toolResponses.add(
                        new ToolResponseMessage.ToolResponse(
                                callId, toolName, outcome.observation()));
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

            if (toolBudgetReached || totalToolCalls >= effectiveBudget.maxTotalToolCalls()) {
                stopReason = AgentStopReason.MAX_TOOL_CALLS_REACHED;
                break;
            }
        }

        // 达到最大轮次或最大工具调用次数后，剥离工具回调发起一次收尾总结生成
        String synthesized =
                synthesizeFinalAnswer(chatModel, conversation, initialPrompt.getOptions());
        long totalCost = System.currentTimeMillis() - startWallMs;
        return new AgentRunResult(
                synthesized, stopReason, stepTraces, stepTraces.size(), totalToolCalls, totalCost);
    }

    private static String synthesizeFinalAnswer(
            ChatModel chatModel, List<Message> conversation, ChatOptions baseOptions) {
        List<Message> finalMessages = new ArrayList<>(conversation);
        finalMessages.add(
                new UserMessage(
                        "Tool execution budget has been reached. Based on all observations gathered above, please provide your best final answer directly without invoking any tools."));
        ChatOptions textOnlyOptions = stripToolsFromOptions(baseOptions);
        ChatResponse finalResponse = chatModel.call(new Prompt(finalMessages, textOnlyOptions));
        AssistantMessage msg = extractAssistantMessage(finalResponse);
        return AiOutputUtil.stripThink(msg.getText() == null ? "" : msg.getText());
    }

    private static ChatOptions buildToolAwareOptions(
            ChatOptions baseOptions,
            List<ToolCallback> callbacks,
            Map<String, Object> toolContext) {
        ToolCallingChatOptions.Builder<?> builder = ToolCallingChatOptions.builder();
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

    private static ChatOptions stripToolsFromOptions(ChatOptions baseOptions) {
        if (baseOptions == null) {
            return null;
        }
        return ChatOptions.builder()
                .model(baseOptions.getModel())
                .temperature(baseOptions.getTemperature())
                .topP(baseOptions.getTopP())
                .topK(baseOptions.getTopK())
                .maxTokens(baseOptions.getMaxTokens())
                .stopSequences(baseOptions.getStopSequences())
                .frequencyPenalty(baseOptions.getFrequencyPenalty())
                .presencePenalty(baseOptions.getPresencePenalty())
                .build();
    }

    private static AssistantMessage extractAssistantMessage(ChatResponse response) {
        if (response == null
                || response.getResult() == null
                || response.getResult().getOutput() == null) {
            return new AssistantMessage("");
        }
        return response.getResult().getOutput();
    }

    /**
     * 规范化 JSON 参数空白字符（忽略空格与换行差异，使 {@code {"a": 1}} 与 {@code {"a":1}} 命中同一防死循环指纹）。
     */
    static String normalizeArguments(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return "{}";
        }
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
