package dev.ppmb.intellihub.app.controller;

import dev.ppmb.intellihub.agent.engine.AgentExecutionBudget;
import dev.ppmb.intellihub.agent.engine.AgentRunResult;
import dev.ppmb.intellihub.agent.engine.ReActAgentExecutor;
import dev.ppmb.intellihub.agent.tool.GovernedToolRegistry;
import dev.ppmb.intellihub.app.common.ApiResponse;
import dev.ppmb.intellihub.app.dto.agent.AgentResumeApiRequest;
import dev.ppmb.intellihub.app.dto.agent.AgentRunApiRequest;
import dev.ppmb.intellihub.app.dto.agent.AgentRunApiResponse;
import dev.ppmb.intellihub.app.dto.agent.ToolInfoResponse;
import dev.ppmb.intellihub.core.exception.GlobalErrorCodeConstants;
import dev.ppmb.intellihub.core.exception.IntelliHubExceptionUtil;
import dev.ppmb.intellihub.llm.registry.IntelliHubModelRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 生产级多步 ReAct 智能体执行、工具治理与人工审批断点恢复 REST 控制器。
 */
@RestController
@RequestMapping("/api/v1/agent")
public class AgentController {

    private final GovernedToolRegistry toolRegistry;
    private final ReActAgentExecutor agentExecutor;
    private final IntelliHubModelRegistry modelRegistry;

    public AgentController(
            GovernedToolRegistry toolRegistry,
            ReActAgentExecutor agentExecutor,
            IntelliHubModelRegistry modelRegistry) {
        this.toolRegistry = toolRegistry;
        this.agentExecutor = agentExecutor;
        this.modelRegistry = modelRegistry;
    }

    /**
     * 查询当前已注册纳管的工具列表及其 Schema 定义与高风险标记。
     */
    @GetMapping("/tools")
    public ApiResponse<List<ToolInfoResponse>> getTools() {
        List<ToolCallback> callbacks = toolRegistry.getAllToolCallbacks();
        List<ToolInfoResponse> result = new ArrayList<>(callbacks.size());
        for (ToolCallback cb : callbacks) {
            String name = cb.getToolDefinition().name();
            String desc = cb.getToolDefinition().description();
            String schema = cb.getToolDefinition().inputSchema();
            boolean highRisk = toolRegistry.isHighRisk(name);
            result.add(new ToolInfoResponse(name, desc, schema, highRisk));
        }
        return ApiResponse.success(result);
    }

    /**
     * 运行 ReAct 智能体多步推理任务（具备键序无关规范化 JSON 指纹防死循环与 Token 预算保护）。
     */
    @PostMapping("/run")
    public ApiResponse<AgentRunApiResponse> runAgent(@RequestBody AgentRunApiRequest request) {
        if (request == null || request.getUserGoal() == null || request.getUserGoal().isBlank()) {
            throw IntelliHubExceptionUtil.exception(
                    GlobalErrorCodeConstants.AI_PROMPT_EMPTY, "目标描述 userGoal 不能为空");
        }

        String targetChannel =
                (request.getChannel() != null && !request.getChannel().isBlank())
                        ? request.getChannel().strip()
                        : modelRegistry.getDefaultChannelOrRoute();
        ChatModel chatModel = modelRegistry.getChatModel(targetChannel);

        List<Message> messages = new ArrayList<>();
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
            messages.add(new SystemMessage(request.getSystemPrompt().strip()));
        }
        messages.add(new UserMessage(request.getUserGoal().strip()));
        Prompt initialPrompt = new Prompt(messages);

        AgentExecutionBudget.Builder budgetBuilder = AgentExecutionBudget.builder();
        if (request.getMaxIterations() != null && request.getMaxIterations() > 0) {
            budgetBuilder.maxIterations(request.getMaxIterations());
        }
        if (request.getMaxTotalToolCalls() != null && request.getMaxTotalToolCalls() > 0) {
            budgetBuilder.maxTotalToolCalls(request.getMaxTotalToolCalls());
        }
        if (request.getMaxDuplicateToolCalls() != null && request.getMaxDuplicateToolCalls() > 0) {
            budgetBuilder.maxDuplicateToolCalls(request.getMaxDuplicateToolCalls());
        }
        if (request.getMaxTotalTokens() != null && request.getMaxTotalTokens() > 0) {
            budgetBuilder.maxTotalTokens(request.getMaxTotalTokens());
        }
        if (request.getMaxExecutionDurationSeconds() != null
                && request.getMaxExecutionDurationSeconds() > 0) {
            budgetBuilder.maxExecutionDuration(
                    Duration.ofSeconds(request.getMaxExecutionDurationSeconds()));
        }

        AgentRunResult runResult =
                agentExecutor.run(
                        chatModel,
                        initialPrompt,
                        request.getEnabledTools(),
                        request.getToolContext(),
                        budgetBuilder.build());

        return ApiResponse.success(AgentRunApiResponse.fromDomain(runResult, targetChannel));
    }

    /**
     * 恢复处于人工审批挂起状态的 Agent 任务。
     */
    @PostMapping("/resume")
    public ApiResponse<AgentRunApiResponse> resumeAgent(
            @RequestBody AgentResumeApiRequest request) {
        if (request == null
                || request.getCheckpointId() == null
                || request.getCheckpointId().isBlank()) {
            throw IntelliHubExceptionUtil.invalidParamException("挂起快照 checkpointId 不能为空");
        }
        if (request.getApproved() == null) {
            throw IntelliHubExceptionUtil.invalidParamException("审批状态 approved 不能为空");
        }

        String targetChannel =
                (request.getChannel() != null && !request.getChannel().isBlank())
                        ? request.getChannel().strip()
                        : modelRegistry.getDefaultChannelOrRoute();
        ChatModel chatModel = modelRegistry.getChatModel(targetChannel);

        AgentRunResult resumeResult =
                agentExecutor.resume(
                        chatModel,
                        request.getCheckpointId().strip(),
                        request.getApproved(),
                        request.getReviewerNote(),
                        request.getToolContext(),
                        AgentExecutionBudget.defaults());

        return ApiResponse.success(AgentRunApiResponse.fromDomain(resumeResult, targetChannel));
    }
}
