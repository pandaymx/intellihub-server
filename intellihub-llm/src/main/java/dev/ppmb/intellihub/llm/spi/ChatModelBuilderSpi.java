package dev.ppmb.intellihub.llm.spi;

import dev.ppmb.intellihub.llm.config.ModelCapability;
import dev.ppmb.intellihub.llm.config.ModelConfig;
import dev.ppmb.intellihub.llm.config.ModelProviderType;
import java.util.Set;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;

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
     * 返回当前 SPI 主渠道支持的能力元数据集合（Capability Metadata）。
     */
    default Set<ModelCapability> capabilities() {
        return ModelProviderType.fromCode(provider())
                .map(ModelProviderType::getCapabilities)
                .orElse(Set.of());
    }

    /**
     * 返回指定子渠道/别名支持的能力元数据集合（适用于一对多 SPI，如 {@code OpenAiChatModelBuilderSpi}）。
     */
    default Set<ModelCapability> capabilities(String provider) {
        return ModelProviderType.fromCode(provider)
                .map(ModelProviderType::getCapabilities)
                .orElseGet(this::capabilities);
    }

    /**
     * 判断指定渠道是否支持某项特定能力。
     */
    default boolean supportsCapability(String provider, ModelCapability capability) {
        return capability != null && capabilities(provider).contains(capability);
    }

    /**
     * 仅基于 {@link ModelConfig} 构建轻量级运行时 {@link ChatOptions}（不创建底层连接池或模型实例，适用于按请求动态传参）。
     */
    default ChatOptions buildOptions(ModelConfig config) {
        return build(config).getOptions();
    }

    /**
     * 基于 {@link ModelConfig}（含通用参数与 {@code extras} 扩展参数）构建 Spring AI 原生 {@link ChatModel} 实例。
     */
    ChatModel build(ModelConfig config);
}
