package dev.ppmb.intellihub.llm.factory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import dev.ppmb.intellihub.core.advisor.AiLocalCacheAdvisor;
import dev.ppmb.intellihub.core.advisor.AiLoggingAndMetricsAdvisor;
import dev.ppmb.intellihub.core.context.SpringContextHolder;
import dev.ppmb.intellihub.core.exception.GlobalErrorCodeConstants;
import dev.ppmb.intellihub.core.exception.IntelliHubExceptionUtil;
import dev.ppmb.intellihub.llm.config.ModelCapability;
import dev.ppmb.intellihub.llm.config.ModelConfig;
import dev.ppmb.intellihub.llm.config.ModelProviderType;
import dev.ppmb.intellihub.llm.spi.ChatModelBuilderSpi;
import dev.ppmb.intellihub.llm.spi.support.SharedHttpClientSupport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.DisposableBean;

/**
 * 基于 {@link ChatModelBuilderSpi} 的插件化 {@link ChatModel} / {@link ChatClient} 工厂（内置 Caffeine
 * 实例缓存与生命周期销毁管理）。
 *
 * <ul>
 *   <li>自动通过 Java {@link ServiceLoader} 加载所有内置与第三方扩展的 {@link ChatModelBuilderSpi}；
 *   <li>支持运行时通过 {@link #register(ChatModelBuilderSpi)} 动态注册或覆盖指定厂商构建器；
 *   <li>内置基于指纹的 Caffeine 本地实例缓存，避免每次请求重复创建 HTTP 连接池或重复加载数 GB 本地模型权重；
 *   <li>在缓存淘汰或容器关闭（{@link #close()} / {@link #destroy()}）时，自动调用 {@link
 *       SharedHttpClientSupport#closeQuietly(ChatModel)} 优雅释放底层连接池、SDK 客户端与显存/内存；
 *   <li>支持 {@link ModelCapability} 能力发现与配置参数兼容性校验。
 * </ul>
 */
public class ChatModelFactory implements AutoCloseable, DisposableBean {

    public static final int DEFAULT_MAX_CACHE_SIZE = 256;
    public static final Duration DEFAULT_CACHE_IDLE_TTL = Duration.ofHours(2);

    private static final Logger log = LoggerFactory.getLogger(ChatModelFactory.class);
    private static final ChatModelFactory DEFAULT_INSTANCE = new ChatModelFactory();

    private final List<ChatModelBuilderSpi> builders = new CopyOnWriteArrayList<>();
    private final Cache<String, ChatModel> modelCache;

    public ChatModelFactory() {
        this(loadFromServiceLoader(), DEFAULT_MAX_CACHE_SIZE, DEFAULT_CACHE_IDLE_TTL);
    }

    public ChatModelFactory(Collection<ChatModelBuilderSpi> initialBuilders) {
        this(initialBuilders, DEFAULT_MAX_CACHE_SIZE, DEFAULT_CACHE_IDLE_TTL);
    }

    public ChatModelFactory(
            Collection<ChatModelBuilderSpi> initialBuilders,
            int maxCacheSize,
            Duration cacheIdleTtl) {
        if (initialBuilders != null) {
            this.builders.addAll(initialBuilders);
        }
        this.modelCache =
                Caffeine.newBuilder()
                        .maximumSize(Math.max(1, maxCacheSize))
                        .expireAfterAccess(
                                Objects.requireNonNullElse(cacheIdleTtl, DEFAULT_CACHE_IDLE_TTL))
                        .executor(Runnable::run)
                        .removalListener((String key, ChatModel model, RemovalCause cause) -> {
                            if (model != null) {
                                log.debug(
                                        "[IntelliHub-LLM] Releasing cached ChatModel (key={}, cause={})",
                                        key,
                                        cause);
                                SharedHttpClientSupport.closeQuietly(model);
                            }
                        })
                        .build();
    }

    /**
     * 获取全局默认工厂单例（优先从 {@link SpringContextHolder} 获取容器管理的 Bean，未初始化时回退到静态默认实例）。
     */
    public static ChatModelFactory defaultInstance() {
        return SpringContextHolder.getBeanIfAvailable(ChatModelFactory.class)
                .orElse(DEFAULT_INSTANCE);
    }

    /**
     * 注册自定义 {@link ChatModelBuilderSpi}（后注册的同渠道构建器优先匹配，并清空已有缓存以使新构建器生效）。
     */
    public ChatModelFactory register(ChatModelBuilderSpi builderSpi) {
        Objects.requireNonNull(builderSpi, "builderSpi must not be null");
        this.builders.add(0, builderSpi);
        invalidateAll();
        return this;
    }

    /**
     * 查找支持指定 {@code provider} 标识的 {@link ChatModelBuilderSpi}。
     */
    public Optional<ChatModelBuilderSpi> findBuilder(String provider) {
        if (provider == null || provider.isBlank()) {
            return Optional.empty();
        }
        return builders.stream().filter(spi -> spi.supports(provider)).findFirst();
    }

