package dev.ppmb.intellihub.llm.factory;

import dev.ppmb.intellihub.core.advisor.AiLocalCacheAdvisor;
import dev.ppmb.intellihub.core.advisor.AiLoggingAndMetricsAdvisor;
import dev.ppmb.intellihub.core.context.SpringContextHolder;
import dev.ppmb.intellihub.core.exception.GlobalErrorCodeConstants;
import dev.ppmb.intellihub.core.exception.IntelliHubExceptionUtil;
import dev.ppmb.intellihub.llm.config.ModelConfig;
import dev.ppmb.intellihub.llm.spi.ChatModelBuilderSpi;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatModel;

/**
 * 基于 {@link ChatModelBuilderSpi} 的插件化 {@link ChatModel} / {@link ChatClient} 工厂。
 *
 * <ul>
 *   <li>自动通过 Java {@link ServiceLoader} 加载所有内置与第三方扩展的 {@link ChatModelBuilderSpi}；
 *   <li>支持运行时通过 {@link #register(ChatModelBuilderSpi)} 动态注册或覆盖指定厂商构建器；
 *   <li>支持根据 {@link ModelConfig} 一键构建原生 {@link ChatModel} 或挂载了默认拦截器的 {@link ChatClient}。
 * </ul>
 */
public class ChatModelFactory {

    private static final ChatModelFactory DEFAULT_INSTANCE = new ChatModelFactory();

    private final List<ChatModelBuilderSpi> builders = new CopyOnWriteArrayList<>();

    public ChatModelFactory() {
        this(loadFromServiceLoader());
    }

    public ChatModelFactory(Collection<ChatModelBuilderSpi> initialBuilders) {
        if (initialBuilders != null) {
            this.builders.addAll(initialBuilders);
        }
    }

    /**
     * 获取全局默认工厂单例（优先从 {@link SpringContextHolder} 获取容器管理的 Bean，未初始化时回退到静态默认实例）。
     */
    public static ChatModelFactory defaultInstance() {
        return SpringContextHolder.getBeanIfAvailable(ChatModelFactory.class)
                .orElse(DEFAULT_INSTANCE);
    }

    /**
     * 注册自定义 {@link ChatModelBuilderSpi}（后注册的同渠道构建器优先匹配）。
     */
    public ChatModelFactory register(ChatModelBuilderSpi builderSpi) {
        Objects.requireNonNull(builderSpi, "builderSpi must not be null");
        this.builders.add(0, builderSpi);
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
     * 返回当前已注册的所有 {@link ChatModelBuilderSpi} 列表（只读）。
     */
    public List<ChatModelBuilderSpi> getBuilders() {
        return Collections.unmodifiableList(builders);
    }

    /**
     * 根据 {@link ModelConfig} 构建对应的 Spring AI 原生 {@link ChatModel}。
     */
    public ChatModel createChatModel(ModelConfig config) {
        if (config == null) {
            throw IntelliHubExceptionUtil.invalidParamException("ModelConfig 不能为空");
        }
        ChatModelBuilderSpi spi =
                findBuilder(config.provider())
                        .orElseThrow(() ->
                                IntelliHubExceptionUtil.exception(
                                        GlobalErrorCodeConstants.AI_PROVIDER_NOT_FOUND,
                                        config.provider()));
        try {
            return spi.build(config);
        } catch (Throwable ex) {
            throw IntelliHubExceptionUtil.wrap(config.provider(), ex);
        }
    }

    /**
     * 根据 {@link ModelConfig} 构建对应的 Spring AI {@link ChatClient}（自动挂载日志耗时与本地缓存 Advisor）。
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

    private static List<ChatModelBuilderSpi> loadFromServiceLoader() {
        List<ChatModelBuilderSpi> loaded = new ArrayList<>();
        ServiceLoader.load(ChatModelBuilderSpi.class).forEach(loaded::add);
        return loaded;
    }
}
