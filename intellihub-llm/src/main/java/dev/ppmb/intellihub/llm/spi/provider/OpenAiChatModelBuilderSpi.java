package dev.ppmb.intellihub.llm.spi.provider;

import dev.ppmb.intellihub.core.exception.IntelliHubExceptionUtil;
import dev.ppmb.intellihub.llm.config.ModelCapability;
import dev.ppmb.intellihub.llm.config.ModelConfig;
import dev.ppmb.intellihub.llm.config.ModelProviderType;
import dev.ppmb.intellihub.llm.spi.ChatModelBuilderSpi;
import dev.ppmb.intellihub.llm.spi.dialect.OpenAiDialectCustomizer;
import dev.ppmb.intellihub.llm.spi.dialect.OpenAiDialectCustomizers;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * 基于 Spring AI 原生 {@link OpenAiChatModel} 的构建器，覆盖 OpenAI、官方文档中所有 OpenAI 协议兼容渠道及企业私有网关：
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
 *   <li>通用自定义/私有推理集群（{@code openai-compatible}、{@code vllm}、{@code sglang}、{@code oneapi}、{@code litellm}、{@code openai-compatible:*}）
 * </ul>
 */
public class OpenAiChatModelBuilderSpi implements ChatModelBuilderSpi {

    private static final Set<ModelProviderType> SUPPORTED_TYPES =
            Set.of(
                    ModelProviderType.OPENAI,
                    ModelProviderType.OPENAI_COMPATIBLE,
                    ModelProviderType.AZURE_OPENAI,
                    ModelProviderType.DOCKER_MODEL_RUNNER,
                    ModelProviderType.GROQ,
                    ModelProviderType.MINIMAX,
                    ModelProviderType.NVIDIA,
                    ModelProviderType.PERPLEXITY_AI,
                    ModelProviderType.OCI_GENERATIVE_AI);

    private static final Set<String> NON_REASONING_DOMESTIC_ALIASES =
            Set.of("zhipu", "glm", "moonshot", "kimi");

    private final List<OpenAiDialectCustomizer> dialectCustomizers =
            new CopyOnWriteArrayList<>(OpenAiDialectCustomizers.defaults());

    /**
     * 注册自定义 {@link OpenAiDialectCustomizer}（后注册的方言微调器优先执行）。
     */
    public OpenAiChatModelBuilderSpi registerDialectCustomizer(OpenAiDialectCustomizer customizer) {
        Objects.requireNonNull(customizer, "customizer must not be null");
        this.dialectCustomizers.add(0, customizer);
        return this;
    }

    public List<OpenAiDialectCustomizer> getDialectCustomizers() {
        return Collections.unmodifiableList(dialectCustomizers);
    }

    @Override
    public String provider() {
        return ModelProviderType.OPENAI.getCode();
    }

    @Override
    public boolean supports(String provider) {
        if (provider == null || provider.isBlank()) {
            return false;
        }
        if (ModelProviderType.isDynamicOpenAiCompatible(provider)) {
            return true;
        }
        return ModelProviderType.fromCode(provider).map(SUPPORTED_TYPES::contains).orElse(false);
    }

    @Override
    public Set<ModelCapability> capabilities(String provider) {
        if (provider == null || provider.isBlank()) {
            return capabilities();
        }
        String raw = provider.strip().toLowerCase(Locale.ROOT);
        if (NON_REASONING_DOMESTIC_ALIASES.contains(raw)) {
            EnumSet<ModelCapability> caps =
                    EnumSet.copyOf(ModelProviderType.OPENAI.getCapabilities());
            caps.remove(ModelCapability.REASONING_EFFORT);
            caps.remove(ModelCapability.THINKING_SWITCH);
            caps.remove(ModelCapability.THINKING_BUDGET);
            return Collections.unmodifiableSet(caps);
        }
        if (ModelProviderType.isDynamicOpenAiCompatible(raw)) {
            return ModelProviderType.OPENAI_COMPATIBLE.getCapabilities();
        }
        return ModelProviderType.fromCode(raw)
                .map(ModelProviderType::getCapabilities)
                .orElseGet(ModelProviderType.OPENAI_COMPATIBLE::getCapabilities);
    }

    @Override
    @SuppressWarnings("unchecked")
    public OpenAiChatOptions buildOptions(ModelConfig config) {
        String rawProvider = config.provider().strip().toLowerCase(Locale.ROOT);
        ModelProviderType matchedType =
                ModelProviderType.fromCode(rawProvider).orElse(ModelProviderType.OPENAI_COMPATIBLE);

        ModelConfig sanitized = config;
        for (OpenAiDialectCustomizer customizer : dialectCustomizers) {
            if (customizer.supports(rawProvider, matchedType, sanitized)) {
                sanitized = customizer.sanitizeConfig(rawProvider, matchedType, sanitized);
            }
        }

        String resolvedBaseUrl = sanitized.resolveBaseUrl(matchedType.getDefaultBaseUrl());
        String resolvedModel = sanitized.resolveModel(matchedType.getDefaultModel());

        String apiKey = sanitized.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw IntelliHubExceptionUtil.invalidParamException(
                    "构建 {} ChatModel 失败：apiKey 不能为空", config.provider());
        }

        OpenAiChatOptions.Builder optionsBuilder =
                OpenAiChatOptions.builder().apiKey(apiKey).model(resolvedModel);

        if (resolvedBaseUrl != null && !resolvedBaseUrl.isBlank()) {
            optionsBuilder.baseUrl(resolvedBaseUrl);
        }
        if (sanitized.temperature() != null) {
            optionsBuilder.temperature(sanitized.temperature());
        }
        if (sanitized.topP() != null) {
            optionsBuilder.topP(sanitized.topP());
        }
        if (sanitized.maxTokens() != null) {
            optionsBuilder.maxTokens(sanitized.maxTokens());
        }
        if (sanitized.frequencyPenalty() != null) {
            optionsBuilder.frequencyPenalty(sanitized.frequencyPenalty());
        }
        if (sanitized.presencePenalty() != null) {
            optionsBuilder.presencePenalty(sanitized.presencePenalty());
        }
        if (!sanitized.stopSequences().isEmpty()) {
            optionsBuilder.stop(sanitized.stopSequences());
        }
        if (sanitized.timeout() != null) {
            optionsBuilder.timeout(sanitized.timeout());
        }
        if (sanitized.maxRetries() != null) {
            optionsBuilder.maxRetries(sanitized.maxRetries());
        }
        if (!sanitized.customHeaders().isEmpty()) {
            optionsBuilder.customHeaders(sanitized.customHeaders());
        }

        sanitized
                .getExtraString(ModelConfig.EXTRA_ORGANIZATION_ID)
                .ifPresent(optionsBuilder::organizationId);
        sanitized
                .getExtraString(ModelConfig.EXTRA_REASONING_EFFORT)
                .ifPresent(optionsBuilder::reasoningEffort);
        sanitized.getExtra("extraBody", Map.class).ifPresent(optionsBuilder::extraBody);

        for (OpenAiDialectCustomizer customizer : dialectCustomizers) {
            if (customizer.supports(rawProvider, matchedType, sanitized)) {
                customizer.customizeOptions(rawProvider, matchedType, sanitized, optionsBuilder);
            }
        }

        return optionsBuilder.build();
    }

    @Override
    public ChatModel build(ModelConfig config) {
        return OpenAiChatModel.builder().options(buildOptions(config)).build();
    }
}
