package dev.ppmb.intellihub.llm.spi.dialect;

import dev.ppmb.intellihub.llm.config.ModelConfig;
import dev.ppmb.intellihub.llm.config.ModelProviderType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * 内置的 {@link OpenAiDialectCustomizer} 方言微调器集合。
 */
public final class OpenAiDialectCustomizers {

    private OpenAiDialectCustomizers() {}

    /**
     * 返回默认注册的全部内置方言微调器列表。
     */
    public static List<OpenAiDialectCustomizer> defaults() {
        return List.of(
                new LocalAndSelfHostedDialectCustomizer(),
                new AzureOpenAiDialectCustomizer(),
                new DomesticOpenAiDialectCustomizer(),
                new PerplexityOpenAiDialectCustomizer());
    }

    /**
     * 本地推理与私有兼容网关方言微调器（Docker Model Runner、vLLM、SGLang、OneAPI、LiteLLM 等）。
     *
     * <p>允许在无鉴权内网环境下免填 {@code apiKey}（自动填充占位凭证）。
     */
    public static final class LocalAndSelfHostedDialectCustomizer
            implements OpenAiDialectCustomizer {

        @Override
        public boolean supports(
                String rawProvider, ModelProviderType matchedType, ModelConfig config) {
            return matchedType == ModelProviderType.DOCKER_MODEL_RUNNER
                    || matchedType == ModelProviderType.OPENAI_COMPATIBLE
                    || ModelProviderType.isDynamicOpenAiCompatible(rawProvider)
                    || config.getExtraBoolean(ModelConfig.EXTRA_OPENAI_COMPATIBLE).orElse(false);
        }

        @Override
        public ModelConfig sanitizeConfig(
                String rawProvider, ModelProviderType matchedType, ModelConfig config) {
            if (config.apiKey() != null && !config.apiKey().isBlank()) {
                return config;
            }
            String fallbackKey =
                    matchedType == ModelProviderType.DOCKER_MODEL_RUNNER ? "dmr-local" : "EMPTY";
            return config.toBuilder().apiKey(fallbackKey).build();
        }
    }

    /**
     * Azure OpenAI 方言微调器：启用 Azure 协议模式并绑定 {@code deploymentName}。
     */
    public static final class AzureOpenAiDialectCustomizer implements OpenAiDialectCustomizer {

        @Override
        public boolean supports(
                String rawProvider, ModelProviderType matchedType, ModelConfig config) {
            return matchedType == ModelProviderType.AZURE_OPENAI
                    || config.getExtraBoolean("azure").orElse(false);
        }

        @Override
        public void customizeOptions(
                String rawProvider,
                ModelProviderType matchedType,
                ModelConfig sanitizedConfig,
                OpenAiChatOptions.Builder optionsBuilder) {
            String resolvedModel =
                    sanitizedConfig.resolveModel(ModelProviderType.AZURE_OPENAI.getDefaultModel());
            optionsBuilder.azure(true);
            optionsBuilder.deploymentName(
                    sanitizedConfig.getExtraString(
                            ModelConfig.EXTRA_DEPLOYMENT_NAME, resolvedModel));
        }
    }

    /**
     * 国产 OpenAI 兼容端点方言微调器（千问 DashScope、火山方舟豆包 Ark、智谱 GLM、月之暗面 Kimi、MiniMax）。
     *
     * <ul>
     *   <li>自动解析各厂商官方 OpenAI 兼容端点与默认模型；
     *   <li>针对智谱/MiniMax/Kimi 清洗不支持的 {@code reasoningEffort} 及惩罚参数，避免上游返回 {@code 400 Bad Request}；
     *   <li>针对千问（DashScope）与豆包（Ark）将 {@code thinkingEnabled} / {@code thinkingBudget} 自动转换为
     *       {@code extraBody} 扩展字段。
     * </ul>
     */
    public static final class DomesticOpenAiDialectCustomizer implements OpenAiDialectCustomizer {

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

