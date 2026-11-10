package dev.ppmb.intellihub.agent;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ppmb.intellihub.agent.engine.AgentExecutionBudget;
import dev.ppmb.intellihub.agent.engine.AgentRunResult;
import dev.ppmb.intellihub.agent.engine.AgentStopReason;
import dev.ppmb.intellihub.agent.engine.ReActAgentExecutor;
import dev.ppmb.intellihub.agent.tool.GovernedToolRegistry;
import dev.ppmb.intellihub.agent.tool.KnowledgeBaseSearchTool;
import dev.ppmb.intellihub.agent.tool.ToolApprovalHandler;
import dev.ppmb.intellihub.rag.engine.RagQueryEngine;
import dev.ppmb.intellihub.rag.store.HybridKnowledgeStore;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

class AgentModuleTest {

    static class OrderFinanceTools {
        private final AtomicInteger weatherCalls = new AtomicInteger();
        private final AtomicInteger dangerousDeleteCalls = new AtomicInteger();

        @Tool(
                name = "calculate_refund",
                description = "Calculate refund amount by order total and rate")
        public String calculateRefund(
                @ToolParam(description = "Order total in USD") double orderTotal,
                @ToolParam(description = "Refund rate from 0.0 to 1.0") double rate) {
            return String.format("refund_usd=%.2f", orderTotal * rate);
        }

        @Tool(name = "get_weather", description = "Get current weather by city and unit")
        public String getWeather(
                @ToolParam(description = "City name") String city,
                @ToolParam(description = "Unit C or F") String unit) {
            weatherCalls.incrementAndGet();
            return "Sunny 25" + unit + " in " + city;
        }

        @Tool(name = "delete_production_db", description = "Drop production database")
        public String deleteProductionDb(@ToolParam(description = "DB name") String dbName) {
            dangerousDeleteCalls.incrementAndGet();
            return "Deleted " + dbName;
        }

        @Tool(name = "dump_huge_log", description = "Dump huge server log")
        public String dumpHugeLog() {
            return "X".repeat(5000);
        }
    }

    @Test
    void shouldExecuteMultiStepReActLoopWithKnowledgeBaseAndBusinessTools() {
        HybridKnowledgeStore store = new HybridKnowledgeStore();
        store.add(
                Document.builder()
                        .id("sla-policy")
                        .text(
                                "Enterprise SLA Outage Refund Policy: P0 outage qualifies for a 30% (0.30) refund.")
                        .build());
        RagQueryEngine ragEngine = new RagQueryEngine(store);

        OrderFinanceTools financeTools = new OrderFinanceTools();
        GovernedToolRegistry registry =
                new GovernedToolRegistry()
                        .registerToolObjects(new KnowledgeBaseSearchTool(ragEngine), financeTools);

        ReActAgentExecutor executor = new ReActAgentExecutor(registry);

        AtomicInteger stepCounter = new AtomicInteger();
        ChatModel mockReActModel =
                prompt -> {
                    int step = stepCounter.incrementAndGet();
                    if (step == 1) {
                        AssistantMessage toolCallMsg =
                                AssistantMessage.builder()
                                        .content("<think>First check SLA refund rate in KB</think>")
                                        .toolCalls(
                                                List.of(
                                                        new AssistantMessage.ToolCall(
                                                                "call_1",
                                                                "function",
                                                                KnowledgeBaseSearchTool.TOOL_NAME,
                                                                "{\"query\":\"P0 outage refund rate\",\"topK\":1}")))
                                        .build();
                        return new ChatResponse(List.of(new Generation(toolCallMsg)));
                    }
                    if (step == 2) {
                        AssistantMessage toolCallMsg =
                                AssistantMessage.builder()
                                        .content(
                                                "<think>Found 0.30 rate, now calculate refund for $200</think>")
                                        .toolCalls(
                                                List.of(
                                                        new AssistantMessage.ToolCall(
                                                                "call_2",
                                                                "function",
                                                                "calculate_refund",
                                                                "{\"orderTotal\":200.0,\"rate\":0.30}")))
                                        .build();
                        return new ChatResponse(List.of(new Generation(toolCallMsg)));
                    }
                    return new ChatResponse(
                            List.of(
                                    new Generation(
                                            new AssistantMessage(
                                                    "<think>Done</think>According to the P0 SLA policy (30%), the refund for your $200 order is $60.00."))));
                };

        AgentRunResult result =
                executor.run(
                        mockReActModel, "My $200 order hit a P0 outage, how much is my refund?");

        assertThat(result.isCompletedNormally()).isTrue();
        assertThat(result.stopReason()).isEqualTo(AgentStopReason.COMPLETED);
        assertThat(result.totalIterations()).isEqualTo(3);
        assertThat(result.totalToolCalls()).isEqualTo(2);
        assertThat(result.output())
                .isEqualTo(
                        "According to the P0 SLA policy (30%), the refund for your $200 order is $60.00.");
        assertThat(result.steps()).hasSize(3);
        assertThat(result.steps().get(0).toolCalls().get(0).observation()).contains("30%");
        assertThat(result.steps().get(1).toolCalls().get(0).observation())
                .contains("refund_usd=60.00");
    }

