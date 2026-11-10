package dev.ppmb.intellihub.llm.config;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Spring AI 2.0.2 原生支持的 ChatModel 模型厂商枚举（对应官方文档 Chat Models 全家族）。
 */
public enum ModelProviderType {
    AMAZON_BEDROCK_CONVERSE(
            "bedrock-converse",
            List.of("bedrock", "aws-bedrock"),
            null,
            "us.anthropic.claude-sonnet-4-20250514-v1:0"),
    ANTHROPIC(
            "anthropic",
            List.of("claude"),
            "https://api.anthropic.com",
            "claude-sonnet-4-20250514"),
    AZURE_OPENAI("azure-openai", List.of("azure"), null, "gpt-4o"),
    DEEPSEEK("deepseek", List.of(), "https://api.deepseek.com", "deepseek-chat"),
    DOCKER_MODEL_RUNNER(
            "docker-model-runner", List.of("dmr"), "http://localhost:12434/engines", "ai/smollm2"),
    GOOGLE_GENAI("google-genai", List.of("gemini", "google"), null, "gemini-2.5-flash"),
    GROQ("groq", List.of(), "https://api.groq.com/openai", "llama-3.3-70b-versatile"),
    MISTRAL_AI("mistral-ai", List.of("mistral"), "https://api.mistral.ai", "mistral-small-latest"),
    MINIMAX("minimax", List.of(), "https://api.minimax.chat", "MiniMax-Text-01"),
    NVIDIA(
            "nvidia",
            List.of("nim"),
            "https://integrate.api.nvidia.com",
            "meta/llama-3.3-70b-instruct"),
    OLLAMA("ollama", List.of(), "http://localhost:11434", "qwen3:8b"),
    PERPLEXITY_AI("perplexity", List.of("perplexity-ai"), "https://api.perplexity.ai", "sonar"),
    OCI_GENERATIVE_AI("oci-genai", List.of("oci"), null, "cohere.command-r-plus"),
    OPENAI(
            "openai",
            List.of("dashscope", "qwen", "ark", "doubao", "zhipu", "glm", "moonshot", "kimi"),
            "https://api.openai.com",
            "gpt-4o-mini"),
    QXOTIC_JINFER(
            "jinfer",
            List.of("qxotic-jinfer", "qxotic"),
            null,
            "LiquidAI/LFM2.5-1.2B-Instruct-GGUF:Q8_0");

    private final String code;
    private final List<String> aliases;
    private final String defaultBaseUrl;
    private final String defaultModel;

    ModelProviderType(
            String code, List<String> aliases, String defaultBaseUrl, String defaultModel) {
        this.code = code;
        this.aliases = aliases;
        this.defaultBaseUrl = defaultBaseUrl;
        this.defaultModel = defaultModel;
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

    public boolean matches(String provider) {
        if (provider == null || provider.isBlank()) {
            return false;
        }
        String normalized = provider.strip().toLowerCase(Locale.ROOT);
        if (code.equals(normalized)) {
            return true;
        }
        return aliases.contains(normalized);
    }

    public static Optional<ModelProviderType> fromCode(String provider) {
        if (provider == null || provider.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(type -> type.matches(provider)).findFirst();
    }
}
