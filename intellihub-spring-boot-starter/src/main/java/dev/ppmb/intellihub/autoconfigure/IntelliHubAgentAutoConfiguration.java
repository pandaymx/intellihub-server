package dev.ppmb.intellihub.autoconfigure;

import dev.ppmb.intellihub.agent.checkpoint.AgentCheckpointStore;
import dev.ppmb.intellihub.agent.checkpoint.FileAgentCheckpointStore;
import dev.ppmb.intellihub.agent.checkpoint.InMemoryAgentCheckpointStore;
import dev.ppmb.intellihub.agent.checkpoint.JdbcAgentCheckpointStore;
import dev.ppmb.intellihub.agent.engine.AgentExecutionBudget;
import dev.ppmb.intellihub.agent.engine.ReActAgentExecutor;
import dev.ppmb.intellihub.agent.memory.FileSessionMemoryStore;
import dev.ppmb.intellihub.agent.memory.InMemorySessionMemoryStore;
import dev.ppmb.intellihub.agent.memory.JdbcSessionMemoryStore;
import dev.ppmb.intellihub.agent.memory.SessionMemoryStore;
import dev.ppmb.intellihub.agent.tool.GovernedToolRegistry;
import dev.ppmb.intellihub.agent.tool.KnowledgeBaseSearchTool;
import dev.ppmb.intellihub.agent.tool.ToolApprovalHandler;
import dev.ppmb.intellihub.prompt.render.SafePromptRenderer;
import dev.ppmb.intellihub.rag.engine.RagQueryEngine;
import java.nio.file.Path;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * {@code intellihub-agent} 模块 Spring Boot 自动装配配置类。
 *
 * <p>当类路径下存在 {@link ReActAgentExecutor} 时，自动注册：
 *
 * <ul>
 *   <li>{@link KnowledgeBaseSearchTool}：将 {@link RagQueryEngine} 封装为内置的 {@code
 *       search_knowledge_base} 工具；
 *   <li>{@link GovernedToolRegistry}：统一收集容器中的 {@link ToolCallback}、内置知识检索工具与可选的 {@link
 *       ToolApprovalHandler}；
 *   <li>{@link ReActAgentExecutor}：具备防死循环守卫、工具调用预算控制与收尾总结的多步 ReAct 智能体执行引擎。
 * </ul>
 */
@AutoConfiguration(
        after = {IntelliHubPromptAutoConfiguration.class, IntelliHubRagAutoConfiguration.class})
@ConditionalOnClass(ReActAgentExecutor.class)
public class IntelliHubAgentAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(RagQueryEngine.class)
    public KnowledgeBaseSearchTool intelliHubKnowledgeBaseSearchTool(
            RagQueryEngine ragQueryEngine) {
        return new KnowledgeBaseSearchTool(ragQueryEngine);
    }

    @Bean
    @ConditionalOnMissingBean
    public GovernedToolRegistry intelliHubGovernedToolRegistry(
            ObjectProvider<List<ToolCallback>> toolCallbacksProvider,
            ObjectProvider<KnowledgeBaseSearchTool> kbSearchToolProvider,
            ObjectProvider<ToolApprovalHandler> approvalHandlerProvider) {
        GovernedToolRegistry registry = new GovernedToolRegistry();
        List<ToolCallback> callbacks = toolCallbacksProvider.getIfAvailable();
        if (callbacks != null && !callbacks.isEmpty()) {
            registry.registerAll(callbacks);
        }
        KnowledgeBaseSearchTool kbTool = kbSearchToolProvider.getIfAvailable();
        if (kbTool != null) {
            registry.registerToolObjects(kbTool);
        }
        ToolApprovalHandler approvalHandler = approvalHandlerProvider.getIfAvailable();
        if (approvalHandler != null) {
            registry.setApprovalHandler(approvalHandler);
        }
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentCheckpointStore intelliHubAgentCheckpointStore(
            ObjectProvider<DataSource> dataSourceProvider,
            @Value("${intellihub.agent.jdbc-store-enabled:false}") boolean jdbcEnabled,
            @Value("${intellihub.agent.checkpoint-path:}") String checkpointPath) {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (jdbcEnabled && dataSource != null) {
            return new JdbcAgentCheckpointStore(dataSource);
        }
        if (checkpointPath != null && !checkpointPath.isBlank()) {
            return new FileAgentCheckpointStore(Path.of(checkpointPath.strip()));
        }
        return new InMemoryAgentCheckpointStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionMemoryStore intelliHubSessionMemoryStore(
            ObjectProvider<DataSource> dataSourceProvider,
            @Value("${intellihub.agent.jdbc-store-enabled:false}") boolean jdbcEnabled,
            @Value("${intellihub.agent.session-memory-path:}") String sessionMemoryPath) {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (jdbcEnabled && dataSource != null) {
            return new JdbcSessionMemoryStore(dataSource);
        }
        if (sessionMemoryPath != null && !sessionMemoryPath.isBlank()) {
            return new FileSessionMemoryStore(Path.of(sessionMemoryPath.strip()));
        }
        return new InMemorySessionMemoryStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public ReActAgentExecutor intelliHubReActAgentExecutor(
            GovernedToolRegistry toolRegistry,
            ObjectProvider<SafePromptRenderer> promptRendererProvider,
            ObjectProvider<AgentCheckpointStore> checkpointStoreProvider) {
        SafePromptRenderer renderer =
                promptRendererProvider.getIfAvailable(SafePromptRenderer::new);
        AgentCheckpointStore checkpointStore =
                checkpointStoreProvider.getIfAvailable(InMemoryAgentCheckpointStore::new);
        return new ReActAgentExecutor(
                toolRegistry, renderer, AgentExecutionBudget.defaults(), checkpointStore);
    }
}
