package dev.ppmb.intellihub.autoconfigure;

import dev.ppmb.intellihub.core.context.SpringContextHolder;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * 核心上下文自动装配，最高优先级初始化 {@link SpringContextHolder}，
 * 确保后续所有静态工具类均可直接从容器中获取 AI Bean。
 */
@AutoConfiguration
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IntelliHubCoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SpringContextHolder intelliHubSpringContextHolder() {
        return new SpringContextHolder();
    }
}