    /**
     * 根据 {@link ModelConfig} 查找匹配的 {@link ChatModelBuilderSpi}（支持 {@code openaiCompatible=true}
     * 私有网关自动兜底路由）。
     */
    public Optional<ChatModelBuilderSpi> findBuilder(ModelConfig config) {
        if (config == null) {
            return Optional.empty();
        }
        Optional<ChatModelBuilderSpi> direct = findBuilder(config.provider());
        if (direct.isPresent()) {
            return direct;
        }
        if (config.getExtraBoolean(ModelConfig.EXTRA_OPENAI_COMPATIBLE).orElse(false)) {
            return findBuilder(ModelProviderType.OPENAI_COMPATIBLE.getCode());
        }
        return Optional.empty();
    }

    /**
     * 返回当前已注册的所有 {@link ChatModelBuilderSpi} 列表（只读）。
     */
    public List<ChatModelBuilderSpi> getBuilders() {
        return Collections.unmodifiableList(builders);
    }

    /**
     * 查询指定 {@code provider} 支持的能力元数据集合（Capability Metadata）。
     */
    public Set<ModelCapability> getCapabilities(String provider) {
        return findBuilder(provider)
                .map(spi -> spi.capabilities(provider))
                .orElseGet(() ->
                        ModelProviderType.fromCode(provider)
                                .map(ModelProviderType::getCapabilities)
                                .orElse(Set.of()));
    }

    /**
     * 判断指定 {@code provider} 是否支持某项特定能力。
     */
    public boolean supportsCapability(String provider, ModelCapability capability) {
        return capability != null && getCapabilities(provider).contains(capability);
    }

    /**
     * 校验 {@link ModelConfig} 中设置的参数是否被目标渠道支持，返回不兼容项的告警列表（为空表示完全兼容）。
     */
    public List<String> validateCapabilities(ModelConfig config) {
        if (config == null) {
            return List.of();
        }
        Optional<ChatModelBuilderSpi> spiOpt = findBuilder(config);
        if (spiOpt.isEmpty()) {
            return List.of();
        }
        Set<ModelCapability> caps = spiOpt.get().capabilities(config.provider());
        List<String> warnings = new ArrayList<>();

        if (config.getExtraBoolean(ModelConfig.EXTRA_THINKING_ENABLED).isPresent()
                && !caps.contains(ModelCapability.THINKING_SWITCH)) {
            warnings.add(
                    String.format(
                            "渠道 [%s] 不支持深度思考开关 (%s)，该参数将被忽略",
                            config.provider(), ModelConfig.EXTRA_THINKING_ENABLED));
        }
        if (config.getExtra(ModelConfig.EXTRA_THINKING_BUDGET, Object.class).isPresent()
                && !caps.contains(ModelCapability.THINKING_BUDGET)) {
            warnings.add(
                    String.format(
                            "渠道 [%s] 不支持思考预算参数 (%s)，该参数将被忽略",
                            config.provider(), ModelConfig.EXTRA_THINKING_BUDGET));
        }
        if (config.getExtraString(ModelConfig.EXTRA_REASONING_EFFORT).isPresent()
                && !caps.contains(ModelCapability.REASONING_EFFORT)) {
            warnings.add(
                    String.format(
                            "渠道 [%s] 不支持推理强度参数 (%s)，该参数将被自动清洗或忽略",
                            config.provider(), ModelConfig.EXTRA_REASONING_EFFORT));
        }
        if (config.topK() != null && !caps.contains(ModelCapability.TOP_K_SAMPLING)) {
            warnings.add(String.format("渠道 [%s] 不支持 topK 采样参数，该参数将被忽略", config.provider()));
        }
        if ((config.frequencyPenalty() != null || config.presencePenalty() != null)
                && !caps.contains(ModelCapability.PENALTY_PARAMETERS)) {
            warnings.add(
                    String.format(
                            "渠道 [%s] 不支持 frequencyPenalty/presencePenalty 惩罚参数，该参数将被自动清洗或忽略",
                            config.provider()));
        }
        if (config.getExtraString(ModelConfig.EXTRA_JINFER_MODEL_PATH).isPresent()
                && !caps.contains(ModelCapability.LOCAL_WEIGHTS)) {
            warnings.add(
                    String.format(
                            "渠道 [%s] 不支持进程内本地权重加载 (%s)",
                            config.provider(), ModelConfig.EXTRA_JINFER_MODEL_PATH));
        }
        return Collections.unmodifiableList(warnings);
    }

