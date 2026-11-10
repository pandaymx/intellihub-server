package dev.ppmb.intellihub.agent.engine;

import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.ChatOptions;

/**
 * 异步人工审批（Human-in-the-Loop）挂起快照（Agent Checkpoint）。
 *
 * <p>当高风险工具触发 {@code AWAITING_HUMAN_APPROVAL} 时，{@link ReActAgentExecutor} 保存当前会话状态、已完成轨迹、指纹计数及待决工具调用，
 * 并<b>立即释放当前工作线程</b>。待前端或审批流回调后，可通过 {@code ReActAgentExecutor.resume(...)} 无缝恢复执行。
 */
public record AgentCheckpoint(
        String checkpointId,
        String approvalTicketId,
        int suspendedAtIteration,
        AssistantMessage pendingAssistantMessage,
        AssistantMessage.ToolCall pendingToolCall,
        List<Message> conversationHistory,
        ChatOptions baseOptions,
        List<String> enabledToolNames,
        List<AgentStepTrace> completedSteps,
        Map<String, Integer> callFingerprintCounts,
        int totalToolCalls,
        long totalTokens) {

    public AgentCheckpoint {
        conversationHistory =
                conversationHistory == null ? List.of() : List.copyOf(conversationHistory);
        enabledToolNames = enabledToolNames == null ? null : List.copyOf(enabledToolNames);
        completedSteps = completedSteps == null ? List.of() : List.copyOf(completedSteps);
        callFingerprintCounts =
                callFingerprintCounts == null ? Map.of() : Map.copyOf(callFingerprintCounts);
    }
}
