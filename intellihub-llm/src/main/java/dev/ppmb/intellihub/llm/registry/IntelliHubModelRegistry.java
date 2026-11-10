package dev.ppmb.intellihub.llm.registry;

import dev.ppmb.intellihub.core.advisor.AiLocalCacheAdvisor;
import dev.ppmb.intellihub.core.advisor.AiLoggingAndMetricsAdvisor;
import dev.ppmb.intellihub.core.context.SpringContextHolder;
import dev.ppmb.intellihub.core.exception.GlobalErrorCodeConstants;
import dev.ppmb.intellihub.core.exception.IntelliHubExceptionUtil;
import dev.ppmb.intellihub.llm.config.ModelConfig;
import dev.ppmb.intellihub.llm.factory.ChatModelFactory;
import dev.ppmb.intellihub.llm.router.ApiKeyPool;
import dev.ppmb.intellihub.llm.router.KeyPoolChatModel;
import dev.ppmb.intellihub.llm.router.ModelRouteTarget;
import dev.ppmb.intellihub.llm.router.RoutingChatModel;
import dev.ppmb.intellihub.llm.router.RoutingStrategy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatModel;

/**
 * 命名 AI 模型渠道（Channel）与高可用路由组（Route Group）统一注册中心。
 *
 * <ul>
 *   <li>支持注册单 Key 命名渠道（{@link #registerChannel(String, ModelConfig)}）与多 Key 轮询冷却池渠道（{@link
 *       #registerKeyPoolChannel(String, ModelConfig, Collection, Duration)}）；
 *   <li>支持注册基于主备降级（{@code FAILOVER}）、轮询（{@code ROUND_ROBIN}）、加权（{@code WEIGHTED}）的高可用路由组（{@link
 *       #registerRoute(RoutingChatModel)}）；
 *   <li>提供统一的 {@link #getChatModel(String)} 与 {@link #getChatClient(String, Advisor...)}
 *       按名称检索接口，配合 {@code intellihub.llm.*} 配置文件实现开箱即用。
 * </ul>
 */
public class IntelliHubModelRegistry {

    private final ChatModelFactory chatModelFactory;
    private final Map<String, ChannelEntry> channels = new ConcurrentHashMap<>();
    private final Map<String, RoutingChatModel> routes = new ConcurrentHashMap<>();
    private volatile String defaultChannelOrRoute;

    public IntelliHubModelRegistry() {
        this(ChatModelFactory.defaultInstance());
    }

    public IntelliHubModelRegistry(ChatModelFactory chatModelFactory) {
        this.chatModelFactory =
                Objects.requireNonNull(chatModelFactory, "chatModelFactory must not be null");
    }

    public ChatModelFactory getChatModelFactory() {
        return chatModelFactory;
    }

    public String getDefaultChannelOrRoute() {
        return defaultChannelOrRoute;
    }

    public IntelliHubModelRegistry setDefaultChannelOrRoute(String defaultChannelOrRoute) {
        this.defaultChannelOrRoute =
                defaultChannelOrRoute != null && !defaultChannelOrRoute.isBlank()
                        ? defaultChannelOrRoute.strip()
                        : null;
        return this;
    }

    /**
     * 注册单 Key 命名模型渠道。
     */
    public IntelliHubModelRegistry registerChannel(String channelName, ModelConfig config) {
        String key = normalizeName(channelName, "channelName");
        Objects.requireNonNull(config, "config must not be null");
        channels.put(
                key,
                new ChannelEntry(
                        key, config.provider(), () -> chatModelFactory.createChatModel(config)));
        if (defaultChannelOrRoute == null) {
            defaultChannelOrRoute = key;
        }
        return this;
    }

    /**
     * 注册多 API Key 轮询与故障冷却池命名渠道。
     */
    public IntelliHubModelRegistry registerKeyPoolChannel(
            String channelName,
            ModelConfig baseConfig,
            Collection<String> apiKeys,
            Duration keyCooldown) {
        String key = normalizeName(channelName, "channelName");
        Objects.requireNonNull(baseConfig, "baseConfig must not be null");
        ApiKeyPool keyPool =
                new ApiKeyPool(
                        baseConfig.provider(),
                        apiKeys,
                        keyCooldown != null ? keyCooldown : ApiKeyPool.DEFAULT_COOLDOWN);
        KeyPoolChatModel keyPoolModel = new KeyPoolChatModel(baseConfig, keyPool, chatModelFactory);
        channels.put(key, new ChannelEntry(key, baseConfig.provider(), () -> keyPoolModel));
        if (defaultChannelOrRoute == null) {
            defaultChannelOrRoute = key;
        }
        return this;
    }

    /**
     * 注册高可用路由组。
     */
    public IntelliHubModelRegistry registerRoute(RoutingChatModel routingChatModel) {
        Objects.requireNonNull(routingChatModel, "routingChatModel must not be null");
        String key = normalizeName(routingChatModel.getRouteName(), "routeName");
        routes.put(key, routingChatModel);
        if (defaultChannelOrRoute == null) {
            defaultChannelOrRoute = key;
        }
        return this;
    }

