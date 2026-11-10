package dev.ppmb.intellihub.autoconfigure;

import dev.ppmb.intellihub.core.advisor.AiLocalCacheAdvisor;
import dev.ppmb.intellihub.core.advisor.AiLoggingAndMetricsAdvisor;
import dev.ppmb.intellihub.llm.factory.ChatModelFactory;
import dev.ppmb.intellihub.llm.spi.ChatModelBuilderSpi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(ChatClient.class)
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
            ObjectProvider<ChatModelBuilderSpi> customBuilderSpis) {
        ChatModelFactory factory = new ChatModelFactory();
        customBuilderSpis.orderedStream().forEach(factory::register);
        return factory;
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