    /**
     * 仅根据 {@link ModelConfig} 构建轻量级运行时 {@link ChatOptions}（适用于按请求动态覆盖采样参数，不触发连接池或模型重建）。
     */
    public ChatOptions createChatOptions(ModelConfig config) {
        ChatModelBuilderSpi spi = resolveRequiredSpi(config);
        try {
            return spi.buildOptions(config);
        } catch (Throwable ex) {
            throw IntelliHubExceptionUtil.wrap(config.provider(), ex);
        }
    }

    /**
     * 根据 {@link ModelConfig} 获取或构建对应的 Spring AI 原生 {@link ChatModel}（优先命中本地 Caffeine 缓存，避免连接池与本地权重泄漏）。
     *
     * <p>对于持有本地权重的推理引擎（如 {@code jinfer}），按 {@link ModelConfig#connectionFingerprint()}
     * 缓存，确保同一模型权重文件在进程内仅加载一次。
     */
    public ChatModel createChatModel(ModelConfig config) {
        ChatModelBuilderSpi spi = resolveRequiredSpi(config);
        List<String> warnings = validateCapabilities(config);
        for (String warning : warnings) {
            log.warn("[IntelliHub-LLM-Capability] {}", warning);
        }
        String cacheKey = resolveCacheKey(spi, config);
        try {
            return modelCache.get(cacheKey, k -> spi.build(config));
        } catch (Throwable ex) {
            throw IntelliHubExceptionUtil.wrap(config.provider(), ex);
        }
    }

    /**
     * 根据 {@link ModelConfig} 直接构建全新的非缓存 {@link ChatModel}（调用方需自行负责资源生命周期管理）。
     */
    public ChatModel buildUncachedChatModel(ModelConfig config) {
        ChatModelBuilderSpi spi = resolveRequiredSpi(config);
        try {
            return spi.build(config);
        } catch (Throwable ex) {
            throw IntelliHubExceptionUtil.wrap(config.provider(), ex);
        }
    }

    /**
     * 根据 {@link ModelConfig} 构建对应的 Spring AI {@link ChatClient}（复用缓存的 {@link ChatModel} +
     * 绑定请求级 {@link ChatOptions} + 自动挂载日志耗时与本地缓存 Advisor）。
     */
    public ChatClient createChatClient(ModelConfig config, Advisor... extraAdvisors) {
        ChatModel chatModel = createChatModel(config);
        ChatClient.Builder clientBuilder = ChatClient.builder(chatModel);

        List<Advisor> advisors = new ArrayList<>();
        SpringContextHolder.getBeanIfAvailable(AiLoggingAndMetricsAdvisor.class)
                .ifPresent(advisors::add);
        SpringContextHolder.getBeanIfAvailable(AiLocalCacheAdvisor.class).ifPresent(advisors::add);
        if (extraAdvisors != null) {
            for (Advisor advisor : extraAdvisors) {
                if (advisor != null) {
                    advisors.add(advisor);
                }
            }
        }
        if (!advisors.isEmpty()) {
            clientBuilder.defaultAdvisors(advisors);
        }
        return clientBuilder.build();
    }

    /**
     * 从缓存中主动驱逐并关闭指定 {@link ModelConfig} 对应的 {@link ChatModel} 实例。
     */
    public void invalidate(ModelConfig config) {
        if (config == null) {
            return;
        }
        findBuilder(config)
                .ifPresent(spi -> {
                    modelCache.invalidate(resolveCacheKey(spi, config));
                    modelCache.cleanUp();
                });
    }

    /**
     * 清空全部已缓存的 {@link ChatModel} 实例并触发底层资源关闭。
     */
    public void invalidateAll() {
        modelCache.invalidateAll();
        modelCache.cleanUp();
    }

    /**
     * 返回当前缓存中的 {@link ChatModel} 实例数量。
     */
    public long cacheSize() {
        modelCache.cleanUp();
        return modelCache.estimatedSize();
    }

    @Override
    public void close() {
        invalidateAll();
    }

    @Override
    public void destroy() {
        close();
    }

    private ChatModelBuilderSpi resolveRequiredSpi(ModelConfig config) {
        if (config == null) {
            throw IntelliHubExceptionUtil.invalidParamException("ModelConfig 不能为空");
        }
        return findBuilder(config)
                .orElseThrow(() ->
                        IntelliHubExceptionUtil.exception(
                                GlobalErrorCodeConstants.AI_PROVIDER_NOT_FOUND, config.provider()));
    }

    private static String resolveCacheKey(ChatModelBuilderSpi spi, ModelConfig config) {
        if (spi.supportsCapability(config.provider(), ModelCapability.LOCAL_WEIGHTS)) {
            return "local:" + config.connectionFingerprint();
        }
        return "model:" + config.cacheKey();
    }

    private static List<ChatModelBuilderSpi> loadFromServiceLoader() {
        List<ChatModelBuilderSpi> loaded = new ArrayList<>();
        ServiceLoader.load(ChatModelBuilderSpi.class).forEach(loaded::add);
        return loaded;
    }
}
