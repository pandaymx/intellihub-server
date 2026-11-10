package dev.ppmb.intellihub.llm.spi.provider;

import com.qxotic.jinfer.spring.ai.JinferChatModel;
import com.qxotic.jinfer.spring.ai.JinferChatOptions;
import dev.ppmb.intellihub.llm.config.ModelConfig;
import dev.ppmb.intellihub.llm.config.ModelProviderType;
import dev.ppmb.intellihub.llm.spi.ChatModelBuilderSpi;
import java.nio.file.Path;
import org.springframework.ai.chat.model.ChatModel;

/**
 * 基于 Qxotic {@link JinferChatModel}（纯 JVM 进程内 GGUF 本地推理引擎）的构建器。
 */
public class JinferChatModelBuilderSpi implements ChatModelBuilderSpi {

    private static final int DEFAULT_CONTEXT_CAPACITY = 8192;

    @Override
    public String provider() {
        return ModelProviderType.QXOTIC_JINFER.getCode();
    }

    @Override
    public boolean supports(String provider) {
        return ModelProviderType.QXOTIC_JINFER.matches(provider);
    }

    @Override
    public JinferChatOptions buildOptions(ModelConfig config) {
        JinferChatOptions.Builder optionsBuilder = JinferChatOptions.builder();
        if (config.temperature() != null) {
            optionsBuilder.temperature(config.temperature());
        }
        if (config.topP() != null) {
            optionsBuilder.topP(config.topP());
        }
        if (config.topK() != null) {
            optionsBuilder.topK(config.topK());
        }
        if (config.maxTokens() != null) {
            optionsBuilder.maxTokens(config.maxTokens());
        }
        if (!config.stopSequences().isEmpty()) {
            optionsBuilder.stopSequences(config.stopSequences());
        }
        if (config.timeout() != null) {
            optionsBuilder.timeout(config.timeout());
        }

        config.getExtraBoolean(ModelConfig.EXTRA_THINKING_ENABLED)
                .ifPresent(optionsBuilder::thinking);
        config.getExtraInt(ModelConfig.EXTRA_THINKING_BUDGET)
                .ifPresent(optionsBuilder::maxReasoningTokens);
        return optionsBuilder.build();
    }

    @Override
    public ChatModel build(ModelConfig config) {
        JinferChatModel.Builder modelBuilder =
                JinferChatModel.builder()
                        .contextCapacity(
                                config.getExtraInt(ModelConfig.EXTRA_JINFER_CONTEXT_CAPACITY)
                                        .orElse(DEFAULT_CONTEXT_CAPACITY))
                        .options(buildOptions(config));

        config.getExtraInt(ModelConfig.EXTRA_JINFER_RETAIN_SESSIONS)
                .ifPresent(modelBuilder::retainSessions);
        config.getExtraInt(ModelConfig.EXTRA_JINFER_SPECULATION_DEPTH)
                .ifPresent(modelBuilder::speculationDepth);

        String localPath = config.getExtraString(ModelConfig.EXTRA_JINFER_MODEL_PATH, null);
        if (localPath != null && !localPath.isBlank()) {
            modelBuilder.modelPath(Path.of(localPath));
        } else {
            String modelRef =
                    config.resolveModel(ModelProviderType.QXOTIC_JINFER.getDefaultModel());
            modelBuilder.model(modelRef);
        }

        return modelBuilder.build();
    }
}
