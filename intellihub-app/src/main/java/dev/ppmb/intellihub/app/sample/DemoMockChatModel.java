package dev.ppmb.intellihub.app.sample;

import dev.ppmb.intellihub.agent.tool.KnowledgeBaseSearchTool;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

/**
 * 开箱即用的内置离线联调演示模型（{@code demo-mock}）。
 *
 * <p>无需配置任何外部公网 API Key，即可在前端控制台完整演示：
 *
 * <ul>
 *   <li>同步与 SSE 流式对话（含 {@code <think>} 深度思考过程与 Token 计量）；
 *   <li>容错型结构化 JSON 抽取；
 *   <li>带 {@code [#1]} 角标溯源的 RAG 知识库问答；
 *   <li>多步 ReAct 工具调用（服务器时间、税率计算、知识库检索）及高风险退款工具的人工审批挂起与恢复闭环。
 * </ul>
 */
public class DemoMockChatModel implements ChatModel {

    public static final String CHANNEL_NAME = "demo-mock";
    private static final String MODEL_ID = "intellihub-demo-r1";

    @Override
    public ChatResponse call(Prompt prompt) {
        List<Message> messages = prompt != null ? prompt.getInstructions() : List.of();
        ChatOptions options = prompt != null ? prompt.getOptions() : null;

        // 1. 判断是否为挂载了工具回调的 ReAct Agent 推理阶段
        if (options instanceof ToolCallingChatOptions toolOpts
                && toolOpts.getToolCallbacks() != null
                && !toolOpts.getToolCallbacks().isEmpty()) {
            return handleAgentTurn(messages);
        }

        String lastUserText = extractLastUserText(messages);

        // 2. 判断是否为结构化输出提取请求
        if (lastUserText.contains("请从以下输入内容中精准提取关键结构化信息")
                || lastUserText.contains("Respond ONLY with valid JSON")) {
            String jsonReply =
                    "<think>正在解析非结构化文本，提取关键业务实体并校验 JSON 语法完整性...</think>\n"
                            + "```json\n"
                            + "{\n"
                            + "  \"客户姓名\": \"李明\",\n"
                            + "  \"联系电话\": \"138-0013-8000\",\n"
                            + "  \"咨询产品\": \"IntelliHub 企业级 AI 中台\",\n"
                            + "  \"诉求分类\": \"企业版私有化部署与高可用集群授权\",\n"
                            + "  \"紧急程度\": \"P0-高优\",\n"
                            + "  \"预计预算\": \"50,000 元\"\n"
                            + "}\n"
                            + "```";
            return buildTextResponse(jsonReply, 68, 92);
        }

        // 3. 判断是否为 RAG 知识库增强问答请求
        if (lastUserText.contains("【参考知识库切片】")) {
            String ragReply =
                    "<think>正在交叉比对【参考知识库切片】与用户提问，严格遵循事实约束并标注引用角标 [#1]...</think>"
                            + "根据当前知识库检索到的参考切片，核心结论如下：\n\n"
                            + "1. **高可用路由与故障隔离机制**：`RoutingChatModel` 与 `KeyPoolChatModel` 支持 `FAILOVER`、`ROUND_ROBIN` 与 `WEIGHTED` 路由策略。当遇到 `401/403` 凭证鉴权失败时，节点或 Key 会直接进入 `DEAD` 状态永久摘除；当遇到 `429` 限流或网络超时则进入 `COOLDOWN` 短期冷却，冷却期满后通过 CAS 单请求半开探活恢复 [#1]。\n"
                            + "2. **ReAct 智能体防死循环与人工审批**：`ReActAgentExecutor` 通过字典序重排生成键序无关的规范化 JSON 指纹（Canonical JSON Loop Fingerprint）拦截重复工具调用，并支持高风险工具 `AWAITING_APPROVAL` 非阻塞挂起与 `resume` 恢复 [#1]。";
            return buildTextResponse(ragReply, 145, 160);
        }

        // 4. 通用对话补全响应
        String cleanUserQuestion = lastUserText.isBlank() ? "你好" : lastUserText.strip();
        String chatReply =
                "<think>收到用户提问：「"
                        + cleanUserQuestion
                        + "」\n1. 当前使用内置免密通道 demo-mock 响应\n2. 检索系统上下文并生成结构化回复</think>"
                        + "你好！我是 **IntelliHub 内置联调模型 (`demo-mock`)**。\n\n"
                        + "针对你的问题 **「"
                        + cleanUserQuestion
                        + "」**，当前系统各核心引擎运行状态如下：\n\n"
                        + "- **LLM 路由层 (`intellihub-llm`)**：支持多 Key 轮询池、主备故障转移与首包流式保护\n"
                        + "- **Prompt 治理层 (`intellihub-prompt`)**：支持多版本模板注册、XML 边界包裹防越狱与残缺 JSON 自动修复\n"
                        + "- **Hybrid RAG 引擎 (`intellihub-rag`)**：支持 Markdown 标题树感知切分、Vector + BM25 双路检索与 RRF 融合重排\n"
                        + "- **ReAct Agent 引擎 (`intellihub-agent`)**：支持 Canonical JSON 防死循环守卫与高风险工具人工审批断点恢复";
        return buildTextResponse(chatReply, 42, 128);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        ChatResponse fullResponse = call(prompt);
        String fullText =
                fullResponse.getResult() != null && fullResponse.getResult().getOutput() != null
                        ? fullResponse.getResult().getOutput().getText()
                        : "";
        List<String> chunks = splitIntoStreamChunks(fullText, 18);
        return Flux.fromIterable(chunks)
                .delayElements(Duration.ofMillis(25))
                .map(piece ->
                        new ChatResponse(List.of(new Generation(new AssistantMessage(piece)))));
    }

