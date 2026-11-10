package dev.ppmb.intellihub.autoconfigure;

import dev.ppmb.intellihub.prompt.render.PromptInjectionGuard;
import dev.ppmb.intellihub.prompt.render.SafePromptRenderer;
import dev.ppmb.intellihub.prompt.structured.StructuredOutputExecutor;
import dev.ppmb.intellihub.prompt.template.PromptRegistry;
import dev.ppmb.intellihub.prompt.template.PromptTemplateSpec;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * {@code intellihub-prompt} 模块 Spring Boot 自动装配配置类。
 *
 * <p>当类路径下存在 {@link PromptRegistry} 时，自动注册：
 *
 * <ul>
 *   <li>{@link PromptRegistry}：提示词多版本注册中心（自动收集容器中声明的 {@link PromptTemplateSpec} Bean）；
 *   <li>{@link PromptInjectionGuard}：提示词注入防御护栏；
 *   <li>{@link SafePromptRenderer}：安全提示词渲染器（必填变量校验 + XML 定界包裹 + JSON 大括号兼容）；
 *   <li>{@link StructuredOutputExecutor}：容错型结构化输出执行器（剥离思维链 + 截断 JSON 修复 + 自纠错重试）。
 * </ul>
 */
@AutoConfiguration
@ConditionalOnClass(PromptRegistry.class)
public class IntelliHubPromptAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PromptRegistry intelliHubPromptRegistry(List<PromptTemplateSpec> initialSpecs) {
        PromptRegistry registry = new PromptRegistry();
        if (initialSpecs != null && !initialSpecs.isEmpty()) {
            initialSpecs.forEach(registry::register);
        }
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean
    public PromptInjectionGuard intelliHubPromptInjectionGuard() {
        return new PromptInjectionGuard();
    }

    @Bean
    @ConditionalOnMissingBean
    public SafePromptRenderer intelliHubSafePromptRenderer(PromptInjectionGuard injectionGuard) {
        return new SafePromptRenderer(injectionGuard);
    }

    @Bean
    @ConditionalOnMissingBean
    public StructuredOutputExecutor intelliHubStructuredOutputExecutor(
            SafePromptRenderer safePromptRenderer) {
        return new StructuredOutputExecutor(
                safePromptRenderer, StructuredOutputExecutor.DEFAULT_MAX_RETRIES);
    }
}
