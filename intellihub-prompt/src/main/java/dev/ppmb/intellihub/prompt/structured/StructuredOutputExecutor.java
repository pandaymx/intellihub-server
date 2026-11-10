package dev.ppmb.intellihub.prompt.structured;

import dev.ppmb.intellihub.core.clean.AiOutputUtil;
import dev.ppmb.intellihub.core.exception.GlobalErrorCodeConstants;
import dev.ppmb.intellihub.core.exception.IntelliHubException;
import dev.ppmb.intellihub.prompt.render.SafePromptRenderer;
import dev.ppmb.intellihub.prompt.template.PromptTemplateSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.ParameterizedTypeReference;

/**
 * 容错型结构化输出执行器（Fault-Tolerant Structured Output Executor）。
 *
 * <p>打通 Spring AI {@link BeanOutputConverter} 与 {@link AiOutputUtil#extractAndRepairJson(String)}：
 *
 * <ul>
 *   <li>自动在末条用户消息末尾追加目标类型的 JSON Schema 格式约束；
 *   <li>对大模型原始返回自动剥离 {@code <think>...</think>} 推理思考块、Markdown 代码围栏并修复截断 JSON；
 *   <li>支持可配置的自纠错重试（Self-Correction Retry）：当首次反序列化失败时，将解析异常信息反馈给模型进行二次修正；
 *   <li>超过最大重试次数后统一抛出 {@link GlobalErrorCodeConstants#AI_STRUCTURED_OUTPUT_PARSE_ERROR}。
 * </ul>
 */
public class StructuredOutputExecutor {

    private static final Logger log = LoggerFactory.getLogger(StructuredOutputExecutor.class);

    public static final int DEFAULT_MAX_RETRIES = 1;

    private final SafePromptRenderer promptRenderer;
    private final int maxRetries;

    public StructuredOutputExecutor() {
        this(new SafePromptRenderer(), DEFAULT_MAX_RETRIES);
    }

    public StructuredOutputExecutor(SafePromptRenderer promptRenderer, int maxRetries) {
        this.promptRenderer = Objects.requireNonNull(promptRenderer, "promptRenderer 不能为空");
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries 不能小于 0");
        }
        this.maxRetries = maxRetries;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    /**
     * 渲染提示词模板并执行结构化输出转换（按 {@link Class} 目标类型）。
     */
    public <T> T execute(
            ChatModel chatModel,
            PromptTemplateSpec spec,
            Map<String, Object> variables,
            Class<T> targetType) {
        Prompt prompt = promptRenderer.render(spec, variables);
        return execute(chatModel, prompt, targetType);
    }

    /**
     * 渲染提示词模板并执行结构化输出转换（支持泛型 {@link ParameterizedTypeReference}）。
     */
    public <T> T execute(
            ChatModel chatModel,
            PromptTemplateSpec spec,
            Map<String, Object> variables,
            ParameterizedTypeReference<T> typeReference) {
        Prompt prompt = promptRenderer.render(spec, variables);
        return execute(chatModel, prompt, typeReference);
    }

    /**
     * 执行 {@link Prompt} 并将模型输出反序列化为目标类型 {@code T}。
     */
    public <T> T execute(ChatModel chatModel, Prompt prompt, Class<T> targetType) {
        Objects.requireNonNull(targetType, "targetType 不能为空");
        return doExecute(chatModel, prompt, new BeanOutputConverter<>(targetType));
    }

    /**
     * 执行 {@link Prompt} 并将模型输出反序列化为复杂泛型类型 {@code T}。
     */
    public <T> T execute(
            ChatModel chatModel, Prompt prompt, ParameterizedTypeReference<T> typeReference) {
        Objects.requireNonNull(typeReference, "typeReference 不能为空");
        return doExecute(chatModel, prompt, new BeanOutputConverter<>(typeReference));
    }

    private <T> T doExecute(ChatModel chatModel, Prompt prompt, BeanOutputConverter<T> converter) {
        Objects.requireNonNull(chatModel, "chatModel 不能为空");
        Objects.requireNonNull(prompt, "prompt 不能为空");

        String formatInstructions = converter.getFormat();
        List<Message> conversation =
                appendFormatToLastUserMessage(prompt.getInstructions(), formatInstructions);

        RuntimeException lastError = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            Prompt currentPrompt = new Prompt(conversation, prompt.getOptions());
            ChatResponse response = chatModel.call(currentPrompt);
            String rawText = extractText(response);
            String cleanedJson = AiOutputUtil.extractAndRepairJson(rawText);

            try {
                if (cleanedJson.isBlank()) {
                    throw new IllegalStateException("模型输出中未提取到有效 JSON 载荷: " + abbreviate(rawText));
                }
                T result = converter.convert(cleanedJson);
                if (result == null) {
                    throw new IllegalStateException(
                            "JSON 反序列化结果为 null: " + abbreviate(cleanedJson));
                }
                if (attempt > 0) {
                    log.info("[StructuredOutputExecutor] 第 {} 次自纠错重试成功修复结构化输出", attempt);
                }
                return result;
            } catch (RuntimeException ex) {
                lastError = ex;
                if (attempt < maxRetries) {
                    log.warn(
                            "[StructuredOutputExecutor] 结构化输出解析失败（第 {}/{} 次重试），原因: {}",
                            attempt + 1,
                            maxRetries,
                            ex.getMessage());
                    conversation = new ArrayList<>(conversation);
                    conversation.add(new AssistantMessage(rawText == null ? "" : rawText));
                    conversation.add(
                            new UserMessage(
                                    "Your previous response could not be parsed as valid JSON matching the required schema. "
                                            + "Parse error: "
                                            + ex.getMessage()
                                            + "\nPlease respond ONLY with a valid JSON object/array strictly adhering to the schema:\n"
                                            + formatInstructions));
                }
            }
        }

        String errorDetail = lastError != null ? lastError.getMessage() : "未知反序列化错误";
        throw new IntelliHubException(
                GlobalErrorCodeConstants.AI_STRUCTURED_OUTPUT_PARSE_ERROR.getCode(),
                "AI 结构化输出解析失败: " + errorDetail,
                lastError);
    }

    private static List<Message> appendFormatToLastUserMessage(
            List<Message> originalMessages, String formatInstructions) {
        List<Message> messages = new ArrayList<>(originalMessages);
        if (formatInstructions == null || formatInstructions.isBlank()) {
            return messages;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message instanceof UserMessage userMessage) {
                String currentText = userMessage.getText() == null ? "" : userMessage.getText();
                if (!currentText.contains(formatInstructions)) {
                    messages.set(i, new UserMessage(currentText + "\n\n" + formatInstructions));
                }
                return messages;
            }
        }
        messages.add(new UserMessage(formatInstructions));
        return messages;
    }

    private static String extractText(ChatResponse response) {
        if (response == null
                || response.getResult() == null
                || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null) {
            return "";
        }
        return response.getResult().getOutput().getText();
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "null";
        }
        String singleLine = text.replace('\n', ' ').replace('\r', ' ').strip();
        return singleLine.length() <= 160 ? singleLine : singleLine.substring(0, 160) + "...";
    }
}
