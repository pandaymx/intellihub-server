package dev.ppmb.intellihub.llm.spi.provider;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import dev.ppmb.intellihub.core.exception.IntelliHubExceptionUtil;
import dev.ppmb.intellihub.llm.config.ModelConfig;
import dev.ppmb.intellihub.llm.config.ModelProviderType;
import dev.ppmb.intellihub.llm.spi.ChatModelBuilderSpi;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;

/**
 * 基于 Spring AI 原生 {@link GoogleGenAiChatModel} 的构建器（支持 Gemini API Key 模式与 Vertex AI 模式）。
 */
public class GoogleGenAiChatModelBuilderSpi implements ChatModelBuilderSpi {

    @Override
    public String provider() {
        return ModelProviderType.GOOGLE_GENAI.getCode();
    }

    @Override
    public boolean supports(String provider) {
        return ModelProviderType.GOOGLE_GENAI.matches(provider);
    }

    @Override
    public ChatModel build(ModelConfig config) {
        boolean vertexAi = config.getExtraBoolean(ModelConfig.EXTRA_VERTEX_AI).orElse(false);
        if (!vertexAi && (config.apiKey() == null || config.apiKey().isBlank())) {
            throw IntelliHubExceptionUtil.invalidParamException(
                    "构建 GoogleGenAiChatModel 失败：非 Vertex AI 模式下 apiKey 不能为空");
        }

        Client.Builder clientBuilder = Client.builder();
        if (config.apiKey() != null && !config.apiKey().isBlank()) {
            clientBuilder.apiKey(config.apiKey());
        }
        if (vertexAi) {
            clientBuilder.vertexAI(true);
            config.getExtraString(ModelConfig.EXTRA_GCP_PROJECT).ifPresent(clientBuilder::project);
            config.getExtraString(ModelConfig.EXTRA_GCP_LOCATION)
                    .ifPresent(clientBuilder::location);
        }

        if ((config.baseUrl() != null && !config.baseUrl().isBlank())
                || config.timeout() != null
                || !config.customHeaders().isEmpty()) {
            HttpOptions.Builder httpBuilder = HttpOptions.builder();
            if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
                httpBuilder.baseUrl(config.baseUrl().strip());
            }
            if (config.timeout() != null) {
                httpBuilder.timeout((int) config.timeout().toMillis());
            }
            if (!config.customHeaders().isEmpty()) {
                httpBuilder.headers(config.customHeaders());
            }
            clientBuilder.httpOptions(httpBuilder.build());
        }

        String model = config.resolveModel(ModelProviderType.GOOGLE_GENAI.getDefaultModel());
        GoogleGenAiChatOptions.Builder optionsBuilder =
                GoogleGenAiChatOptions.builder().model(model);

        if (config.temperature() != null) {
            optionsBuilder.temperature(config.temperature());
        }
        if (config.topP() != null) {
            optionsBuilder.topP(config.topP());
        }
        if (config.topK() != null) {
            optionsBuilder.topK(config.topK());
        }
        if (config.maxTokens() != null) {
            optionsBuilder.maxOutputTokens(config.maxTokens());
        }
        if (!config.stopSequences().isEmpty()) {
            optionsBuilder.stopSequences(config.stopSequences());
        }

        config.getExtraBoolean(ModelConfig.EXTRA_THINKING_ENABLED)
                .ifPresent(optionsBuilder::includeThoughts);
        config.getExtraInt(ModelConfig.EXTRA_THINKING_BUDGET)
                .ifPresent(optionsBuilder::thinkingBudget);

        return GoogleGenAiChatModel.builder()
                .genAiClient(clientBuilder.build())
                .options(optionsBuilder.build())
                .build();
    }
}
