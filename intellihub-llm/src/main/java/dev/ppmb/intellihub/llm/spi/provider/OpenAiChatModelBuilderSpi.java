package dev.ppmb.intellihub.llm.spi.provider;

import dev.ppmb.intellihub.core.exception.IntelliHubExceptionUtil;
import dev.ppmb.intellihub.llm.config.ModelConfig;
import dev.ppmb.intellihub.llm.config.ModelProviderType;
import dev.ppmb.intellihub.llm.spi.ChatModelBuilderSpi;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * 基于 Spring AI 原生 {@link OpenAiChatModel} 的构建器，覆盖 OpenAI 及官方文档中所有 OpenAI 协议兼容渠道：
 *
 * <ul>
 *   <li>OpenAI ({@code openai})
 *   <li>Azure OpenAI ({@code azure-openai} / {@code azure})
 *   <li>Groq ({@code groq})
 *   <li>NVIDIA NIM ({@code nvidia})
 *   <li>Perplexity AI ({@code perplexity})
 *   <li>MiniMax ({@code minimax})
 *   <li>Docker Model Runner ({@code docker-model-runner})
 *   <li>OCI Generative AI ({@code oci-genai})
 *   <li>国产兼容端点（{@code dashscope}/{@code qwen}、{@code ark}/{@code doubao}、{@code zhipu}/{@code glm}、{@code moonshot}/{@code kimi}）
 * </ul>
 */
public class OpenAiChatModelBuilderSpi implements ChatModelBuilderSpi {

    private static final Set<ModelProviderType> SUPPORTED_TYPES =
            Set.of(
                    ModelProviderType.OPENAI,
                    ModelProviderType.AZURE_OPENAI,
                    ModelProviderType.DOCKER_MODEL_RUNNER,
                    ModelProviderType.GROQ,
                    ModelProviderType.MINIMAX,
                    ModelProviderType.NVIDIA,
                    ModelProviderType.PERPLEXITY_AI,
                    ModelProviderType.OCI_GENERATIVE_AI);

    private static final Map<String, String> DOMESTIC_BASE_URLS =
            Map.of(
                    "dashscope", "https://dashscope.aliyuncs.com/compatible-mode",
                    "qwen", "https://dashscope.aliyuncs.com/compatible-mode",
                    "ark", "https://ark.cn-beijing.volces.com/api/v3",
                    "doubao", "https://ark.cn-beijing.volces.com/api/v3",
                    "zhipu", "https://open.bigmodel.cn/api/paas/v4",
                    "glm", "https://open.bigmodel.cn/api/paas/v4",
                    "moonshot", "https://api.moonshot.cn",
                    "kimi", "https://api.moonshot.cn");

    @Override
    public String provider() {
        return ModelProviderType.OPENAI.getCode();
    }

    @Override
    public boolean supports(String provider) {
        return ModelProviderType.fromCode(provider).map(SUPPORTED_TYPES::contains).orElse(false);
    }

    @Override
    @SuppressWarnings("unchecked")
    public ChatModel build(ModelConfig config) {
        String rawProvider = config.provider().strip().toLowerCase(Locale.ROOT);
        ModelProviderType matchedType =
                ModelProviderType.fromCode(rawProvider).orElse(ModelProviderType.OPENAI);

        String defaultBaseUrl =
                DOMESTIC_BASE_URLS.getOrDefault(rawProvider, matchedType.getDefaultBaseUrl());
        String resolvedBaseUrl = config.resolveBaseUrl(defaultBaseUrl);
        String resolvedModel = config.resolveModel(matchedType.getDefaultModel());

        String apiKey = config.apiKey();
        if ((apiKey == null || apiKey.isBlank())
                && matchedType != ModelProviderType.DOCKER_MODEL_RUNNER) {
            throw IntelliHubExceptionUtil.invalidParamException(
                    "构建 {} ChatModel 失败：apiKey 不能为空", config.provider());
        }
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = "dmr-local";
        }

        OpenAiChatOptions.Builder optionsBuilder =
                OpenAiChatOptions.builder().apiKey(apiKey).model(resolvedModel);

        if (resolvedBaseUrl != null && !resolvedBaseUrl.isBlank()) {
            optionsBuilder.baseUrl(resolvedBaseUrl);
        }

        boolean isAzure =
                matchedType == ModelProviderType.AZURE_OPENAI
                        || config.getExtraBoolean("azure").orElse(false);
        if (isAzure) {
            optionsBuilder.azure(true);
            optionsBuilder.deploymentName(
                    config.getExtraString(ModelConfig.EXTRA_DEPLOYMENT_NAME, resolvedModel));
        }

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
        if (config.timeout() != null) {
            optionsBuilder.timeout(config.timeout());
        }
        if (config.maxRetries() != null) {
            optionsBuilder.maxRetries(config.maxRetries());
        }
        if (!config.customHeaders().isEmpty()) {
            optionsBuilder.customHeaders(config.customHeaders());
        }

        config.getExtraString(ModelConfig.EXTRA_ORGANIZATION_ID)
                .ifPresent(optionsBuilder::organizationId);
        config.getExtraString(ModelConfig.EXTRA_REASONING_EFFORT)
                .ifPresent(optionsBuilder::reasoningEffort);
        config.getExtra("extraBody", Map.class).ifPresent(optionsBuilder::extraBody);

        return OpenAiChatModel.builder().options(optionsBuilder.build()).build();
    }
}
