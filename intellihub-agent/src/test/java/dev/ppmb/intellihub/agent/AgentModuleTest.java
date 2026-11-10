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

        @Tool(name = "get_weather", description = "Get current weather by city")
        public String getWeather(@ToolParam(description = "City name") String city) {
            weatherCalls.incrementAndGet();
            return "Sunny 25C in " + city;
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
    void shouldInterceptDuplicateToolCallInfiniteLoopsWithLoopGuard() {
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
                        // 第 1 次调用 {"city": "Shanghai"}
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
                                                                                "{\"city\": \"Shanghai\"}")))
                                                        .build())));
                    }
                    if (t == 2) {
                        // 第 2 次以不同空格格式重复调用同一参数 {"city":"Shanghai"}，应触发 [LOOP_GUARD]
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
                                                                                "{\"city\":\"Shanghai\"}")))
                                                        .build())));
                    }
                    return new ChatResponse(
                            List.of(
                                    new Generation(
                                            new AssistantMessage("Shanghai is Sunny 25C."))));
                };

        AgentRunResult result = executor.run(loopingModel, "Weather in Shanghai?", budget);

        assertThat(result.stopReason()).isEqualTo(AgentStopReason.COMPLETED);
        // 底层 Java 方法仅真正执行了 1 次，第 2 次被防死循环守卫拦截
        assertThat(tools.weatherCalls.get()).isEqualTo(1);
        assertThat(result.steps().get(1).toolCalls().get(0).loopBlocked()).isTrue();
        assertThat(result.steps().get(1).toolCalls().get(0).observation()).contains("[LOOP_GUARD]");
    }

    @Test
    void shouldEnforceHighRiskApprovalGateTruncateOversizedOutputAndAlignParallelBudgetIds() {
        OrderFinanceTools tools = new OrderFinanceTools();
        GovernedToolRegistry registry =
                new GovernedToolRegistry(300)
                        .registerToolObjects(tools)
                        .markHighRisk("delete_production_db")
                        .setApprovalHandler((name, args, ctx) ->
                                Boolean.TRUE.equals(ctx.get("admin_approved"))
                                        ? ToolApprovalHandler.ApprovalDecision.allow()
                                        : ToolApprovalHandler.ApprovalDecision.reject(
                                                "Missing admin_approved token"));

        // 1. 验证高风险工具未授权时被拒绝且不触发底层方法
        GovernedToolRegistry.ToolInvocationOutcome rejected =
                registry.executeTool("delete_production_db", "{\"dbName\":\"prod\"}", Map.of());
        assertThat(rejected.approvalRejected()).isTrue();
        assertThat(rejected.observation()).contains("APPROVAL_REJECTED");
        assertThat(tools.dangerousDeleteCalls.get()).isZero();

        // 2. 验证超长工具输出自动截断
        GovernedToolRegistry.ToolInvocationOutcome truncated =
                registry.executeTool("dump_huge_log", "{}", Map.of());
        assertThat(truncated.success()).isTrue();
        assertThat(truncated.observation()).contains("[TRUNCATED totalChars=");
        assertThat(truncated.observation().length()).isLessThan(350);

        // 3. 验证单步并行 ToolCall 在达到 maxTotalToolCalls=1 时仍补齐全部 tool_call_id 并触发优雅收尾总结
        AgentExecutionBudget tightBudget =
                AgentExecutionBudget.builder()
                        .maxIterations(4)
                        .maxTotalToolCalls(1)
                        .maxDuplicateToolCalls(2)
                        .build();
        ReActAgentExecutor executor = new ReActAgentExecutor(registry);

        AtomicInteger modelCalls = new AtomicInteger();
        ChatModel parallelModel =
                prompt -> {
                    int call = modelCalls.incrementAndGet();
                    if (call == 1) {
                        return new ChatResponse(
                                List.of(
                                        new Generation(
                                                AssistantMessage.builder()
                                                        .content("")
                                                        .toolCalls(
                                                                List.of(
                                                                        new AssistantMessage
                                                                                .ToolCall(
                                                                                "parallel_1",
                                                                                "function",
                                                                                "get_weather",
                                                                                "{\"city\":\"Beijing\"}"),
                                                                        new AssistantMessage
                                                                                .ToolCall(
                                                                                "parallel_2",
                                                                                "function",
                                                                                "get_weather",
                                                                                "{\"city\":\"Shenzhen\"}")))
                                                        .build())));
                    }
                    // 验证收尾总结请求中 ToolResponseMessage 依然完整包含 2 个 ToolResponse ID，保证协议不报 400
                    List<Message> msgs = prompt.getInstructions();
                    ToolResponseMessage toolRespMsg =
                            msgs.stream()
                                    .filter(ToolResponseMessage.class::isInstance)
                                    .map(ToolResponseMessage.class::cast)
                                    .findFirst()
                                    .orElseThrow();
                    assertThat(toolRespMsg.getResponses())
                            .extracting(ToolResponseMessage.ToolResponse::id)
                            .containsExactly("parallel_1", "parallel_2");
                    return new ChatResponse(
                            List.of(
                                    new Generation(
                                            new AssistantMessage(
                                                    "Synthesized after budget limit: Beijing is Sunny 25C."))));
                };

        AgentRunResult runResult =
                executor.run(
                        parallelModel,
                        new Prompt("Check Beijing and Shenzhen"),
                        null,
                        Map.of(),
                        tightBudget);

        assertThat(runResult.stopReason()).isEqualTo(AgentStopReason.MAX_TOOL_CALLS_REACHED);
        assertThat(runResult.totalToolCalls()).isEqualTo(1);
        assertThat(runResult.output())
                .isEqualTo("Synthesized after budget limit: Beijing is Sunny 25C.");
    }
}
