package dev.ppmb.intellihub.llm.spi.dialect;

import dev.ppmb.intellihub.llm.config.ModelConfig;
import dev.ppmb.intellihub.llm.config.ModelProviderType;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * OpenAI 兼容协议方言微调器（Strategy Pattern）。
 *
 * <p>用于在构建 {@link OpenAiChatOptions} 前后，针对 Azure OpenAI、千问（DashScope）、豆包（Ark）、智谱（Zhipu）、
 * Kimi（Moonshot）、MiniMax、Perplexity、Docker Model Runner、vLLM/OneAPI 等各兼容厂商的协议差异进行：
 *
 * <ul>
 *   <li>默认端点（Base URL）与默认模型名称补全；
 *   <li>不支持字段的前置清洗与剔除（如剔除部分厂商不支持的 {@code frequencyPenalty}、{@code reasoningEffort}，防止触发
 *       {@code 400 Bad Request}）；
 *   <li>厂商专属扩展字段注入（如 Azure 的 {@code deploymentName}，或千问/豆包的 {@code extraBody.enable_thinking}）。
 * </ul>
 */
public interface OpenAiDialectCustomizer {

    /**
     * 判断当前方言微调器是否适用于目标渠道。
     *
     * @param rawProvider 归一化后的原始渠道名（小写）
     * @param matchedType 匹配到的标准枚举类型
     * @param config 原始模型配置
     */
    boolean supports(String rawProvider, ModelProviderType matchedType, ModelConfig config);

    /**
     * 在构建 {@link OpenAiChatOptions} 之前对 {@link ModelConfig} 进行清洗与默认值补全。
     */
    default ModelConfig sanitizeConfig(
            String rawProvider, ModelProviderType matchedType, ModelConfig config) {
        return config;
    }

    /**
     * 对 {@link OpenAiChatOptions.Builder} 进行厂商专属定制。
     */
    default void customizeOptions(
            String rawProvider,
            ModelProviderType matchedType,
            ModelConfig sanitizedConfig,
            OpenAiChatOptions.Builder optionsBuilder) {}
}
