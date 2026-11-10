package dev.ppmb.intellihub.llm.config;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Spring AI 2.0.2 原生支持的 ChatModel 模型厂商枚举（对应官方文档 15 大原生家族 + 通用 OpenAI 兼容私有网关）。
 */
public enum ModelProviderType {
    AMAZON_BEDROCK_CONVERSE(
            "bedrock-converse",
            List.of("bedrock", "aws-bedrock"),
            null,
            "us.anthropic.claude-sonnet-4-20250514-v1:0",
            EnumSet.of(
                    ModelCapability.STREAMING,
                    ModelCapability.TOOL_CALLING,
                    ModelCapability.VISION,
                    ModelCapability.STRUCTURED_OUTPUT,
                    ModelCapability.TOP_K_SAMPLING)),
    ANTHROPIC(
            "anthropic",
            List.of("claude"),
            "https://api.anthropic.com",
            "claude-sonnet-4-20250514",
            EnumSet.of(
                    ModelCapability.STREAMING,
                    ModelCapability.TOOL_CALLING,
                    ModelCapability.VISION,
                    ModelCapability.STRUCTURED_OUTPUT,
                    ModelCapability.THINKING_SWITCH,
                    ModelCapability.THINKING_BUDGET,
                    ModelCapability.TOP_K_SAMPLING,
                    ModelCapability.CUSTOM_BASE_URL)),
    AZURE_OPENAI(
            "azure-openai",
            List.of("azure"),
            null,
            "gpt-4o",
            EnumSet.of(
                    ModelCapability.STREAMING,
                    ModelCapability.TOOL_CALLING,
                    ModelCapability.VISION,
                    ModelCapability.STRUCTURED_OUTPUT,
                    ModelCapability.REASONING_EFFORT,
                    ModelCapability.PENALTY_PARAMETERS,
                    ModelCapability.CUSTOM_BASE_URL)),
    DEEPSEEK(
            "deepseek",
            List.of(),
            "https://api.deepseek.com",
            "deepseek-chat",
            EnumSet.of(
                    ModelCapability.STREAMING,
                    ModelCapability.TOOL_CALLING,
                    ModelCapability.STRUCTURED_OUTPUT,
                    ModelCapability.THINKING_SWITCH,
                    ModelCapability.PENALTY_PARAMETERS,
                    ModelCapability.CUSTOM_BASE_URL)),
    DOCKER_MODEL_RUNNER(
            "docker-model-runner",
            List.of("dmr"),
            "http://localhost:12434/engines",
            "ai/smollm2",
            EnumSet.of(
                    ModelCapability.STREAMING,
                    ModelCapability.TOOL_CALLING,
                    ModelCapability.STRUCTURED_OUTPUT,
                    ModelCapability.PENALTY_PARAMETERS,
                    ModelCapability.CUSTOM_BASE_URL)),
    GOOGLE_GENAI(
            "google-genai",
            List.of("gemini", "google"),
            null,
            "gemini-2.5-flash",
            EnumSet.of(
                    ModelCapability.STREAMING,
                    ModelCapability.TOOL_CALLING,
                    ModelCapability.VISION,
                    ModelCapability.STRUCTURED_OUTPUT,
                    ModelCapability.THINKING_SWITCH,
                    ModelCapability.THINKING_BUDGET,
                    ModelCapability.TOP_K_SAMPLING,
                    ModelCapability.CUSTOM_BASE_URL)),
    GROQ(
            "groq",
            List.of(),
            "https://api.groq.com/openai",
            "llama-3.3-70b-versatile",
            EnumSet.of(
                    ModelCapability.STREAMING,
                    ModelCapability.TOOL_CALLING,
                    ModelCapability.VISION,
                    ModelCapability.STRUCTURED_OUTPUT,
                    ModelCapability.REASONING_EFFORT,
                    ModelCapability.PENALTY_PARAMETERS,
                    ModelCapability.CUSTOM_BASE_URL)),
    MISTRAL_AI(
            "mistral-ai",
            List.of("mistral"),
            "https://api.mistral.ai",
            "mistral-small-latest",
            EnumSet.of(
                    ModelCapability.STREAMING,
                    ModelCapability.TOOL_CALLING,
                    ModelCapability.VISION,
                    ModelCapability.STRUCTURED_OUTPUT,
                    ModelCapability.CUSTOM_BASE_URL)),
    MINIMAX(
            "minimax",
            List.of(),
            "https://api.minimax.chat",
            "MiniMax-Text-01",
            EnumSet.of(
                    ModelCapability.STREAMING,
                    ModelCapability.TOOL_CALLING,
                    ModelCapability.VISION,
                    ModelCapability.STRUCTURED_OUTPUT,
                    ModelCapability.CUSTOM_BASE_URL)),
    NVIDIA(
            "nvidia",
            List.of("nim"),
            "https://integrate.api.nvidia.com",
            "meta/llama-3.3-70b-instruct",
            EnumSet.of(
                    ModelCapability.STREAMING,
                    ModelCapability.TOOL_CALLING,
                    ModelCapability.VISION,
                    ModelCapability.STRUCTURED_OUTPUT,
                    ModelCapability.PENALTY_PARAMETERS,
                    ModelCapability.CUSTOM_BASE_URL)),
    OLLAMA(
            "ollama",
            List.of(),
            "http://localhost:11434",
            "qwen3:8b",
            EnumSet.of(
                    ModelCapability.STREAMING,
                    ModelCapability.TOOL_CALLING,
                    ModelCapability.VISION,
                    ModelCapability.STRUCTURED_OUTPUT,
                    ModelCapability.THINKING_SWITCH,
                    ModelCapability.PENALTY_PARAMETERS,
                    ModelCapability.TOP_K_SAMPLING,
                    ModelCapability.CUSTOM_BASE_URL)),
    PERPLEXITY_AI(
            "perplexity",
            List.of("perplexity-ai"),
            "https://api.perplexity.ai",
            "sonar",
            EnumSet.of(
                    ModelCapability.STREAMING,
                    ModelCapability.STRUCTURED_OUTPUT,
                    ModelCapability.CUSTOM_BASE_URL)),
    OCI_GENERATIVE_AI(
            "oci-genai",
            List.of("oci"),
            null,
            "cohere.command-r-plus",
            EnumSet.of(
                    ModelCapability.STREAMING,
                    ModelCapability.TOOL_CALLING,
                    ModelCapability.STRUCTURED_OUTPUT,
                    ModelCapability.PENALTY_PARAMETERS,
                    ModelCapability.CUSTOM_BASE_URL)),
    OPENAI(
            "openai",
            List.of("dashscope", "qwen", "ark", "doubao", "zhipu", "glm", "moonshot", "kimi"),
            "https://api.openai.com",
            "gpt-4o-mini",
            EnumSet.of(
                    ModelCapability.STREAMING,
                    ModelCapability.TOOL_CALLING,
                    ModelCapability.VISION,
                    ModelCapability.STRUCTURED_OUTPUT,
                    ModelCapability.THINKING_SWITCH,
                    ModelCapability.THINKING_BUDGET,
                    ModelCapability.REASONING_EFFORT,
                    ModelCapability.PENALTY_PARAMETERS,
                    ModelCapability.CUSTOM_BASE_URL)),
    OPENAI_COMPATIBLE(
            "openai-compatible",
            List.of(
                    "vllm",
                    "sglang",
                    "oneapi",
                    "newapi",
                    "litellm",
                    "openrouter",
                    "siliconflow",
                    "custom-openai"),
            "http://localhost:8000",
            "default",
            EnumSet.of(
                    ModelCapability.STREAMING,
                    ModelCapability.TOOL_CALLING,
                    ModelCapability.VISION,
                    ModelCapability.STRUCTURED_OUTPUT,
                    ModelCapability.THINKING_SWITCH,
                    ModelCapability.THINKING_BUDGET,
                    ModelCapability.REASONING_EFFORT,
                    ModelCapability.PENALTY_PARAMETERS,
                    ModelCapability.CUSTOM_BASE_URL)),
    QXOTIC_JINFER(
            "jinfer",
            List.of("qxotic-jinfer", "qxotic"),
            null,
            "LiquidAI/LFM2.5-1.2B-Instruct-GGUF:Q8_0",
            EnumSet.of(
                    ModelCapability.STREAMING,
                    ModelCapability.TOOL_CALLING,
                    ModelCapability.VISION,
                    ModelCapability.THINKING_SWITCH,
                    ModelCapability.THINKING_BUDGET,
                    ModelCapability.TOP_K_SAMPLING,
                    ModelCapability.LOCAL_WEIGHTS));

