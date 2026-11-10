package dev.ppmb.intellihub.llm.spi.provider;

import dev.ppmb.intellihub.core.exception.IntelliHubExceptionUtil;
import dev.ppmb.intellihub.llm.config.ModelConfig;
import dev.ppmb.intellihub.llm.config.ModelProviderType;
import dev.ppmb.intellihub.llm.spi.ChatModelBuilderSpi;
import dev.ppmb.intellihub.llm.spi.support.SharedHttpClientSupport;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.http.HttpHeaders;

/**
 * 基于 Spring AI 原生 {@link DeepSeekChatModel} 的构建器（支持 DeepSeek-R1/V3 深度思考开关与共享 HTTP 连接池）。
 */
public class DeepSeekChatModelBuilderSpi implements ChatModelBuilderSpi {

    @Override
    public String provider() {
        return ModelProviderType.DEEPSEEK.getCode();
    }

    @Override
    public boolean supports(String provider) {
        return ModelProviderType.DEEPSEEK.matches(provider);
    }

    @Override
    public DeepSeekChatOptions buildOptions(ModelConfig config) {
        String model = config.resolveModel(ModelProviderType.DEEPSEEK.getDefaultModel());
        DeepSeekChatOptions.Builder optionsBuilder = DeepSeekChatOptions.builder().model(model);
        if (config.temperature() != null) {
            optionsBuilder.temperature(config.temperature());
        }
        if (config.topP() != null) {
            optionsBuilder.topP(config.topP());
        }
        if (config.maxTokens() != null) {
            optionsBuilder.maxTokens(config.maxTokens());
        }
        if (config.frequencyPenalty() != null) {
            optionsBuilder.frequencyPenalty(config.frequencyPenalty());
        }
        if (config.presencePenalty() != null) {
            optionsBuilder.presencePenalty(config.presencePenalty());
        }
        if (!config.stopSequences().isEmpty()) {
            optionsBuilder.stop(config.stopSequences());
        }

        config.getExtraBoolean(ModelConfig.EXTRA_THINKING_ENABLED)
                .ifPresent(enabled -> {
                    if (enabled) {
                        optionsBuilder.enableThinking();
                    } else {
                        optionsBuilder.disableThinking();
                    }
                });
        return optionsBuilder.build();
    }

    @Override
    public ChatModel build(ModelConfig config) {
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw IntelliHubExceptionUtil.invalidParamException(
                    "构建 DeepSeekChatModel 失败：apiKey 不能为空");
        }
        String baseUrl = config.resolveBaseUrl(ModelProviderType.DEEPSEEK.getDefaultBaseUrl());

        DeepSeekApi.Builder apiBuilder =
                DeepSeekApi.builder()
                        .baseUrl(baseUrl)
                        .apiKey(config.apiKey())
                        .restClientBuilder(SharedHttpClientSupport.sharedRestClientBuilder())
                        .webClientBuilder(SharedHttpClientSupport.sharedWebClientBuilder());
        if (!config.customHeaders().isEmpty()) {
            HttpHeaders headers = new HttpHeaders();
            config.customHeaders().forEach(headers::add);
            apiBuilder.headers(headers);
        }

        return DeepSeekChatModel.builder()
                .deepSeekApi(apiBuilder.build())
                .options(buildOptions(config))
                .build();
    }
}
