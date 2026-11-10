package dev.ppmb.intellihub.llm.spi.provider;

import dev.ppmb.intellihub.core.exception.IntelliHubExceptionUtil;
import dev.ppmb.intellihub.llm.config.ModelConfig;
import dev.ppmb.intellihub.llm.config.ModelProviderType;
import dev.ppmb.intellihub.llm.spi.ChatModelBuilderSpi;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;

/**
 * 基于 Spring AI 原生 {@link AnthropicChatModel} 的构建器（支持 Claude Extended Thinking 预算配置）。
 */
public class AnthropicChatModelBuilderSpi implements ChatModelBuilderSpi {

    private static final int DEFAULT_MAX_TOKENS = 4096;
    private static final long DEFAULT_THINKING_BUDGET = 2048L;

    @Override
    public String provider() {
        return ModelProviderType.ANTHROPIC.getCode();
    }

    @Override
    public boolean supports(String provider) {
        return ModelProviderType.ANTHROPIC.matches(provider);
    }

    @Override
    public ChatModel build(ModelConfig config) {
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw IntelliHubExceptionUtil.invalidParamException(
                    "构建 AnthropicChatModel 失败：apiKey 不能为空");
        }
        String baseUrl = config.resolveBaseUrl(ModelProviderType.ANTHROPIC.getDefaultBaseUrl());
        String model = config.resolveModel(ModelProviderType.ANTHROPIC.getDefaultModel());

        AnthropicChatOptions.Builder optionsBuilder =
                AnthropicChatOptions.builder()
                        .apiKey(config.apiKey())
                        .baseUrl(baseUrl)
                        .model(model)
                        .maxTokens(
                                config.maxTokens() != null
                                        ? config.maxTokens()
                                        : DEFAULT_MAX_TOKENS);

        if (config.temperature() != null) {
            optionsBuilder.temperature(config.temperature());
        }
        if (config.topP() != null) {
            optionsBuilder.topP(config.topP());
        }
        if (config.topK() != null) {
            optionsBuilder.topK(config.topK());
        }
        if (!config.stopSequences().isEmpty()) {
            optionsBuilder.stopSequences(config.stopSequences());
        }
        if (config.timeout() != null) {
            optionsBuilder.timeout(config.timeout());
        }
        if (config.maxRetries() != null) {
            optionsBuilder.maxRetries(config.maxRetries());
        }
        if (!config.customHeaders().isEmpty()) {
            optionsBuilder.customHeaders(config.customHeaders());
        }

        config.getExtraBoolean(ModelConfig.EXTRA_THINKING_ENABLED)
                .ifPresent(enabled -> {
                    if (enabled) {
                        long budget =
                                config.getExtraLong(ModelConfig.EXTRA_THINKING_BUDGET)
                                        .orElse(DEFAULT_THINKING_BUDGET);
                        optionsBuilder.thinkingEnabled(budget);
                    } else {
                        optionsBuilder.thinkingDisabled();
                    }
                });

        return AnthropicChatModel.builder().options(optionsBuilder.build()).build();
    }
}