    /**
     * 基于已注册的多个命名渠道快速构建并注册主备故障转移路由组（{@link RoutingStrategy#FAILOVER}）。
     */
    public IntelliHubModelRegistry registerFailoverRoute(
            String routeName, List<String> channelNames) {
        return registerRoute(routeName, RoutingStrategy.FAILOVER, channelNames, null, null);
    }

    /**
     * 基于已注册的命名渠道列表构建并注册指定策略的高可用路由组。
     */
    public IntelliHubModelRegistry registerRoute(
            String routeName,
            RoutingStrategy strategy,
            List<String> channelNames,
            Integer failureThreshold,
            Duration cooldownDuration) {
        String key = normalizeName(routeName, "routeName");
        if (channelNames == null || channelNames.isEmpty()) {
            throw IntelliHubExceptionUtil.invalidParamException("注册路由组 [{}] 失败：候选渠道列表不能为空", key);
        }
        List<ModelRouteTarget> targets = new ArrayList<>(channelNames.size());
        for (String chName : channelNames) {
            targets.add(createRouteTargetFromChannel(chName, 1));
        }
        RoutingChatModel.Builder builder =
                RoutingChatModel.builder(key)
                        .strategy(strategy != null ? strategy : RoutingStrategy.FAILOVER)
                        .targets(targets);
        if (failureThreshold != null) {
            builder.failureThreshold(failureThreshold);
        }
        if (cooldownDuration != null) {
            builder.cooldownDuration(cooldownDuration);
        }
        return registerRoute(builder.build());
    }

    /**
     * 将已注册的命名渠道包装为路由候选节点 {@link ModelRouteTarget}。
     */
    public ModelRouteTarget createRouteTargetFromChannel(String channelName, int weight) {
        String key = normalizeName(channelName, "channelName");
        ChannelEntry entry = channels.get(key);
        if (entry == null) {
            throw IntelliHubExceptionUtil.exception(
                    GlobalErrorCodeConstants.NOT_FOUND, "未找到已注册的模型渠道 [" + key + "]");
        }
        return new ModelRouteTarget(entry.name(), entry.provider(), weight, entry.modelSupplier());
    }

    /**
     * 按名称获取 {@link ChatModel}（优先匹配高可用路由组 {@code routes}，其次匹配单/多 Key 渠道 {@code channels}）。
     */
    public ChatModel getChatModel(String name) {
        String key = normalizeName(name, "name");
        RoutingChatModel route = routes.get(key);
        if (route != null) {
            return route;
        }
        ChannelEntry channel = channels.get(key);
        if (channel != null) {
            return channel.modelSupplier().get();
        }
        throw IntelliHubExceptionUtil.exception(
                GlobalErrorCodeConstants.NOT_FOUND, "未找到名为 [" + key + "] 的 AI 模型渠道或路由组");
    }

    /**
     * 按名称构建挂载了默认拦截器的 {@link ChatClient}。
     */
    public ChatClient getChatClient(String name, Advisor... extraAdvisors) {
        ChatModel chatModel = getChatModel(name);
        return buildChatClientForModel(chatModel, extraAdvisors);
    }

    /**
     * 获取默认渠道或路由组的 {@link ChatModel}。
     */
    public ChatModel getDefaultChatModel() {
        String target = resolveDefaultName();
        return getChatModel(target);
    }

    /**
     * 获取默认渠道或路由组的 {@link ChatClient}。
     */
    public ChatClient getDefaultChatClient(Advisor... extraAdvisors) {
        ChatModel chatModel = getDefaultChatModel();
        return buildChatClientForModel(chatModel, extraAdvisors);
    }

    public Optional<RoutingChatModel> findRoute(String routeName) {
        if (routeName == null || routeName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(routes.get(routeName.strip()));
    }

    public Set<String> getChannelNames() {
        return Collections.unmodifiableSet(channels.keySet());
    }

    public Set<String> getRouteNames() {
        return Collections.unmodifiableSet(routes.keySet());
    }

    private String resolveDefaultName() {
        if (defaultChannelOrRoute != null && !defaultChannelOrRoute.isBlank()) {
            return defaultChannelOrRoute;
        }
        if (!routes.isEmpty()) {
            return routes.keySet().iterator().next();
        }
        if (!channels.isEmpty()) {
            return channels.keySet().iterator().next();
        }
        throw IntelliHubExceptionUtil.exception(
                GlobalErrorCodeConstants.NOT_FOUND, "尚未注册任何默认 AI 模型渠道或路由组");
    }

    private static ChatClient buildChatClientForModel(
            ChatModel chatModel, Advisor... extraAdvisors) {
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

    private static String normalizeName(String name, String paramLabel) {
        if (name == null || name.isBlank()) {
            throw IntelliHubExceptionUtil.invalidParamException("{} 不能为空", paramLabel);
        }
        return name.strip();
    }

    private record ChannelEntry(String name, String provider, Supplier<ChatModel> modelSupplier) {}
}
