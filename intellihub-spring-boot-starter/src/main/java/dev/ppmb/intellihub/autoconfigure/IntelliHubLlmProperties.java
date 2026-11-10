package dev.ppmb.intellihub.autoconfigure;

import dev.ppmb.intellihub.llm.config.ModelConfig;
import dev.ppmb.intellihub.llm.factory.ChatModelFactory;
import dev.ppmb.intellihub.llm.router.ApiKeyPool;
import dev.ppmb.intellihub.llm.router.RoutingChatModel;
import dev.ppmb.intellihub.llm.router.RoutingStrategy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Intelli-Hub LLM 多渠道、API Key 池与高可用路由组配置属性（{@code intellihub.llm.*}）。
 */
@ConfigurationProperties(prefix = "intellihub.llm")
public class IntelliHubLlmProperties {

    /** 默认使用的模型渠道名或高可用路由组名 */
    private String defaultChannel;

    /** ChatModel 实例本地缓存配置 */
    private CacheProperties cache = new CacheProperties();

    /** 命名模型渠道配置字典（key 为渠道名称，如 deepseek-primary、claude-sonnet、qwen-backup） */
    private Map<String, ChannelProperties> channels = new LinkedHashMap<>();

    /** 命名高可用路由组配置字典（key 为路由组名称，如 default-ha、reasoning-ha） */
    private Map<String, RouteGroupProperties> routes = new LinkedHashMap<>();

    public String getDefaultChannel() {
        return defaultChannel;
    }

    public void setDefaultChannel(String defaultChannel) {
        this.defaultChannel = defaultChannel;
    }

    public CacheProperties getCache() {
        return cache;
    }

    public void setCache(CacheProperties cache) {
        this.cache = cache != null ? cache : new CacheProperties();
    }

    public Map<String, ChannelProperties> getChannels() {
        return channels;
    }

    public void setChannels(Map<String, ChannelProperties> channels) {
        this.channels = channels != null ? channels : new LinkedHashMap<>();
    }

    public Map<String, RouteGroupProperties> getRoutes() {
        return routes;
    }

    public void setRoutes(Map<String, RouteGroupProperties> routes) {
        this.routes = routes != null ? routes : new LinkedHashMap<>();
    }

    /**
     * {@link ChatModelFactory} 实例缓存配置。
     */
    public static class CacheProperties {
        private int maxSize = ChatModelFactory.DEFAULT_MAX_CACHE_SIZE;
        private Duration idleTtl = ChatModelFactory.DEFAULT_CACHE_IDLE_TTL;

        public int getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(int maxSize) {
            this.maxSize = maxSize;
        }

        public Duration getIdleTtl() {
            return idleTtl;
        }

        public void setIdleTtl(Duration idleTtl) {
            this.idleTtl = idleTtl;
        }
    }

    /**
     * 单个命名模型渠道配置（支持单 {@code apiKey} 或多 {@code apiKeys} 轮询池）。
     */
    public static class ChannelProperties {
        private String provider;
        private String apiKey;
        private List<String> apiKeys = new ArrayList<>();
        private Duration keyCooldown = ApiKeyPool.DEFAULT_COOLDOWN;
        private String baseUrl;
        private String model;
        private Double temperature;
        private Double topP;
        private Integer topK;
        private Integer maxTokens;
        private Double frequencyPenalty;
        private Double presencePenalty;
        private List<String> stopSequences = new ArrayList<>();
        private Duration timeout;
        private Integer maxRetries;
        private Map<String, String> customHeaders = new LinkedHashMap<>();
        private Map<String, Object> extras = new LinkedHashMap<>();