    /** 动态自定义网关前缀（如 {@code "openai-compatible:my-vllm"} 或 {@code "custom:internal-llm"}） */
    public static final String DYNAMIC_COMPATIBLE_PREFIX = "openai-compatible:";

    public static final String CUSTOM_GATEWAY_PREFIX = "custom:";

    private final String code;
    private final List<String> aliases;
    private final String defaultBaseUrl;
    private final String defaultModel;
    private final Set<ModelCapability> capabilities;

    ModelProviderType(
            String code,
            List<String> aliases,
            String defaultBaseUrl,
            String defaultModel,
            Set<ModelCapability> capabilities) {
        this.code = code;
        this.aliases = aliases;
        this.defaultBaseUrl = defaultBaseUrl;
        this.defaultModel = defaultModel;
        this.capabilities = Collections.unmodifiableSet(EnumSet.copyOf(capabilities));
    }

    public String getCode() {
        return code;
    }

    public List<String> getAliases() {
        return aliases;
    }

    public String getDefaultBaseUrl() {
        return defaultBaseUrl;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public Set<ModelCapability> getCapabilities() {
        return capabilities;
    }

    public boolean supportsCapability(ModelCapability capability) {
        return capability != null && capabilities.contains(capability);
    }

    public boolean matches(String provider) {
        if (provider == null || provider.isBlank()) {
            return false;
        }
        String normalized = provider.strip().toLowerCase(Locale.ROOT);
        if (code.equals(normalized)) {
            return true;
        }
        if (aliases.contains(normalized)) {
            return true;
        }
        return this == OPENAI_COMPATIBLE && isDynamicOpenAiCompatible(normalized);
    }

    /**
     * 判断给定的 {@code provider} 是否属于动态 OpenAI 兼容网关前缀（如 {@code "openai-compatible:vllm-prod"} 或
     * {@code "custom:gateway-1"}）。
     */
    public static boolean isDynamicOpenAiCompatible(String provider) {
        if (provider == null || provider.isBlank()) {
            return false;
        }
        String normalized = provider.strip().toLowerCase(Locale.ROOT);
        return normalized.startsWith(DYNAMIC_COMPATIBLE_PREFIX)
                || normalized.startsWith(CUSTOM_GATEWAY_PREFIX);
    }

    public static Optional<ModelProviderType> fromCode(String provider) {
        if (provider == null || provider.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(type -> type.matches(provider)).findFirst();
    }
}
