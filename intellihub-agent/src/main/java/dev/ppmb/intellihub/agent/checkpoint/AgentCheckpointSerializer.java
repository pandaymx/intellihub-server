package dev.ppmb.intellihub.agent.checkpoint;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ppmb.intellihub.agent.engine.AgentCheckpoint;
import dev.ppmb.intellihub.agent.engine.AgentStepTrace;
import dev.ppmb.intellihub.core.exception.GlobalErrorCodeConstants;
import dev.ppmb.intellihub.core.exception.IntelliHubExceptionUtil;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;

/**
 * {@link AgentCheckpoint} 与 Spring AI 多态 {@link Message} 列表的无损 JSON 序列化器。
 *
 * <p>解决 Spring AI {@link AssistantMessage}、{@link ToolResponseMessage} 等类无默认无参构造器而无法直接用默认 Jackson
 * 反序列化的问题，供 {@link JdbcAgentCheckpointStore}、{@link FileAgentCheckpointStore} 与会话记忆存储复用。
 */
public final class AgentCheckpointSerializer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentCheckpointSerializer() {}

    public static String serializeCheckpoint(AgentCheckpoint cp) {
        try {
            SerializedCheckpoint dto =
                    new SerializedCheckpoint(
                            cp.checkpointId(),
                            cp.approvalTicketId(),
                            cp.suspendedAtIteration(),
                            toMessageDto(cp.pendingAssistantMessage()),
                            toToolCallDto(cp.pendingToolCall()),
                            cp.conversationHistory().stream()
                                    .map(AgentCheckpointSerializer::toMessageDto)
                                    .toList(),
                            cp.enabledToolNames(),
                            cp.completedSteps(),
                            cp.callFingerprintCounts(),
                            cp.totalToolCalls(),
                            cp.totalTokens());
            return MAPPER.writeValueAsString(dto);
        } catch (Exception ex) {
            throw IntelliHubExceptionUtil.exception(
                    GlobalErrorCodeConstants.INTERNAL_SERVER_ERROR,
                    "序列化 AgentCheckpoint 失败: " + ex.getMessage());
        }
    }

    public static AgentCheckpoint deserializeCheckpoint(String json) {
        try {
            SerializedCheckpoint dto = MAPPER.readValue(json, SerializedCheckpoint.class);
            Message pendingMsg =
                    dto.pendingAssistantMessage() != null
                            ? fromMessageDto(dto.pendingAssistantMessage())
                            : new AssistantMessage("");
            AssistantMessage assistantMsg =
                    pendingMsg instanceof AssistantMessage am
                            ? am
                            : new AssistantMessage(pendingMsg.getText());
            AssistantMessage.ToolCall pendingTc = fromToolCallDto(dto.pendingToolCall());
            List<Message> history =
                    dto.conversationHistory() != null
                            ? dto.conversationHistory().stream()
                                    .map(AgentCheckpointSerializer::fromMessageDto)
                                    .toList()
                            : List.of();
            return new AgentCheckpoint(
                    dto.checkpointId(),
                    dto.approvalTicketId(),
                    dto.suspendedAtIteration(),
                    assistantMsg,
                    pendingTc,
                    history,
                    null,
                    dto.enabledToolNames(),
                    dto.completedSteps() != null ? dto.completedSteps() : List.of(),
                    dto.callFingerprintCounts() != null ? dto.callFingerprintCounts() : Map.of(),
                    dto.totalToolCalls(),
                    dto.totalTokens());
        } catch (Exception ex) {
            throw IntelliHubExceptionUtil.exception(
                    GlobalErrorCodeConstants.INTERNAL_SERVER_ERROR,
                    "反序列化 AgentCheckpoint 失败: " + ex.getMessage());
        }
    }

    public static String serializeMessages(List<Message> messages) {
        try {
            if (messages == null || messages.isEmpty()) {
                return "[]";
            }
            List<SerializedMessage> dtos =
                    messages.stream().map(AgentCheckpointSerializer::toMessageDto).toList();
            return MAPPER.writeValueAsString(dtos);
        } catch (Exception ex) {
            throw IntelliHubExceptionUtil.exception(
                    GlobalErrorCodeConstants.INTERNAL_SERVER_ERROR,
                    "序列化会话消息列表失败: " + ex.getMessage());
        }
    }

    public static List<Message> deserializeMessages(String json) {
        try {
            if (json == null || json.isBlank()) {
                return List.of();
            }
            List<SerializedMessage> dtos =
                    MAPPER.readValue(json, new TypeReference<List<SerializedMessage>>() {});
            return dtos.stream().map(AgentCheckpointSerializer::fromMessageDto).toList();
        } catch (Exception ex) {
            throw IntelliHubExceptionUtil.exception(
                    GlobalErrorCodeConstants.INTERNAL_SERVER_ERROR,
                    "反序列化会话消息列表失败: " + ex.getMessage());
        }
    }

    public static SerializedMessage toMessageDto(Message msg) {
        if (msg == null) {
            return null;
        }
        String type =
                msg.getMessageType() != null
                        ? msg.getMessageType().name()
                        : MessageType.USER.name();
        String text = msg.getText() != null ? msg.getText() : "";
        Map<String, Object> metadata =
                msg.getMetadata() != null ? new LinkedHashMap<>(msg.getMetadata()) : Map.of();

        if (msg instanceof AssistantMessage am) {
            List<SerializedToolCall> tcs =
                    am.getToolCalls() != null
                            ? am.getToolCalls().stream()
                                    .map(AgentCheckpointSerializer::toToolCallDto)
                                    .toList()
                            : List.of();
            return new SerializedMessage(type, text, metadata, tcs, List.of(), List.of());
        }
        if (msg instanceof ToolResponseMessage trm) {
            List<SerializedToolResponse> resps =
                    trm.getResponses() != null
                            ? trm.getResponses().stream()
                                    .map(r ->
                                            new SerializedToolResponse(
                                                    r.id(), r.name(), r.responseData()))
                                    .toList()
                            : List.of();
            return new SerializedMessage(type, text, metadata, List.of(), resps, List.of());
        }
        if (msg instanceof UserMessage um && um.getMedia() != null && !um.getMedia().isEmpty()) {
            List<SerializedMedia> mediaList = new ArrayList<>();
            for (Media m : um.getMedia()) {
                if (m == null) {
                    continue;
                }
                try {
                    byte[] bytes = m.getDataAsByteArray();
                    if (bytes != null && bytes.length > 0) {
                        mediaList.add(
                                new SerializedMedia(
                                        m.getName(),
                                        m.getMimeType() != null
                                                ? m.getMimeType().toString()
                                                : "application/octet-stream",
                                        Base64.getEncoder().encodeToString(bytes)));
                    }
                } catch (Exception ignored) {
                    // ignore non-byte media
                }
            }
            return new SerializedMessage(type, text, metadata, List.of(), List.of(), mediaList);
        }
        return new SerializedMessage(type, text, metadata, List.of(), List.of(), List.of());
    }

    public static Message fromMessageDto(SerializedMessage dto) {
        if (dto == null) {
            return new UserMessage("");
        }
        String type = dto.messageType() != null ? dto.messageType() : MessageType.USER.name();
        String text = dto.text() != null ? dto.text() : "";

        if (MessageType.SYSTEM.name().equalsIgnoreCase(type)) {
            return new SystemMessage(text);
        }
        if (MessageType.ASSISTANT.name().equalsIgnoreCase(type)) {
            List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
            if (dto.toolCalls() != null) {
                for (SerializedToolCall tc : dto.toolCalls()) {
                    toolCalls.add(fromToolCallDto(tc));
                }
            }
            return AssistantMessage.builder()
                    .content(text)
                    .properties(dto.metadata() != null ? dto.metadata() : Map.of())
                    .toolCalls(toolCalls)
                    .build();
        }
        if (MessageType.TOOL.name().equalsIgnoreCase(type)) {
            List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
            if (dto.toolResponses() != null) {
                for (SerializedToolResponse tr : dto.toolResponses()) {
                    responses.add(
                            new ToolResponseMessage.ToolResponse(
                                    tr.id(), tr.name(), tr.responseData()));
                }
            }
            return ToolResponseMessage.builder().responses(responses).build();
        }
        if (dto.media() != null && !dto.media().isEmpty()) {
            List<Media> mediaObjs = new ArrayList<>();
            for (SerializedMedia sm : dto.media()) {
                if (sm == null || sm.base64Data() == null || sm.base64Data().isBlank()) {
                    continue;
                }
                try {
                    byte[] bytes = Base64.getDecoder().decode(sm.base64Data());
                    String mime =
                            sm.mimeType() != null && !sm.mimeType().isBlank()
                                    ? sm.mimeType()
                                    : "image/png";
                    mediaObjs.add(
                            Media.builder()
                                    .mimeType(MimeTypeUtils.parseMimeType(mime))
                                    .data(bytes)
                                    .name(sm.name() != null ? sm.name() : "media")
                                    .build());
                } catch (Exception ignored) {
                    // ignore invalid base64
                }
            }
            if (!mediaObjs.isEmpty()) {
                return UserMessage.builder().text(text).media(mediaObjs).build();
            }
        }
        return new UserMessage(text);
    }

    private static SerializedToolCall toToolCallDto(AssistantMessage.ToolCall tc) {
        if (tc == null) {
            return null;
        }
        return new SerializedToolCall(tc.id(), tc.type(), tc.name(), tc.arguments());
    }

    private static AssistantMessage.ToolCall fromToolCallDto(SerializedToolCall dto) {
        if (dto == null) {
            return null;
        }
        return new AssistantMessage.ToolCall(
                dto.id(),
                dto.type() != null ? dto.type() : "function",
                dto.name(),
                dto.arguments());
    }

    public record SerializedCheckpoint(
            String checkpointId,
            String approvalTicketId,
            int suspendedAtIteration,
            SerializedMessage pendingAssistantMessage,
            SerializedToolCall pendingToolCall,
            List<SerializedMessage> conversationHistory,
            List<String> enabledToolNames,
            List<AgentStepTrace> completedSteps,
            Map<String, Integer> callFingerprintCounts,
            int totalToolCalls,
            long totalTokens) {}

    public record SerializedMessage(
            String messageType,
            String text,
            Map<String, Object> metadata,
            List<SerializedToolCall> toolCalls,
            List<SerializedToolResponse> toolResponses,
            List<SerializedMedia> media) {}

    public record SerializedMedia(String name, String mimeType, String base64Data) {}

    public record SerializedToolCall(String id, String type, String name, String arguments) {}

    public record SerializedToolResponse(String id, String name, String responseData) {}
}