    private ChatResponse handleAgentTurn(List<Message> messages) {
        List<ToolResponseMessage> toolResponses =
                messages.stream()
                        .filter(ToolResponseMessage.class::isInstance)
                        .map(ToolResponseMessage.class::cast)
                        .toList();

        // 若已经执行过工具调用，汇总工具返回的观察结果生成自然流畅的对话回复
        if (!toolResponses.isEmpty()) {
            StringBuilder naturalAnswer = new StringBuilder();
            boolean rejected = false;
            for (ToolResponseMessage trm : toolResponses) {
                for (ToolResponseMessage.ToolResponse resp : trm.getResponses()) {
                    String toolName = resp.name();
                    String data = resp.responseData() != null ? resp.responseData() : "";
                    if (data.contains("HUMAN_APPROVAL_REJECTED")
                            || data.contains("APPROVAL_REJECTED")) {
                        rejected = true;
                    }
                    if (SampleSystemTools.TOOL_SERVER_TIME.equals(toolName)) {
                        naturalAnswer
                                .append("已通过系统时钟工具查询确认，当前服务器标准时间为：**")
                                .append(data)
                                .append("**。\n");
                    } else if (SampleSystemTools.TOOL_CALCULATE_TAX.equals(toolName)) {
                        naturalAnswer.append("已完成税务核算，明细如下：\n- ").append(data).append("\n");
                    } else if (SampleSystemTools.TOOL_REFUND_TRANSFER.equals(toolName)) {
                        if (rejected) {
                            naturalAnswer
                                    .append("⚠️ **高风险退款操作已被审批人拒绝拦截**，资金未发生划转。\n- 审计记录：`")
                                    .append(data)
                                    .append("`\n");
                        } else {
                            naturalAnswer
                                    .append("✅ **人工审批已通过，高风险售后退款打款执行成功！**\n- 交易回执：`")
                                    .append(data)
                                    .append("`\n");
                        }
                    } else if (KnowledgeBaseSearchTool.TOOL_NAME.equals(toolName)) {
                        naturalAnswer
                                .append("我查阅了企业知识库中的权威技术规范，为您整理核心结论如下：\n\n")
                                .append(data)
                                .append("\n");
                    } else {
                        naturalAnswer
                                .append("- 工具 `")
                                .append(toolName)
                                .append("` 返回：`")
                                .append(data)
                                .append("`\n");
                    }
                }
            }
            String thinkText =
                    rejected
                            ? "<think>检测到高风险工具调用被人工审批人拒绝，立即终止敏感操作并向用户反馈原因。</think>"
                            : "<think>已收到工具调用返回结果，正在将结构化观察结果转化为自然语言回答...</think>";
            return buildTextResponse(thinkText + naturalAnswer.toString().strip(), 95, 88);
        }

        // 首轮推理：根据用户意图智能决定是否发起 ToolCall，若为普通寒暄则直接自然对话回答
        String rawUserText = extractLastUserText(messages);
        String goal = extractCoreUserQuestion(rawUserText);
        String lowerGoal = goal.toLowerCase(Locale.ROOT);

        if (lowerGoal.contains("退款")
                || lowerGoal.contains("refund")
                || lowerGoal.contains("打款")
                || lowerGoal.contains("ord-")) {
            AssistantMessage toolCallMsg =
                    AssistantMessage.builder()
                            .content(
                                    "<think>识别到售后订单资金退回诉求，需调用高风险工具 execute_refund_transfer（将触发人工审批门禁）...</think>")
                            .toolCalls(
                                    List.of(
                                            new AssistantMessage.ToolCall(
                                                    "call_refund_demo_1",
                                                    "function",
                                                    SampleSystemTools.TOOL_REFUND_TRANSFER,
                                                    "{\"orderId\":\"ORD-20261110\",\"refundAmount\":299.00}")))
                            .build();
            return new ChatResponse(
                    List.of(new Generation(toolCallMsg)),
                    ChatResponseMetadata.builder()
                            .model(MODEL_ID)
                            .usage(new DefaultUsage(56, 34, 90))
                            .build());
        }

        if (lowerGoal.contains("税")
                || lowerGoal.contains("tax")
                || lowerGoal.contains("发票")
                || lowerGoal.contains("金额")) {
            AssistantMessage toolCallMsg =
                    AssistantMessage.builder()
                            .content("<think>用户需要计算含税总额与税额，准备调用 calculate_tax 工具...</think>")
                            .toolCalls(
                                    List.of(
                                            new AssistantMessage.ToolCall(
                                                    "call_tax_demo_1",
                                                    "function",
                                                    SampleSystemTools.TOOL_CALCULATE_TAX,
                                                    "{\"amount\":1000.0,\"taxRatePercent\":13.0}")))
                            .build();
            return new ChatResponse(
                    List.of(new Generation(toolCallMsg)),
                    ChatResponseMetadata.builder()
                            .model(MODEL_ID)
                            .usage(new DefaultUsage(48, 28, 76))
                            .build());
        }

        if (lowerGoal.contains("时间")
                || lowerGoal.contains("日期")
                || lowerGoal.contains("几点")
                || lowerGoal.contains("time")) {
            AssistantMessage toolCallMsg =
                    AssistantMessage.builder()
                            .content(
                                    "<think>用户询问当前系统服务器时间，调用 get_current_server_time 工具...</think>")
                            .toolCalls(
                                    List.of(
                                            new AssistantMessage.ToolCall(
                                                    "call_time_demo_1",
                                                    "function",
                                                    SampleSystemTools.TOOL_SERVER_TIME,
                                                    "{}")))
                            .build();
            return new ChatResponse(
                    List.of(new Generation(toolCallMsg)),
                    ChatResponseMetadata.builder()
                            .model(MODEL_ID)
                            .usage(new DefaultUsage(40, 22, 62))
                            .build());
        }

        if (lowerGoal.contains("路由")
                || lowerGoal.contains("401")
                || lowerGoal.contains("403")
                || lowerGoal.contains("429")
                || lowerGoal.contains("死循环")
                || lowerGoal.contains("知识库")
                || lowerGoal.contains("rag")
                || lowerGoal.contains("keypool")
                || lowerGoal.contains("sla")
                || lowerGoal.contains("赔偿")) {
            String safeQuery = goal.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
            AssistantMessage toolCallMsg =
                    AssistantMessage.builder()
                            .content(
                                    "<think>该问题涉及企业架构或业务规范，调用 search_knowledge_base 工具检索权威切片...</think>")
                            .toolCalls(
                                    List.of(
                                            new AssistantMessage.ToolCall(
                                                    "call_kb_demo_1",
                                                    "function",
                                                    KnowledgeBaseSearchTool.TOOL_NAME,
                                                    "{\"query\":\""
                                                            + safeQuery
                                                            + "\",\"topK\":2}")))
                            .build();
            return new ChatResponse(
                    List.of(new Generation(toolCallMsg)),
                    ChatResponseMetadata.builder()
                            .model(MODEL_ID)
                            .usage(new DefaultUsage(52, 30, 82))
                            .build());
        }

        if (rawUserText.contains("【参考知识库切片】")) {
            String ragReply =
                    "<think>正在交叉比对【参考知识库切片】与用户提问，严格遵循事实约束并标注引用角标 [#1]...</think>"
                            + "根据当前知识库检索到的参考切片，为您解答如下：\n\n"
                            + "1. **高可用路由与故障隔离机制**：`RoutingChatModel` 与 `KeyPoolChatModel` 支持 `FAILOVER`、`ROUND_ROBIN` 与 `WEIGHTED` 路由策略。当遇到 `401/403` 凭证鉴权失败时，节点或 Key 会直接进入 `DEAD` 状态永久摘除；当遇到 `429` 限流或网络超时则进入 `COOLDOWN` 短期冷却，冷却期满后通过 CAS 单请求半开探活恢复 [#1]。\n"
                            + "2. **ReAct 智能体防死循环与人工审批**：`ReActAgentExecutor` 通过字典序重排生成键序无关的规范化 JSON 指纹（Canonical JSON Loop Fingerprint）拦截重复工具调用，并支持高风险工具 `AWAITING_APPROVAL` 非阻塞挂起与 `resume` 恢复 [#1]。";
            return buildTextResponse(ragReply, 145, 160);
        }

        // 普通对话无需调用外部工具，直接自然回复
        String cleanUserQuestion = goal.isBlank() ? "你好" : goal.strip();
        String directReply =
                "<think>分析用户消息：「"
                        + cleanUserQuestion
                        + "」——属于常规对话咨询，无需触发外部工具，直接给出解答。</think>"
                        + "你好！我是 **IntelliHub 智能助手**。你当前可以在这一个对话窗口中直接体验所有能力：\n\n"
                        + "1. **直接查时间 / 算税费**：例如发 *「现在服务器几点了？」* 或 *「帮我计算 1000 元 13% 增值税」*\n"
                        + "2. **高风险工具人工审批**：例如发 *「客户订单 ORD-20261110 商品破损，请立即退回 299 元」*，会在气泡内直接弹出审批卡片\n"
                        + "3. **知识库溯源问答**：开启下方「📚 知识库增强」或直接问 *「401 鉴权失败的 Key 会怎么处理？」*\n"
                        + "4. **自然语言结构化提取**：开启下方「🧬 结构化提取」，发一段客户留言即可在回复中生成结构化数据表";
        return buildTextResponse(directReply, 45, 110);
    }

    private static String extractCoreUserQuestion(String rawUserText) {
        if (rawUserText == null || rawUserText.isBlank()) {
            return "";
        }
        String marker = "【用户问题】\n";
        int idx = rawUserText.lastIndexOf(marker);
        if (idx >= 0) {
            return rawUserText.substring(idx + marker.length()).strip();
        }
        return rawUserText.strip();
    }

    private static String extractLastUserText(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if (msg != null && msg.getText() != null && !msg.getText().isBlank()) {
                return msg.getText();
            }
        }
        return "";
    }

    private static ChatResponse buildTextResponse(
            String text, int promptTokens, int completionTokens) {
        ChatResponseMetadata metadata =
                ChatResponseMetadata.builder()
                        .model(MODEL_ID)
                        .usage(
                                new DefaultUsage(
                                        promptTokens,
                                        completionTokens,
                                        promptTokens + completionTokens))
                        .build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))), metadata);
    }

    private static List<String> splitIntoStreamChunks(String text, int chunkSize) {
        if (text == null || text.isEmpty()) {
            return List.of("");
        }
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < text.length(); i += chunkSize) {
            parts.add(text.substring(i, Math.min(text.length(), i + chunkSize)));
        }
        return parts;
    }
}