        private static final Map<String, String> DOMESTIC_DEFAULT_MODELS =
                Map.of(
                        "dashscope", "qwen-plus",
                        "qwen", "qwen-plus",
                        "ark", "doubao-1-5-pro-32k-250115",
                        "doubao", "doubao-1-5-pro-32k-250115",
                        "zhipu", "glm-4-flash",
                        "glm", "glm-4-flash",
                        "moonshot", "moonshot-v1-8k",
                        "kimi", "moonshot-v1-8k");

        private static final Set<String> NO_REASONING_EFFORT_PROVIDERS =
                Set.of("zhipu", "glm", "moonshot", "kimi", "minimax");

        private static final Set<String> THINKING_EXTRA_BODY_PROVIDERS =
                Set.of("dashscope", "qwen", "ark", "doubao");

        @Override
        public boolean supports(
                String rawProvider, ModelProviderType matchedType, ModelConfig config) {
            return matchedType == ModelProviderType.MINIMAX
                    || DOMESTIC_BASE_URLS.containsKey(rawProvider);
        }

        @Override
        public ModelConfig sanitizeConfig(
                String rawProvider, ModelProviderType matchedType, ModelConfig config) {
            ModelConfig.Builder builder = config.toBuilder();
            if (config.baseUrl() == null || config.baseUrl().isBlank()) {
                String defaultUrl = DOMESTIC_BASE_URLS.get(rawProvider);
                if (defaultUrl != null) {
                    builder.baseUrl(defaultUrl);
                }
            }
            if (config.model() == null || config.model().isBlank()) {
                String defaultModel = DOMESTIC_DEFAULT_MODELS.get(rawProvider);
                if (defaultModel != null) {
                    builder.model(defaultModel);
                }
            }
            if (NO_REASONING_EFFORT_PROVIDERS.contains(rawProvider)) {
                builder.withoutExtra(ModelConfig.EXTRA_REASONING_EFFORT);
            }
            if (matchedType == ModelProviderType.MINIMAX || "minimax".equals(rawProvider)) {
                builder.frequencyPenalty(null).presencePenalty(null);
            }
            return builder.build();
        }

        @Override
        @SuppressWarnings("unchecked")
        public void customizeOptions(
                String rawProvider,
                ModelProviderType matchedType,
                ModelConfig sanitizedConfig,
                OpenAiChatOptions.Builder optionsBuilder) {
            if (!THINKING_EXTRA_BODY_PROVIDERS.contains(rawProvider)) {
                return;
            }
            Boolean thinkingEnabled =
                    sanitizedConfig
                            .getExtraBoolean(ModelConfig.EXTRA_THINKING_ENABLED)
                            .orElse(null);
            Integer thinkingBudget =
                    sanitizedConfig.getExtraInt(ModelConfig.EXTRA_THINKING_BUDGET).orElse(null);
            if (thinkingEnabled == null && thinkingBudget == null) {
                return;
            }
            Map<String, Object> extraBody = new LinkedHashMap<>();
            sanitizedConfig
                    .getExtra("extraBody", Map.class)
                    .ifPresent(existing -> extraBody.putAll((Map<String, Object>) existing));
            if (thinkingEnabled != null) {
                extraBody.put("enable_thinking", thinkingEnabled);
            }
            if (thinkingBudget != null) {
                extraBody.put("thinking_budget", thinkingBudget);
            }
            optionsBuilder.extraBody(extraBody);
        }
    }

    /**
     * Perplexity AI (Sonar) 方言微调器：清洗不支持的 {@code reasoningEffort} 与冲突的惩罚字段。
     */
    public static final class PerplexityOpenAiDialectCustomizer implements OpenAiDialectCustomizer {

        @Override
        public boolean supports(
                String rawProvider, ModelProviderType matchedType, ModelConfig config) {
            return matchedType == ModelProviderType.PERPLEXITY_AI;
        }

        @Override
        public ModelConfig sanitizeConfig(
                String rawProvider, ModelProviderType matchedType, ModelConfig config) {
            ModelConfig.Builder builder =
                    config.toBuilder().withoutExtra(ModelConfig.EXTRA_REASONING_EFFORT);
            // Perplexity Sonar API 不允许同时传入 frequency_penalty 与 presence_penalty
            if (config.frequencyPenalty() != null && config.presencePenalty() != null) {
                builder.presencePenalty(null);
            }
            return builder.build();
        }
    }
}
