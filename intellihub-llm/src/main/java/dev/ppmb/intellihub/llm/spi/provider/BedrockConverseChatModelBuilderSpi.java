package dev.ppmb.intellihub.llm.spi.provider;

import dev.ppmb.intellihub.llm.config.ModelConfig;
import dev.ppmb.intellihub.llm.config.ModelProviderType;
import dev.ppmb.intellihub.llm.spi.ChatModelBuilderSpi;
import org.springframework.ai.bedrock.converse.BedrockChatOptions;
import org.springframework.ai.bedrock.converse.BedrockProxyChatModel;
import org.springframework.ai.chat.model.ChatModel;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;

/**
 * 基于 Spring AI 原生 {@link BedrockProxyChatModel}（Amazon Bedrock Converse API）的构建器。
 */
public class BedrockConverseChatModelBuilderSpi implements ChatModelBuilderSpi {

    private static final String DEFAULT_REGION = "us-east-1";

    @Override
    public String provider() {
        return ModelProviderType.AMAZON_BEDROCK_CONVERSE.getCode();
    }

    @Override
    public boolean supports(String provider) {
        return ModelProviderType.AMAZON_BEDROCK_CONVERSE.matches(provider);
    }

    @Override
    public BedrockChatOptions buildOptions(ModelConfig config) {
        String model =
                config.resolveModel(ModelProviderType.AMAZON_BEDROCK_CONVERSE.getDefaultModel());
        BedrockChatOptions.Builder optionsBuilder = BedrockChatOptions.builder().model(model);
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
        return optionsBuilder.build();
    }

    @Override
    public ChatModel build(ModelConfig config) {
        String regionStr = config.getExtraString(ModelConfig.EXTRA_AWS_REGION, DEFAULT_REGION);

        BedrockProxyChatModel.Builder modelBuilder =
                BedrockProxyChatModel.builder()
                        .region(Region.of(regionStr))
                        .options(buildOptions(config));

        if (config.timeout() != null) {
            modelBuilder.timeout(config.timeout());
        }

        String accessKeyId = config.getExtraString(ModelConfig.EXTRA_AWS_ACCESS_KEY_ID, null);
        String secretAccessKey =
                config.getExtraString(ModelConfig.EXTRA_AWS_SECRET_ACCESS_KEY, null);
        String sessionToken = config.getExtraString(ModelConfig.EXTRA_AWS_SESSION_TOKEN, null);

        if ((accessKeyId == null || secretAccessKey == null)
                && config.apiKey() != null
                && config.apiKey().contains(":")) {
            String[] parts = config.apiKey().split(":", 2);
            accessKeyId = parts[0].strip();
            secretAccessKey = parts[1].strip();
        }

        if (accessKeyId != null
                && !accessKeyId.isBlank()
                && secretAccessKey != null
                && !secretAccessKey.isBlank()) {
            if (sessionToken != null && !sessionToken.isBlank()) {
                modelBuilder.credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsSessionCredentials.create(
                                        accessKeyId, secretAccessKey, sessionToken)));
            } else {
                modelBuilder.credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(accessKeyId, secretAccessKey)));
            }
        }

        return modelBuilder.build();
    }
}
