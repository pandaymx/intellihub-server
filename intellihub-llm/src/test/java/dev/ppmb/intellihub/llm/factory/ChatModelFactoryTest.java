package dev.ppmb.intellihub.llm.factory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ppmb.intellihub.core.exception.GlobalErrorCodeConstants;
import dev.ppmb.intellihub.core.exception.IntelliHubException;
import dev.ppmb.intellihub.llm.config.ModelConfig;
import dev.ppmb.intellihub.llm.config.ModelProviderType;
import dev.ppmb.intellihub.llm.spi.ChatModelBuilderSpi;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.bedrock.converse.BedrockProxyChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.mistralai.MistralAiChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;

class ChatModelFactoryTest {

    private final ChatModelFactory factory = new ChatModelFactory();

    @Test
    void serviceLoader_discoversAllEightNativeSpiBuilders() {
        assertThat(factory.getBuilders()).hasSize(8);

        for (ModelProviderType type : ModelProviderType.values()) {
            assertThat(factory.findBuilder(type.getCode()))
                    .as("Provider %s should be supported", type.getCode())
                    .isPresent();
        }

        // 验证常见别名
        for (String alias :
                List.of(
                        "claude",
                        "gemini",
                        "azure",
                        "bedrock",
                        "mistral",
                        "dmr",
                        "qxotic-jinfer",
                        "dashscope",
                        "qwen",
                        "ark",
                        "doubao",
                        "glm",
                        "kimi")) {
            assertThat(factory.findBuilder(alias))
                    .as("Alias %s should be supported", alias)
                    .isPresent();
        }
    }