    @Test
    void shouldInterceptKeyOrderPermutedDuplicateToolCallsWithCanonicalJsonLoopGuard() {
        // 验证键序颠倒的嵌套 JSON 也能生成完全一致的 Canonical 指纹
        String fp1 =
                ReActAgentExecutor.normalizeArguments(
                        "{\"b\": {\"y\": 2, \"x\": 1}, \"a\": \"val\"}");
        String fp2 =
                ReActAgentExecutor.normalizeArguments("{\"a\":\"val\",\"b\":{\"x\":1,\"y\":2}}");
        assertThat(fp1).isEqualTo(fp2).isEqualTo("{\"a\":\"val\",\"b\":{\"x\":1,\"y\":2}}");

        OrderFinanceTools tools = new OrderFinanceTools();
        GovernedToolRegistry registry = new GovernedToolRegistry().registerToolObjects(tools);
        AgentExecutionBudget budget =
                AgentExecutionBudget.builder()
                        .maxIterations(5)
                        .maxTotalToolCalls(10)
                        .maxDuplicateToolCalls(1)
                        .build();
        ReActAgentExecutor executor = new ReActAgentExecutor(registry);

        AtomicInteger turn = new AtomicInteger();
        ChatModel loopingModel =
                prompt -> {
                    int t = turn.incrementAndGet();
                    if (t == 1) {
                        // 第 1 次调用：{"city": "Shanghai", "unit": "C"}
                        return new ChatResponse(
                                List.of(
                                        new Generation(
                                                AssistantMessage.builder()
                                                        .content("")
                                                        .toolCalls(
                                                                List.of(
                                                                        new AssistantMessage
                                                                                .ToolCall(
                                                                                "c1",
                                                                                "function",
                                                                                "get_weather",
                                                                                "{\"city\": \"Shanghai\", \"unit\": \"C\"}")))
                                                        .build())));
                    }
                    if (t == 2) {
                        // 第 2 次将 JSON 键序颠倒：{"unit":"C","city":"Shanghai"}，仍应被 Canonical 指纹识别并拦截
                        return new ChatResponse(
                                List.of(
                                        new Generation(
                                                AssistantMessage.builder()
                                                        .content("")
                                                        .toolCalls(
                                                                List.of(
                                                                        new AssistantMessage
                                                                                .ToolCall(
                                                                                "c2",
                                                                                "function",
                                                                                "get_weather",
                                                                                "{\"unit\":\"C\",\"city\":\"Shanghai\"}")))
                                                        .build())));
                    }
                    return new ChatResponse(
                            List.of(
                                    new Generation(
                                            new AssistantMessage("Shanghai is Sunny 25C."))));
                };

        AgentRunResult result = executor.run(loopingModel, "Weather in Shanghai?", budget);

        assertThat(result.stopReason()).isEqualTo(AgentStopReason.COMPLETED);
        assertThat(tools.weatherCalls.get()).isEqualTo(1);
        assertThat(result.steps().get(1).toolCalls().get(0).loopBlocked()).isTrue();
        assertThat(result.steps().get(1).toolCalls().get(0).observation()).contains("[LOOP_GUARD]");
    }

    @Test
    void shouldSupportNonBlockingHumanApprovalCheckpointingAndResume() {
        OrderFinanceTools tools = new OrderFinanceTools();
        GovernedToolRegistry registry =
                new GovernedToolRegistry()
                        .registerToolObjects(tools)
                        .markHighRisk("delete_production_db")
                        .setApprovalHandler((name, args, ctx) ->
                                ToolApprovalHandler.ApprovalDecision.awaitHumanApproval(
                                        "ticket-dba-001", "Requires DBA human approval"));

        ReActAgentExecutor executor = new ReActAgentExecutor(registry);

        ChatModel checkpointModel =
                prompt -> {
                    boolean hasToolResult =
                            prompt.getInstructions().stream()
                                    .anyMatch(ToolResponseMessage.class::isInstance);
                    if (!hasToolResult) {
                        return new ChatResponse(
                                List.of(
                                        new Generation(
                                                AssistantMessage.builder()
                                                        .content("<think>Need to drop db</think>")
                                                        .toolCalls(
                                                                List.of(
                                                                        new AssistantMessage
                                                                                .ToolCall(
                                                                                "call_del_1",
                                                                                "function",
                                                                                "delete_production_db",
                                                                                "{\"dbName\":\"staging_db\"}")))
                                                        .build())));
                    }
                    return new ChatResponse(
                            List.of(
                                    new Generation(
                                            new AssistantMessage(
                                                    "Database staging_db was deleted after DBA approval."))));
                };

        // 1. 首次运行遇到高风险工具，立即返回 AWAITING_APPROVAL 并保存 AgentCheckpoint，不阻塞线程且不执行删除
        AgentRunResult suspended = executor.run(checkpointModel, "Drop staging_db");
        assertThat(suspended.isAwaitingApproval()).isTrue();
        assertThat(suspended.stopReason()).isEqualTo(AgentStopReason.AWAITING_APPROVAL);
        assertThat(suspended.checkpoint()).isNotNull();
        assertThat(suspended.checkpoint().approvalTicketId()).isEqualTo("ticket-dba-001");
        assertThat(tools.dangerousDeleteCalls.get()).isZero();

        // 2. 人工在 Web 端点击“批准”后调用 resume(...)，恢复上下文并真正执行工具完成闭环
        AgentRunResult resumed =
                executor.resume(
                        checkpointModel,
                        suspended.checkpoint().checkpointId(),
                        true,
                        "Approved by DBA",
                        Map.of(),
                        AgentExecutionBudget.defaults());

        assertThat(resumed.stopReason()).isEqualTo(AgentStopReason.COMPLETED);
        assertThat(tools.dangerousDeleteCalls.get()).isEqualTo(1);
        assertThat(resumed.output())
                .isEqualTo("Database staging_db was deleted after DBA approval.");
    }

