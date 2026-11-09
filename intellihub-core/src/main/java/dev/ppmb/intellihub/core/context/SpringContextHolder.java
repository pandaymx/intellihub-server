package dev.ppmb.intellihub.core.context;

import dev.ppmb.intellihub.core.exception.GlobalErrorCodeConstants;
import dev.ppmb.intellihub.core.exception.IntelliHubExceptionUtil;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.env.Environment;

/**
 * Spring 上下文静态持有器，让静态工具类（如 {@code AiUtil}、{@code PromptUtil}、{@code RagUtil}）
 * 能无缝获取 Spring 容器中的 {@code ChatClient}、{@code ChatModel}、{@code EmbeddingModel} 等 AI Bean。
 */
public class SpringContextHolder implements ApplicationContextAware, DisposableBean {

    private static volatile ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext context) throws BeansException {
        SpringContextHolder.applicationContext = context;
    }

    /**
     * 手动注入或覆盖 {@link ApplicationContext}（支持非 Starter 环境或单元测试直接绑定）。
     */
    public static void bind(ApplicationContext context) {
        SpringContextHolder.applicationContext = context;
    }

    /**
     * 判断当前 Spring 容器上下文是否已就绪。
     */
    public static boolean isInitialized() {
        return applicationContext != null;
    }

    /**
     * 获取当前 {@link ApplicationContext}，未初始化时抛出统一异常。
     */
    public static ApplicationContext getApplicationContext() {
        ApplicationContext ctx = applicationContext;
        if (ctx == null) {
            throw IntelliHubExceptionUtil.exception(
                    GlobalErrorCodeConstants.INTERNAL_SERVER_ERROR,
                    "SpringContextHolder 尚未初始化，请确认已在 Spring 容器中注册或引入 intellihub-spring-boot-starter");
        }
        return ctx;
    }

    /**
     * 按类型获取 Spring 容器中的 Bean（如 {@code ChatClient.class}）。
     */
    public static <T> T getBean(Class<T> requiredType) {
        try {
            return getApplicationContext().getBean(requiredType);
        } catch (NoSuchBeanDefinitionException ex) {
            throw IntelliHubExceptionUtil.exception(
                    GlobalErrorCodeConstants.NOT_FOUND,
                    "Spring 容器中未找到类型为 " + requiredType.getSimpleName() + " 的 AI Bean");
        }
    }

    /**
     * 按名称与类型获取 Spring 容器中的 Bean。
     */
    public static <T> T getBean(String name, Class<T> requiredType) {
        try {
            return getApplicationContext().getBean(name, requiredType);
        } catch (NoSuchBeanDefinitionException ex) {
            throw IntelliHubExceptionUtil.exception(
                    GlobalErrorCodeConstants.NOT_FOUND,
                    "Spring 容器中未找到名称为 "
                            + name
                            + " ("
                            + requiredType.getSimpleName()
                            + ") 的 AI Bean");
        }
    }

    /**
     * 安全尝试获取指定类型的 Bean（容器未初始化或 Bean 不存在时返回 {@link Optional#empty()}，不抛异常）。
     */
    public static <T> Optional<T> getBeanIfAvailable(Class<T> requiredType) {
        ApplicationContext ctx = applicationContext;
        if (ctx == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(ctx.getBeanProvider(requiredType).getIfAvailable());
    }

    /**
     * 获取指定类型的所有 Bean 映射表（支持多渠道/多模型实例发现）。
     */
    public static <T> Map<String, T> getBeansOfType(Class<T> type) {
        ApplicationContext ctx = applicationContext;
        if (ctx == null) {
            return Collections.emptyMap();
        }
        return ctx.getBeansOfType(type);
    }

    /**
     * 读取 Spring {@link Environment} 配置属性。
     */
    public static String getProperty(String key, String defaultValue) {
        ApplicationContext ctx = applicationContext;
        if (ctx == null) {
            return defaultValue;
        }
        Environment env = ctx.getEnvironment();
        return env.getProperty(key, defaultValue);
    }

    /**
     * 清空静态持有的 {@link ApplicationContext}。
     */
    public static void clearHolder() {
        applicationContext = null;
    }

    @Override
    public void destroy() {
        clearHolder();
    }
}
