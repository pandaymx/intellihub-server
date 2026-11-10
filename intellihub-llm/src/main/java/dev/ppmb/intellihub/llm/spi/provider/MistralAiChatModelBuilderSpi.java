package dev.ppmb.intellihub.llm.spi.provider;

import dev.ppmb.intellihub.core.exception.IntelliHubExceptionUtil;
import dev.ppmb.intellihub.llm.config.ModelConfig;
import dev.ppmb.intellihub.llm.config.ModelProviderType;
import dev.ppmb.intellihub.llm.spi.ChatModelBuilderSpi;
import dev.ppmb.intellihub.llm.spi.support.SharedHttpClientSupport;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.mistralai.MistralAiChatModel;
import org.springframework.ai.mistralai.MistralAiChatOptions;
import org.springframework.ai.mistralai.api.MistralAiApi;

/**
 * 基于 Spring AI 原生 {@link MistralAiChatModel} 的构建器（复用全局共享 HTTP 连接池）。
 */
public class MistralAiChatModelBuilderSpi implements ChatModelBuilderSpi {

    @Override
    public String provider() {
        return ModelProviderType.MISTRAL_AI.getCode();
    }

    @Override
    public boolean supports(String provider) {
        return ModelProviderType.MISTRAL_AI.matches(provider);
    }

    @Override
    public MistralAiChatOptions buildOptions(ModelConfig config) {
        String model = config.resolveModel(ModelProviderType.MISTRAL_AI.getDefaultModel());
        MistralAiChatOptions.Builder optionsBuilder = MistralAiChatOptions.builder().model(model);
        if (config.temperature() != null) {
            optionsBuilder.temperature(config.temperature());
        }
        if (config.topP() != null) {
            optionsBuilder.topP(config.topP());
        }
        if (config.maxTokens() != null) {
            optionsBuilder.maxTokens(config.maxTokens());
        }
        if (!config.stopSequences().isEmpty()) {
            optionsBuilder.stop(config.stopSequences());
        }
        return optionsBuilder.build();
    }

    @Override
    public ChatModel build(ModelConfig config) {
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw IntelliHubExceptionUtil.invalidParamException(
                    "构建 MistralAiChatModel 失败：apiKey 不能为空");
        }
        String baseUrl = config.resolveBaseUrl(ModelProviderType.MISTRAL_AI.getDefaultBaseUrl());

        MistralAiApi mistralAiApi =
                MistralAiApi.builder()
                        .baseUrl(baseUrl)
                        .apiKey(config.apiKey())
                        .restClientBuilder(SharedHttpClientSupport.sharedRestClientBuilder())
                        .webClientBuilder(SharedHttpClientSupport.sharedWebClientBuilder())
                        .build();

        return MistralAiChatModel.builder()
                .mistralAiApi(mistralAiApi)
                .options(buildOptions(config))
                .build();
    }
}