    @Test
    void shouldTruncateWithPaginationGuidanceEnforceTokenBudgetAndAlignParallelIds() {
        OrderFinanceTools tools = new OrderFinanceTools();
        GovernedToolRegistry registry = new GovernedToolRegistry(300).registerToolObjects(tools);

        // 1. 验证超长工具输出自动截断并附带缩小过滤/分页引导语
        GovernedToolRegistry.ToolInvocationOutcome truncated =
                registry.executeTool("dump_huge_log", "{}", Map.of());
        assertThat(truncated.success()).isTrue();
        assertThat(truncated.observation())
                .contains("[TRUNCATED totalChars=")
                .contains("Please narrow down your query filters or use pagination parameters");
        assertThat(truncated.observation().length()).isLessThan(460);

        // 2. 验证累计 Token 预算（maxTotalTokens=500）超限时停止工具执行并触发收尾总结
        AgentExecutionBudget tokenTightBudget =
                AgentExecutionBudget.builder()
                        .maxIterations(5)
                        .maxTotalToolCalls(5)
                        .maxTotalTokens(500L)
                        .build();
        ReActAgentExecutor executor = new ReActAgentExecutor(registry);

        AtomicInteger tokenModelCalls = new AtomicInteger();
        ChatModel highTokenModel =
                prompt -> {
                    int c = tokenModelCalls.incrementAndGet();
                    ChatResponseMetadata meta =
                            ChatResponseMetadata.builder()
                                    .usage(new DefaultUsage(400, 200, 600))
                                    .build();
                    if (c == 1) {
                        return new ChatResponse(
                                List.of(
                                        new Generation(
                                                AssistantMessage.builder()
                                                        .content("")
                                                        .toolCalls(
                                                                List.of(
                                                                        new AssistantMessage
                                                                                .ToolCall(
                                                                                "p1",
                                                                                "function",
                                                                                "get_weather",
                                                                                "{\"city\":\"Beijing\",\"unit\":\"C\"}"),
                                                                        new AssistantMessage
                                                                                .ToolCall(
                                                                                "p2",
                                                                                "function",
                                                                                "get_weather",
                                                                                "{\"city\":\"Shenzhen\",\"unit\":\"C\"}")))
                                                        .build())),
                                meta);
                    }
                    // 验证并行 ToolCall 即使因 Token 预算超限未执行，ToolResponseMessage 仍保留完整的 p1、p2 ID
                    List<Message> msgs = prompt.getInstructions();
                    ToolResponseMessage toolRespMsg =
                            msgs.stream()
                                    .filter(ToolResponseMessage.class::isInstance)
                                    .map(ToolResponseMessage.class::cast)
                                    .findFirst()
                                    .orElseThrow();
                    assertThat(toolRespMsg.getResponses())
                            .extracting(ToolResponseMessage.ToolResponse::id)
                            .containsExactly("p1", "p2");
                    return new ChatResponse(
                            List.of(
                                    new Generation(
                                            new AssistantMessage(
                                                    "Synthesized after token budget limit."))),
                            meta);
                };

        AgentRunResult tokenResult =
                executor.run(
                        highTokenModel,
                        new Prompt("Check weather"),
                        null,
                        Map.of(),
                        tokenTightBudget);

        assertThat(tokenResult.stopReason()).isEqualTo(AgentStopReason.MAX_TOKENS_REACHED);
        assertThat(tokenResult.totalTokens()).isEqualTo(1200L);
        assertThat(tokenResult.output()).isEqualTo("Synthesized after token budget limit.");
    }
}