    @Test
    void buildNativeChatModels_createsExpectedSpringAiImplementations() {
        // 1. OpenAI & OpenAI-compatible (Groq / NVIDIA / Perplexity / MiniMax / Azure / DMR)
        ChatModel openAiModel =
                factory.createChatModel(
                        ModelConfig.builder(ModelProviderType.OPENAI)
                                .apiKey("sk-test-openai")
                                .model("gpt-4o-mini")
                                .temperature(0.5)
                                .maxTokens(1024)
                                .reasoningEffort("low")
                                .build());
        assertThat(openAiModel).isInstanceOf(OpenAiChatModel.class);
        assertThat(openAiModel.getOptions().getModel()).isEqualTo("gpt-4o-mini");

        ChatModel azureModel =
                factory.createChatModel(
                        ModelConfig.builder(ModelProviderType.AZURE_OPENAI)
                                .apiKey("azure-key")
                                .baseUrl("https://my-resource.openai.azure.com")
                                .model("gpt-4o")
                                .extra(ModelConfig.EXTRA_DEPLOYMENT_NAME, "my-gpt4o-deployment")
                                .build());
        assertThat(azureModel).isInstanceOf(OpenAiChatModel.class);

        ChatModel groqModel =
                factory.createChatModel(
                        ModelConfig.of("groq", "gsk-test", "llama-3.3-70b-versatile"));
        assertThat(groqModel).isInstanceOf(OpenAiChatModel.class);

        ChatModel dmrModel =
                factory.createChatModel(
                        ModelConfig.builder(ModelProviderType.DOCKER_MODEL_RUNNER).build());
        assertThat(dmrModel).isInstanceOf(OpenAiChatModel.class);

        // 2. DeepSeek Native
        ChatModel deepSeekModel =
                factory.createChatModel(
                        ModelConfig.builder(ModelProviderType.DEEPSEEK)
                                .apiKey("sk-deepseek")
                                .model("deepseek-reasoner")
                                .thinkingEnabled(true)
                                .build());
        assertThat(deepSeekModel).isInstanceOf(DeepSeekChatModel.class);
        assertThat(deepSeekModel.getOptions().getModel()).isEqualTo("deepseek-reasoner");

        // 3. Anthropic Claude Native
        ChatModel anthropicModel =
                factory.createChatModel(
                        ModelConfig.builder("claude")
                                .apiKey("sk-ant-test")
                                .model("claude-sonnet-4-20250514")
                                .thinkingEnabled(true)
                                .thinkingBudget(4096)
                                .timeout(Duration.ofSeconds(30))
                                .build());
        assertThat(anthropicModel).isInstanceOf(AnthropicChatModel.class);
        assertThat(anthropicModel.getOptions().getModel()).isEqualTo("claude-sonnet-4-20250514");

        // 4. Google GenAI (Gemini) Native
        ChatModel geminiModel =
                factory.createChatModel(
                        ModelConfig.builder("gemini")
                                .apiKey("AIzaSyTestKey")
                                .model("gemini-2.5-flash")
                                .thinkingEnabled(true)
                                .thinkingBudget(1024)
                                .build());
        assertThat(geminiModel).isInstanceOf(GoogleGenAiChatModel.class);
        assertThat(geminiModel.getOptions().getModel()).isEqualTo("gemini-2.5-flash");

        // 5. Ollama Native
        ChatModel ollamaModel =
                factory.createChatModel(
                        ModelConfig.builder(ModelProviderType.OLLAMA)
                                .model("qwen3:8b")
                                .thinkingEnabled(false)
                                .extra(ModelConfig.EXTRA_OLLAMA_NUM_CTX, 16384)
                                .extra(ModelConfig.EXTRA_OLLAMA_KEEP_ALIVE, "10m")
                                .build());
        assertThat(ollamaModel).isInstanceOf(OllamaChatModel.class);
        assertThat(ollamaModel.getOptions().getModel()).isEqualTo("qwen3:8b");

        // 6. Mistral AI Native
        ChatModel mistralModel =
                factory.createChatModel(
                        ModelConfig.of("mistral", "mistral-key", "mistral-large-latest"));
        assertThat(mistralModel).isInstanceOf(MistralAiChatModel.class);
        assertThat(mistralModel.getOptions().getModel()).isEqualTo("mistral-large-latest");

        // 7. Amazon Bedrock Converse Native
        ChatModel bedrockModel =
                factory.createChatModel(
                        ModelConfig.builder(ModelProviderType.AMAZON_BEDROCK_CONVERSE)
                                .apiKey(
                                        "AKIAIOSFODNN7EXAMPLE:wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
                                .model("us.anthropic.claude-sonnet-4-20250514-v1:0")
                                .extra(ModelConfig.EXTRA_AWS_REGION, "us-west-2")
                                .build());
        assertThat(bedrockModel).isInstanceOf(BedrockProxyChatModel.class);
    }

    @Test
    void registerCustomSpi_allowsPluggableExtensionAndChatClientExecution() {
        ChatModelFactory customFactory = new ChatModelFactory();
        customFactory.register(
                new ChatModelBuilderSpi() {
                    @Override
                    public String provider() {
                        return "mock-custom";
                    }

                    @Override
                    public ChatModel build(ModelConfig config) {
                        return prompt ->
                                new ChatResponse(
                                        List.of(
                                                new Generation(
                                                        new AssistantMessage(
                                                                "echo:"
                                                                        + config.model()
                                                                        + ":"
                                                                        + config.getExtraString(
                                                                                "tag", "none")))));
                    }
                });

        ModelConfig config =
                ModelConfig.builder("mock-custom")
                        .model("my-model-v1")
                        .extra("tag", "plugin-ok")
                        .build();

        ChatClient chatClient = customFactory.createChatClient(config);
        String reply = chatClient.prompt("hi").call().content();
        assertThat(reply).isEqualTo("echo:my-model-v1:plugin-ok");
    }

    @Test
    void createChatModel_whenProviderUnknown_throwsIntelliHubException() {
        assertThatThrownBy(() ->
                        factory.createChatModel(ModelConfig.of("unknown-vendor", "key", "model")))
                .isInstanceOf(IntelliHubException.class)
                .satisfies(ex -> {
                    IntelliHubException ihe = (IntelliHubException) ex;
                    assertThat(ihe.getCode())
                            .isEqualTo(GlobalErrorCodeConstants.AI_PROVIDER_NOT_FOUND.getCode());
                });
    }
}
