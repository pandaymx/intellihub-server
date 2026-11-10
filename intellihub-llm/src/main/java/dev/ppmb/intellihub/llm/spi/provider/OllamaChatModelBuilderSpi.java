package dev.ppmb.intellihub.llm.spi.provider;

import dev.ppmb.intellihub.llm.config.ModelConfig;
import dev.ppmb.intellihub.llm.config.ModelProviderType;
import dev.ppmb.intellihub.llm.spi.ChatModelBuilderSpi;
import dev.ppmb.intellihub.llm.spi.support.SharedHttpClientSupport;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;

/**
 * 基于 Spring AI 原生 {@link OllamaChatModel} 的构建器（复用全局共享 HTTP 连接池）。
 */
public class OllamaChatModelBuilderSpi implements ChatModelBuilderSpi {

    @Override
    public String provider() {
        return ModelProviderType.OLLAMA.getCode();
    }

    @Override
    public boolean supports(String provider) {
        return ModelProviderType.OLLAMA.matches(provider);
    }

    @Override
    public OllamaChatOptions buildOptions(ModelConfig config) {
        String model = config.resolveModel(ModelProviderType.OLLAMA.getDefaultModel());
        OllamaChatOptions.Builder optionsBuilder = OllamaChatOptions.builder().model(model);

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
            optionsBuilder.numPredict(config.maxTokens());
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

        config.getExtraInt(ModelConfig.EXTRA_OLLAMA_NUM_CTX).ifPresent(optionsBuilder::numCtx);
        config.getExtraString(ModelConfig.EXTRA_OLLAMA_KEEP_ALIVE)
                .ifPresent(optionsBuilder::keepAlive);
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
        String baseUrl = config.resolveBaseUrl(ModelProviderType.OLLAMA.getDefaultBaseUrl());
        OllamaApi ollamaApi =
                OllamaApi.builder()
                        .baseUrl(baseUrl)
                        .restClientBuilder(SharedHttpClientSupport.sharedRestClientBuilder())
                        .webClientBuilder(SharedHttpClientSupport.sharedWebClientBuilder())
                        .build();

        return OllamaChatModel.builder().ollamaApi(ollamaApi).options(buildOptions(config)).build();
    }
}
