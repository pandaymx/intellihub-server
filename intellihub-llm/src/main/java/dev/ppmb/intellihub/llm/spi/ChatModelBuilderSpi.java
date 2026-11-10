package dev.ppmb.intellihub.llm.spi;

import dev.ppmb.intellihub.llm.config.ModelConfig;
import org.springframework.ai.chat.model.ChatModel;

/**
 * 插件化 {@link ChatModel} 构建器 SPI 接口。
 *
 * <p>每个实现类对应一种或一族 Spring AI 原生 {@link ChatModel} 构建逻辑，支持通过 Java {@link java.util.ServiceLoader}
 * 或 Spring 容器自动发现注册。
 */
public interface ChatModelBuilderSpi {

    /**
     * 当前构建器对应的标准主渠道标识（如 {@code "openai"}、{@code "deepseek"}、{@code "anthropic"} 等）。
     */
    String provider();

    /**
     * 判断当前 SPI 是否支持构建指定 {@code provider} 渠道（支持别名匹配，忽略大小写）。
     */
    default boolean supports(String provider) {
        return provider != null && provider().equalsIgnoreCase(provider.strip());
    }

    /**
     * 基于 {@link ModelConfig}（含通用参数与 {@code extras} 扩展参数）构建 Spring AI 原生 {@link ChatModel} 实例。
     */
    ChatModel build(ModelConfig config);
}
