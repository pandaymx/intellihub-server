package dev.ppmb.intellihub.app.sample;

import dev.ppmb.intellihub.agent.tool.GovernedToolRegistry;
import dev.ppmb.intellihub.agent.tool.ToolApprovalHandler;
import dev.ppmb.intellihub.llm.registry.IntelliHubModelRegistry;
import dev.ppmb.intellihub.llm.router.ModelRouteTarget;
import dev.ppmb.intellihub.llm.router.RoutingChatModel;
import dev.ppmb.intellihub.llm.router.RoutingStrategy;
import dev.ppmb.intellihub.prompt.template.PromptTemplateSpec;
import dev.ppmb.intellihub.rag.ingest.DocumentIngestionPipeline;
import dev.ppmb.intellihub.rag.store.HybridKnowledgeStore;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 联调示例配置：自动注册开箱即用的 Prompt 模板、示例工具、高风险审批策略、初始知识库文档及跨域支持，便于 REST API 联调与测试验证。
 */
@Configuration
public class SampleAppConfiguration implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }

    @Bean
    public PromptTemplateSpec sampleSummarizerPrompt() {
        return PromptTemplateSpec.builder("text-summarizer")
                .version("v1")
                .description("长篇文本要点结构化提炼与摘要模板")
                .systemTemplate("你是一个专业高效的商业与技术文档摘要助手。请严格按照用户指定的格式提炼输入文本的核心要点。")
                .userTemplate("【待摘要内容】\n{content}\n\n【输出要求】\n{requirements}")
                .wrapUserVariablesInXmlTag(true)
                .build();
    }

    @Bean
    public PromptTemplateSpec sampleCustomerServicePrompt() {
        return PromptTemplateSpec.builder("customer-service-agent")
                .version("v1")
                .description("电商售后与智能客服工单诊断模板")
                .systemTemplate("你是一个金牌售后客服专家。请以亲切专业的口吻解答用户咨询，并识别用户诉求的关键分类。")
                .userTemplate("【用户信息】\n用户名：{userName}\n问题描述：{issueDescription}")
                .wrapUserVariablesInXmlTag(true)
                .build();
    }

    @Bean
    public ToolApprovalHandler sampleToolApprovalHandler() {
        return (toolName, rawArgs, context) -> {
            if (Boolean.TRUE.equals(context.get("autoApprove"))) {
                return ToolApprovalHandler.ApprovalDecision.allow();
            }
            if (Boolean.TRUE.equals(context.get("forceReject"))) {
                return ToolApprovalHandler.ApprovalDecision.reject("风控策略强制拒绝执行该高风险工具");
            }
            return ToolApprovalHandler.ApprovalDecision.awaitHumanApproval(
                    "ticket-" + toolName, "高风险敏感操作 [" + toolName + "] 需人工复核审批后方可执行");
        };
    }

    @Bean
    public SmartInitializingSingleton sampleComponentsInitializer(
            IntelliHubModelRegistry modelRegistry,
            GovernedToolRegistry toolRegistry,
            SampleSystemTools systemTools,
            ToolApprovalHandler approvalHandler,
            DocumentIngestionPipeline ingestionPipeline,
            HybridKnowledgeStore knowledgeStore) {
        return () -> {
            DemoMockChatModel demoMockChatModel = new DemoMockChatModel();
            modelRegistry.registerRoute(
                    RoutingChatModel.builder(DemoMockChatModel.CHANNEL_NAME)
                            .strategy(RoutingStrategy.FAILOVER)
                            .targets(
                                    List.of(
                                            new ModelRouteTarget(
                                                    DemoMockChatModel.CHANNEL_NAME,
                                                    "builtin-mock",
                                                    1,
                                                    () -> demoMockChatModel)))
                            .build());

            toolRegistry
                    .registerToolObjects(systemTools)
                    .markHighRisk(SampleSystemTools.TOOL_REFUND_TRANSFER)
                    .setApprovalHandler(approvalHandler);

            if (knowledgeStore.size() == 0) {
                String builtinGuide =
                        """
                        # IntelliHub 企业级 AI 研发底座指南
                        ## 多模型高可用路由与 KeyPool
                        IntelliHub 内置 `RoutingChatModel` 与 `KeyPoolChatModel`，支持 FAILOVER（主备故障转移）、ROUND_ROBIN（轮询）与 WEIGHTED（加权）策略。当上游返回 401/403 鉴权失败时，对应 API Key 将进入 DEAD 状态永久隔离；当返回 429 或超时时进入 COOLDOWN 冷却，冷却期满后通过 CAS 单请求探活（Half-Open）恢复。

                        ## 生产级 ReAct 智能体与防死循环治理
                        `ReActAgentExecutor` 采用基于 Jackson AST 字典序重排的键序无关规范化 JSON 指纹（Canonical JSON Loop Fingerprint），防止大模型因死循环耗尽 Token 预算。同时支持非阻塞异步人工审批挂起（`AWAITING_APPROVAL`）与 `resume` 断点恢复。
                        """;
                Document seedDoc =
                        Document.builder()
                                .id("builtin-architecture-guide")
                                .text(builtinGuide)
                                .build();
                ingestionPipeline.ingest(
                        List.of(seedDoc),
                        Map.of(
                                "title",
                                "IntelliHub 架构指南",
                                DocumentIngestionPipeline.META_TENANT_ID,
                                "default",
                                DocumentIngestionPipeline.META_KB_ID,
                                "builtin"),
                        knowledgeStore::addAll);
            }
        };
    }
}
