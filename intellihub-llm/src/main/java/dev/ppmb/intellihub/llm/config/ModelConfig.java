package dev.ppmb.intellihub.llm.config;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 统一 AI 模型配置对象（支持通用采样参数 + {@code extras} 插件化扩展属性）。
 *
 * <p>配合 {@link dev.ppmb.intellihub.llm.spi.ChatModelBuilderSpi}，既可传递跨模型通用的
 * {@code provider}、{@code apiKey}、{@code baseUrl}、{@code model}、{@code temperature}，
 * 也可通过 {@link #extras()} 传递各原生 SDK 专属参数（如 DeepSeek/Anthropic/Gemini/Ollama 的深度思考开关、
 * Azure 的 {@code deploymentName}、Bedrock 的 {@code region}、Jinfer 的 {@code modelPath} 等）。
 */
public final class ModelConfig {

    // ========== 常用扩展属性 Key 常量 ==========
    /** 是否启用深度思考 / Reasoning（Boolean） */
    public static final String EXTRA_THINKING_ENABLED = "thinkingEnabled";

    /** 思考 Token 预算（Integer / Long，适用于 Anthropic Claude / Google GenAI / Jinfer） */
    public static final String EXTRA_THINKING_BUDGET = "thinkingBudget";

    /** 推理强度（String，如 "low", "medium", "high", "max"，适用于 OpenAI / DeepSeek / Mistral） */
    public static final String EXTRA_REASONING_EFFORT = "reasoningEffort";

    /** Azure OpenAI 部署名称（String） */
    public static final String EXTRA_DEPLOYMENT_NAME = "deploymentName";

    /** OpenAI Organization ID（String） */
    public static final String EXTRA_ORGANIZATION_ID = "organizationId";

    /** AWS Bedrock 区域（String，如 "us-east-1"） */
    public static final String EXTRA_AWS_REGION = "region";

    /** AWS Access Key ID（String） */
    public static final String EXTRA_AWS_ACCESS_KEY_ID = "accessKeyId";

    /** AWS Secret Access Key（String） */
    public static final String EXTRA_AWS_SECRET_ACCESS_KEY = "secretAccessKey";

    /** AWS Session Token（String，可选） */
    public static final String EXTRA_AWS_SESSION_TOKEN = "sessionToken";

    /** Google Vertex AI 模式开关（Boolean） */
    public static final String EXTRA_VERTEX_AI = "vertexAi";

    /** Google Cloud Project ID（String） */
    public static final String EXTRA_GCP_PROJECT = "project";

    /** Google Cloud Location（String，如 "us-central1"） */
    public static final String EXTRA_GCP_LOCATION = "location";

    /** Ollama 上下文窗口大小 num_ctx（Integer） */
    public static final String EXTRA_OLLAMA_NUM_CTX = "numCtx";

    /** Ollama 模型驻留内存时间 keep_alive（String，如 "5m"） */
    public static final String EXTRA_OLLAMA_KEEP_ALIVE = "keepAlive";

    /** Qxotic Jinfer 本地 GGUF 模型文件路径（String / Path） */
    public static final String EXTRA_JINFER_MODEL_PATH = "modelPath";

    /** Qxotic Jinfer 上下文容量（Integer，默认 8192） */
    public static final String EXTRA_JINFER_CONTEXT_CAPACITY = "contextCapacity";

    /** Qxotic Jinfer 保留会话数（Integer） */
    public static final String EXTRA_JINFER_RETAIN_SESSIONS = "retainSessions";

    /** Qxotic Jinfer 投机采样深度（Integer） */
    public static final String EXTRA_JINFER_SPECULATION_DEPTH = "speculationDepth";

    private final String provider;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final Double temperature;
    private final Double topP;
    private final Integer topK;
    private final Integer maxTokens;
    private final Double frequencyPenalty;
    private final Double presencePenalty;
    private final List<String> stopSequences;
    private final Duration timeout;
    private final Integer maxRetries;
    private final Map<String, String> customHeaders;
    private final Map<String, Object> extras;

    private ModelConfig(Builder builder) {
        this.provider = Objects.requireNonNull(builder.provider, "provider must not be null");
        this.apiKey = builder.apiKey;
        this.baseUrl = builder.baseUrl;
        this.model = builder.model;
        this.temperature = builder.temperature;
        this.topP = builder.topP;
        this.topK = builder.topK;
        this.maxTokens = builder.maxTokens;
        this.frequencyPenalty = builder.frequencyPenalty;
        this.presencePenalty = builder.presencePenalty;
        this.stopSequences =
                builder.stopSequences == null ? List.of() : List.copyOf(builder.stopSequences);
        this.timeout = builder.timeout;
        this.maxRetries = builder.maxRetries;
        this.customHeaders =
                builder.customHeaders.isEmpty()
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(builder.customHeaders));
        this.extras =
                builder.extras.isEmpty()
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(builder.extras));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Builder builder(String provider) {
        return new Builder().provider(provider);
    }

    public static Builder builder(ModelProviderType providerType) {
        return new Builder().provider(providerType);
    }

    public static ModelConfig of(String provider, String apiKey, String model) {
        return builder().provider(provider).apiKey(apiKey).model(model).build();
    }

    public String provider() {
        return provider;
    }

    public String apiKey() {
        return apiKey;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String model() {
        return model;
    }

    public Double temperature() {
        return temperature;
    }

    public Double topP() {
        return topP;
    }

    public Integer topK() {
        return topK;
    }

    public Integer maxTokens() {
        return maxTokens;
    }

    public Double frequencyPenalty() {
        return frequencyPenalty;
    }

    public Double presencePenalty() {
        return presencePenalty;
    }

    public List<String> stopSequences() {
        return stopSequences;
    }

    public Duration timeout() {
        return timeout;
    }

    public Integer maxRetries() {
        return maxRetries;
    }

    public Map<String, String> customHeaders() {
        return customHeaders;
    }

    public Map<String, Object> extras() {
        return extras;
    }

    /**
     * 解析有效的 Base URL（优先使用显式配置的 {@code baseUrl}，为空时回退到枚举默认值）。
     */
    public String resolveBaseUrl(String fallbackUrl) {
        if (baseUrl != null && !baseUrl.isBlank()) {
            return baseUrl.strip();
        }
        return fallbackUrl;
    }

    /**
     * 解析有效的模型名称（优先使用显式配置的 {@code model}，为空时回退到枚举默认值）。
     */
    public String resolveModel(String fallbackModel) {
        if (model != null && !model.isBlank()) {
            return model.strip();
        }
        return fallbackModel;
    }

    /**
     * 按指定类型读取扩展属性。
     */
    public <T> Optional<T> getExtra(String key, Class<T> type) {
        Object val = extras.get(key);
        if (val == null) {
            return Optional.empty();
        }
        if (type.isInstance(val)) {
            return Optional.of(type.cast(val));
        }
        return Optional.empty();
    }

    public Optional<String> getExtraString(String key) {
        Object val = extras.get(key);
        if (val == null) {
            return Optional.empty();
        }
        String str = val.toString().strip();
        return str.isEmpty() ? Optional.empty() : Optional.of(str);
    }

    public String getExtraString(String key, String defaultValue) {
        return getExtraString(key).orElse(defaultValue);
    }

    public Optional<Boolean> getExtraBoolean(String key) {
        Object val = extras.get(key);
        if (val instanceof Boolean b) {
            return Optional.of(b);
        }
        if (val instanceof String s && !s.isBlank()) {
            return Optional.of(Boolean.parseBoolean(s.strip()));
        }
        return Optional.empty();
    }

    public Optional<Integer> getExtraInt(String key) {
        Object val = extras.get(key);
        if (val instanceof Number n) {
            return Optional.of(n.intValue());
        }
        if (val instanceof String s && !s.isBlank()) {
            return Optional.of(Integer.parseInt(s.strip()));
        }
        return Optional.empty();
    }

    public Optional<Long> getExtraLong(String key) {
        Object val = extras.get(key);
        if (val instanceof Number n) {
            return Optional.of(n.longValue());
        }
        if (val instanceof String s && !s.isBlank()) {
            return Optional.of(Long.parseLong(s.strip()));
        }
        return Optional.empty();
    }

    public Optional<Double> getExtraDouble(String key) {
        Object val = extras.get(key);
        if (val instanceof Number n) {
            return Optional.of(n.doubleValue());
        }
        if (val instanceof String s && !s.isBlank()) {
            return Optional.of(Double.parseDouble(s.strip()));
        }
        return Optional.empty();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static final class Builder {
        private String provider;
        private String apiKey;
        private String baseUrl;
        private String model;
        private Double temperature;
        private Double topP;
        private Integer topK;
        private Integer maxTokens;
        private Double frequencyPenalty;
        private Double presencePenalty;
        private List<String> stopSequences;
        private Duration timeout;
        private Integer maxRetries;
        private final Map<String, String> customHeaders = new LinkedHashMap<>();
        private final Map<String, Object> extras = new LinkedHashMap<>();

        public Builder() {}

        private Builder(ModelConfig source) {
            this.provider = source.provider;
            this.apiKey = source.apiKey;
            this.baseUrl = source.baseUrl;
            this.model = source.model;
            this.temperature = source.temperature;
            this.topP = source.topP;
            this.topK = source.topK;
            this.maxTokens = source.maxTokens;
            this.frequencyPenalty = source.frequencyPenalty;
            this.presencePenalty = source.presencePenalty;
            this.stopSequences = source.stopSequences;
            this.timeout = source.timeout;
            this.maxRetries = source.maxRetries;
            this.customHeaders.putAll(source.customHeaders);
            this.extras.putAll(source.extras);
        }

        public Builder provider(String provider) {
            this.provider = provider;
            return this;
        }

        public Builder provider(ModelProviderType providerType) {
            this.provider = providerType.getCode();
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder topP(Double topP) {
            this.topP = topP;
            return this;
        }

        public Builder topK(Integer topK) {
            this.topK = topK;
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder frequencyPenalty(Double frequencyPenalty) {
            this.frequencyPenalty = frequencyPenalty;
            return this;
        }

        public Builder presencePenalty(Double presencePenalty) {
            this.presencePenalty = presencePenalty;
            return this;
        }

        public Builder stopSequences(List<String> stopSequences) {
            this.stopSequences = stopSequences;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder maxRetries(Integer maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder header(String name, String value) {
            if (name != null && value != null) {
                this.customHeaders.put(name, value);
            }
            return this;
        }

        public Builder customHeaders(Map<String, String> headers) {
            if (headers != null) {
                this.customHeaders.putAll(headers);
            }
            return this;
        }

        public Builder extra(String key, Object value) {
            if (key != null && value != null) {
                this.extras.put(key, value);
            }
            return this;
        }

        public Builder extras(Map<String, Object> extras) {
            if (extras != null) {
                this.extras.putAll(extras);
            }
            return this;
        }

        public Builder thinkingEnabled(boolean enabled) {
            return extra(EXTRA_THINKING_ENABLED, enabled);
        }

        public Builder thinkingBudget(int tokenBudget) {
            return extra(EXTRA_THINKING_BUDGET, tokenBudget);
        }

        public Builder reasoningEffort(String effort) {
            return extra(EXTRA_REASONING_EFFORT, effort);
        }

        public ModelConfig build() {
            return new ModelConfig(this);
        }
    }
}
