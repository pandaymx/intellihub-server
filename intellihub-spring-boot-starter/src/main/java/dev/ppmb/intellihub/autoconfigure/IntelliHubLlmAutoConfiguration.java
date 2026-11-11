package dev.ppmb.intellihub.autoconfigure;

import dev.ppmb.intellihub.autoconfigure.IntelliHubLlmProperties.ChannelProperties;
import dev.ppmb.intellihub.autoconfigure.IntelliHubLlmProperties.EmbeddingProperties;
import dev.ppmb.intellihub.autoconfigure.IntelliHubLlmProperties.RouteGroupProperties;
import dev.ppmb.intellihub.autoconfigure.IntelliHubLlmProperties.RouteTargetProperties;
import dev.ppmb.intellihub.core.advisor.AiLocalCacheAdvisor;
import dev.ppmb.intellihub.core.advisor.AiLoggingAndMetricsAdvisor;
import dev.ppmb.intellihub.llm.config.ModelConfig;
import dev.ppmb.intellihub.llm.config.ModelProviderType;
import dev.ppmb.intellihub.llm.embedding.GoogleGenAiEmbeddingModel;
import dev.ppmb.intellihub.llm.factory.ChatModelFactory;
import dev.ppmb.intellihub.llm.registry.IntelliHubModelRegistry;
import dev.ppmb.intellihub.llm.router.ApiKeyPool;
import dev.ppmb.intellihub.llm.router.ModelRouteTarget;
import dev.ppmb.intellihub.llm.router.RoutingChatModel;
import dev.ppmb.intellihub.llm.spi.ChatModelBuilderSpi;
import dev.ppmb.intellihub.llm.spi.dialect.OpenAiDialectCustomizer;
import dev.ppmb.intellihub.llm.spi.provider.OpenAiChatModelBuilderSpi;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@AutoConfiguration
@ConditionalOnClass(ChatClient.class)
@EnableConfigurationProperties(IntelliHubLlmProperties.class)
public class IntelliHubLlmAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AiLoggingAndMetricsAdvisor aiLoggingAndMetricsAdvisor() {
        return new AiLoggingAndMetricsAdvisor();
    }

    @Bean
    @ConditionalOnMissingBean
    public AiLocalCacheAdvisor aiLocalCacheAdvisor() {
        return new AiLocalCacheAdvisor();
    }

    @Bean
    @ConditionalOnMissingBean
    public ChatModelFactory chatModelFactory(
            IntelliHubLlmProperties properties,
            ObjectProvider<ChatModelBuilderSpi> customBuilderSpis,
            ObjectProvider<OpenAiDialectCustomizer> customDialectCustomizers) {
        List<ChatModelBuilderSpi> loadedSpis = new ArrayList<>();
        ServiceLoader.load(ChatModelBuilderSpi.class).forEach(loadedSpis::add);
        ChatModelFactory factory =
                new ChatModelFactory(
                        loadedSpis,
                        properties.getCache().getMaxSize(),
                        properties.getCache().getIdleTtl());
        factory.findBuilder(ModelProviderType.OPENAI.getCode())
                .filter(OpenAiChatModelBuilderSpi.class::isInstance)
                .map(OpenAiChatModelBuilderSpi.class::cast)
                .ifPresent(openAiSpi ->
                        customDialectCustomizers
                                .orderedStream()
                                .forEach(openAiSpi::registerDialectCustomizer));
        customBuilderSpis.orderedStream().forEach(factory::register);
        return factory;
    }

    @Bean
    @ConditionalOnMissingBean
    public IntelliHubModelRegistry intelliHubModelRegistry(
            ChatModelFactory chatModelFactory, IntelliHubLlmProperties properties) {
        IntelliHubModelRegistry registry = new IntelliHubModelRegistry(chatModelFactory);

        for (Map.Entry<String, ChannelProperties> entry : properties.getChannels().entrySet()) {
            String channelName = entry.getKey();
            ChannelProperties chProps = entry.getValue();
            if (chProps == null
                    || chProps.getProvider() == null
                    || chProps.getProvider().isBlank()) {
                continue;
            }
            ModelConfig config = chProps.toModelConfig();
            if (chProps.getApiKeys() != null && !chProps.getApiKeys().isEmpty()) {
                registry.registerKeyPoolChannel(
                        channelName, config, chProps.getApiKeys(), chProps.getKeyCooldown());
            } else {
                registry.registerChannel(channelName, config);
            }
        }

        for (Map.Entry<String, RouteGroupProperties> entry : properties.getRoutes().entrySet()) {
            String routeName = entry.getKey();
            RouteGroupProperties routeProps = entry.getValue();
            if (routeProps == null) {
                continue;
            }
            List<ModelRouteTarget> targets = new ArrayList<>();
            if (routeProps.getTargets() != null && !routeProps.getTargets().isEmpty()) {
                for (RouteTargetProperties t : routeProps.getTargets()) {
                    if (t.getChannel() != null && !t.getChannel().isBlank()) {
                        targets.add(
                                registry.createRouteTargetFromChannel(
                                        t.getChannel(), t.getWeight()));
                    }
                }
            } else if (routeProps.getChannels() != null && !routeProps.getChannels().isEmpty()) {
                for (String chName : routeProps.getChannels()) {
                    if (chName != null && !chName.isBlank()) {
                        targets.add(registry.createRouteTargetFromChannel(chName, 1));
                    }
                }
            }
            if (!targets.isEmpty()) {
                RoutingChatModel routingModel =
                        RoutingChatModel.builder(routeName)
                                .strategy(routeProps.getStrategy())
                                .failureThreshold(routeProps.getFailureThreshold())
                                .cooldownDuration(routeProps.getCooldown())
                                .failoverOnAuthError(routeProps.isFailoverOnAuthError())
                                .targets(targets)
                                .build();
                registry.registerRoute(routingModel);
            }
        }

        if (properties.getDefaultChannel() != null && !properties.getDefaultChannel().isBlank()) {
            registry.setDefaultChannelOrRoute(properties.getDefaultChannel());
        }
        return registry;
    }

    /**
     * 将 {@link IntelliHubModelRegistry} 的默认高可用路由组/渠道暴露为全局 {@code @Primary} {@link ChatModel}，
     * 使业务方通过 {@code @Autowired ChatModel} 即可零感知接入主备降级与 KeyPool 能力。
     */
    @Bean
    @Primary
    public ChatModel intelliHubPrimaryChatModel(
            IntelliHubModelRegistry registry, ChatModelFactory chatModelFactory) {
        if (!registry.getRouteNames().isEmpty() || !registry.getChannelNames().isEmpty()) {
            return registry.getDefaultChatModel();
        }
        return chatModelFactory.createChatModel(
                ModelConfig.of("openai", "sk-placeholder", "gpt-4o-mini"));
    }

    /**
     * 基于 {@code @Primary} {@link ChatModel} 构建默认挂载了日志指标与本地缓存拦截器的 {@code @Primary} {@link
     * ChatClient}。
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean
    public ChatClient chatClient(
            ChatModel primaryChatModel,
            AiLoggingAndMetricsAdvisor loggingAdvisor,
            AiLocalCacheAdvisor localCacheAdvisor) {
        return ChatClient.builder(primaryChatModel)
                .defaultAdvisors(loggingAdvisor, localCacheAdvisor)
                .build();
    }

    /**
     * 当开启 {@code intellihub.llm.embedding.enabled=true} 时，自动装配支持 KeyPool 故障转移与代理穿透的
     * {@link GoogleGenAiEmbeddingModel}。
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(EmbeddingModel.class)
    @ConditionalOnProperty(
            prefix = "intellihub.llm.embedding",
            name = "enabled",
            havingValue = "true")
    public EmbeddingModel intelliHubEmbeddingModel(IntelliHubLlmProperties properties) {
        EmbeddingProperties embedProps = properties.getEmbedding();
        ChannelProperties refChannel =
                (embedProps.getChannel() != null && !embedProps.getChannel().isBlank())
                        ? properties.getChannels().get(embedProps.getChannel().strip())
                        : null;

        List<String> resolvedKeys = new ArrayList<>();
        if (embedProps.getApiKeys() != null && !embedProps.getApiKeys().isEmpty()) {
            resolvedKeys.addAll(embedProps.getApiKeys());
        } else if (embedProps.getApiKey() != null && !embedProps.getApiKey().isBlank()) {
            resolvedKeys.add(embedProps.getApiKey().strip());
        } else if (refChannel != null) {
            if (refChannel.getApiKeys() != null && !refChannel.getApiKeys().isEmpty()) {
                resolvedKeys.addAll(refChannel.getApiKeys());
            } else if (refChannel.getApiKey() != null && !refChannel.getApiKey().isBlank()) {
                resolvedKeys.add(refChannel.getApiKey().strip());
            }
        }

        ModelConfig.Builder cfgBuilder =
                (refChannel != null
                                ? refChannel.toModelConfig().toBuilder()
                                : ModelConfig.builder())
                        .provider(
                                embedProps.getProvider() != null
                                        ? embedProps.getProvider()
                                        : ModelProviderType.GOOGLE_GENAI.getCode())
                        .model(embedProps.getModel());
        if (!resolvedKeys.isEmpty()) {
            cfgBuilder.apiKey(resolvedKeys.get(0));
        }

        ApiKeyPool keyPool =
                new ApiKeyPool(
                        ModelProviderType.GOOGLE_GENAI.getCode(),
                        resolvedKeys,
                        refChannel != null
                                ? refChannel.getKeyCooldown()
                                : ApiKeyPool.DEFAULT_COOLDOWN);
        return new GoogleGenAiEmbeddingModel(
                cfgBuilder.build(), keyPool, embedProps.getDimensions());
    }
}