        public ModelConfig toModelConfig() {
            return ModelConfig.builder()
                    .provider(provider)
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .model(model)
                    .temperature(temperature)
                    .topP(topP)
                    .topK(topK)
                    .maxTokens(maxTokens)
                    .frequencyPenalty(frequencyPenalty)
                    .presencePenalty(presencePenalty)
                    .stopSequences(stopSequences)
                    .timeout(timeout)
                    .maxRetries(maxRetries)
                    .customHeaders(customHeaders)
                    .extras(extras)
                    .build();
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public List<String> getApiKeys() {
            return apiKeys;
        }

        public void setApiKeys(List<String> apiKeys) {
            this.apiKeys = apiKeys != null ? apiKeys : new ArrayList<>();
        }

        public Duration getKeyCooldown() {
            return keyCooldown;
        }

        public void setKeyCooldown(Duration keyCooldown) {
            this.keyCooldown = keyCooldown;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public Double getTemperature() {
            return temperature;
        }

        public void setTemperature(Double temperature) {
            this.temperature = temperature;
        }

        public Double getTopP() {
            return topP;
        }

        public void setTopP(Double topP) {
            this.topP = topP;
        }

        public Integer getTopK() {
            return topK;
        }

        public void setTopK(Integer topK) {
            this.topK = topK;
        }

        public Integer getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
        }

        public Double getFrequencyPenalty() {
            return frequencyPenalty;
        }

        public void setFrequencyPenalty(Double frequencyPenalty) {
            this.frequencyPenalty = frequencyPenalty;
        }

        public Double getPresencePenalty() {
            return presencePenalty;
        }

        public void setPresencePenalty(Double presencePenalty) {
            this.presencePenalty = presencePenalty;
        }

        public List<String> getStopSequences() {
            return stopSequences;
        }

        public void setStopSequences(List<String> stopSequences) {
            this.stopSequences = stopSequences != null ? stopSequences : new ArrayList<>();
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public Integer getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(Integer maxRetries) {
            this.maxRetries = maxRetries;
        }

        public Map<String, String> getCustomHeaders() {
            return customHeaders;
        }

        public void setCustomHeaders(Map<String, String> customHeaders) {
            this.customHeaders = customHeaders != null ? customHeaders : new LinkedHashMap<>();
        }

        public Map<String, Object> getExtras() {
            return extras;
        }

        public void setExtras(Map<String, Object> extras) {
            this.extras = extras != null ? extras : new LinkedHashMap<>();
        }
    }

    /**
     * 高可用路由组配置。
     */
    public static class RouteGroupProperties {
        private RoutingStrategy strategy = RoutingStrategy.FAILOVER;
        private int failureThreshold = RoutingChatModel.DEFAULT_FAILURE_THRESHOLD;
        private Duration cooldown = RoutingChatModel.DEFAULT_COOLDOWN;
        private boolean failoverOnAuthError = true;

        /** 简写候选渠道名列表（权重均默认为 1） */
        private List<String> channels = new ArrayList<>();

        /** 带权重的候选目标列表 */
        private List<RouteTargetProperties> targets = new ArrayList<>();

        public RoutingStrategy getStrategy() {
            return strategy;
        }

        public void setStrategy(RoutingStrategy strategy) {
            this.strategy = strategy != null ? strategy : RoutingStrategy.FAILOVER;
        }

        public int getFailureThreshold() {
            return failureThreshold;
        }

        public void setFailureThreshold(int failureThreshold) {
            this.failureThreshold = failureThreshold;
        }

        public Duration getCooldown() {
            return cooldown;
        }

        public void setCooldown(Duration cooldown) {
            this.cooldown = cooldown;
        }

        public boolean isFailoverOnAuthError() {
            return failoverOnAuthError;
        }

        public void setFailoverOnAuthError(boolean failoverOnAuthError) {
            this.failoverOnAuthError = failoverOnAuthError;
        }

        public List<String> getChannels() {
            return channels;
        }

        public void setChannels(List<String> channels) {
            this.channels = channels != null ? channels : new ArrayList<>();
        }

        public List<RouteTargetProperties> getTargets() {
            return targets;
        }

        public void setTargets(List<RouteTargetProperties> targets) {
            this.targets = targets != null ? targets : new ArrayList<>();
        }
    }

    /**
     * 带权重的路由候选目标配置。
     */
    public static class RouteTargetProperties {
        private String channel;
        private int weight = 1;

        public String getChannel() {
            return channel;
        }

        public void setChannel(String channel) {
            this.channel = channel;
        }

        public int getWeight() {
            return weight;
        }

        public void setWeight(int weight) {
            this.weight = weight;
        }
    }
}
