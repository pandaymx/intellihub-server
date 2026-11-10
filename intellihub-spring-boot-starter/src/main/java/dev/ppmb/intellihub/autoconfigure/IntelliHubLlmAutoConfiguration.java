package dev.ppmb.intellihub.autoconfigure;

import dev.ppmb.intellihub.autoconfigure.IntelliHubLlmProperties.ChannelProperties;
import dev.ppmb.intellihub.autoconfigure.IntelliHubLlmProperties.RouteGroupProperties;
import dev.ppmb.intellihub.autoconfigure.IntelliHubLlmProperties.RouteTargetProperties;
import dev.ppmb.intellihub.core.advisor.AiLocalCacheAdvisor;
import dev.ppmb.intellihub.core.advisor.AiLoggingAndMetricsAdvisor;
import dev.ppmb.intellihub.llm.config.ModelConfig;
import dev.ppmb.intellihub.llm.config.ModelProviderType;
import dev.ppmb.intellihub.llm.factory.ChatModelFactory;
import dev.ppmb.intellihub.llm.registry.IntelliHubModelRegistry;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

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

    @Bean
    @ConditionalOnMissingBean
    public ChatClient chatClient(
            ChatClient.Builder builder,
            AiLoggingAndMetricsAdvisor loggingAdvisor,
            AiLocalCacheAdvisor localCacheAdvisor) {
        return builder.defaultAdvisors(loggingAdvisor, localCacheAdvisor).build();
    }
}
