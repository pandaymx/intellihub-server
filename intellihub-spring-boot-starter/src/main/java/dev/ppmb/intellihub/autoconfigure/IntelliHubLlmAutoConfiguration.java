package dev.ppmb.intellihub.autoconfigure;

import dev.ppmb.intellihub.core.advisor.AiLocalCacheAdvisor;
import dev.ppmb.intellihub.core.advisor.AiLoggingAndMetricsAdvisor;
import org.springframework.ai.chat.client.ChatClient;
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
    public ChatClient chatClient(
            ChatClient.Builder builder,
            AiLoggingAndMetricsAdvisor loggingAdvisor,
            AiLocalCacheAdvisor localCacheAdvisor) {
        return builder.defaultAdvisors(loggingAdvisor, localCacheAdvisor).build();
    }
}
