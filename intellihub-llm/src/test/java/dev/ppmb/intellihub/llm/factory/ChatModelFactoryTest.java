package dev.ppmb.intellihub.llm.factory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ppmb.intellihub.core.exception.GlobalErrorCodeConstants;
import dev.ppmb.intellihub.core.exception.IntelliHubException;
import dev.ppmb.intellihub.llm.config.ModelCapability;
import dev.ppmb.intellihub.llm.config.ModelConfig;
import dev.ppmb.intellihub.llm.config.ModelProviderType;
import dev.ppmb.intellihub.llm.spi.ChatModelBuilderSpi;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.bedrock.converse.BedrockProxyChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.mistralai.MistralAiChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

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

        // 验证常见别名与私有网关标识
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
                        "kimi",
                        "vllm",
                        "sglang",
                        "oneapi",
                        "litellm",
                        "openrouter",
                        "siliconflow",
                        "openai-compatible:private-cluster-1",
                        "custom:internal-vllm")) {
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
    void caffeineInstanceCache_reusesChatModelAndClosesResourcesOnInvalidateOrClose() {
        AtomicInteger buildCounter = new AtomicInteger(0);
        AtomicBoolean closedFlag = new AtomicBoolean(false);

        final class CloseableMockChatModel implements ChatModel, AutoCloseable {
            private final String modelName;

            CloseableMockChatModel(String modelName) {
                this.modelName = modelName;
            }

            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(
                        List.of(new Generation(new AssistantMessage("ok:" + modelName))));
            }

            @Override
            public void close() {
                closedFlag.set(true);
            }
        }

        ChatModelFactory scopedFactory = new ChatModelFactory();
        scopedFactory.register(
                new ChatModelBuilderSpi() {
                    @Override
                    public String provider() {
                        return "cached-local-engine";
                    }

                    @Override
                    public Set<ModelCapability> capabilities() {
                        return Set.of(ModelCapability.LOCAL_WEIGHTS, ModelCapability.STREAMING);
                    }

                    @Override
                    public ChatModel build(ModelConfig config) {
                        buildCounter.incrementAndGet();
                        return new CloseableMockChatModel(config.model());
                    }
                });

        ModelConfig config1 =
                ModelConfig.builder("cached-local-engine")
                        .model("local-gguf-7b")
                        .extra(ModelConfig.EXTRA_JINFER_MODEL_PATH, "/models/7b.gguf")
                        .temperature(0.2)
                        .build();
        // 仅修改运行时采样参数 temperature，本地权重引擎（LOCAL_WEIGHTS）不应重复加载
        ModelConfig config2 = config1.toBuilder().temperature(0.9).build();

        ChatModel m1 = scopedFactory.createChatModel(config1);
        ChatModel m2 = scopedFactory.createChatModel(config2);

        assertThat(m1).isSameAs(m2);
        assertThat(buildCounter.get()).isEqualTo(1);
        assertThat(scopedFactory.cacheSize()).isEqualTo(1);
        assertThat(closedFlag.get()).isFalse();

        scopedFactory.close();
        assertThat(scopedFactory.cacheSize()).isZero();
        assertThat(closedFlag.get()).isTrue();
    }

    @Test
    void openAiDialectCustomizers_sanitizeIncompatibleFieldsAndInjectThinkingExtraBody() {
        // 1. 千问 DashScope 自动注入默认 BaseUrl，并将 thinkingEnabled / thinkingBudget 转为 extraBody
        ChatOptions qwenOptions =
                factory.createChatOptions(
                        ModelConfig.builder("qwen")
                                .apiKey("sk-qwen")
                                .thinkingEnabled(true)
                                .thinkingBudget(2048)
                                .build());
        assertThat(qwenOptions).isInstanceOf(OpenAiChatOptions.class);
        OpenAiChatOptions qwenOpenAiOpts = (OpenAiChatOptions) qwenOptions;
        assertThat(qwenOpenAiOpts.getBaseUrl())
                .isEqualTo("https://dashscope.aliyuncs.com/compatible-mode");
        assertThat(qwenOpenAiOpts.getModel()).isEqualTo("qwen-plus");
        assertThat(qwenOpenAiOpts.getExtraBody())
                .containsEntry("enable_thinking", true)
                .containsEntry("thinking_budget", 2048);

        // 2. 智谱 GLM 自动剔除不支持的 reasoningEffort，避免上游 400 Bad Request
        OpenAiChatOptions glmOpts =
                (OpenAiChatOptions)
                        factory.createChatOptions(
                                ModelConfig.builder("zhipu")
                                        .apiKey("sk-glm")
                                        .reasoningEffort("high")
                                        .build());
        assertThat(glmOpts.getBaseUrl()).isEqualTo("https://open.bigmodel.cn/api/paas/v4");
        assertThat(glmOpts.getModel()).isEqualTo("glm-4-flash");
        assertThat(glmOpts.getReasoningEffort()).isNull();

        // 3. MiniMax 自动清洗不支持的 frequencyPenalty / presencePenalty / reasoningEffort
        OpenAiChatOptions minimaxOpts =
                (OpenAiChatOptions)
                        factory.createChatOptions(
                                ModelConfig.builder(ModelProviderType.MINIMAX)
                                        .apiKey("sk-minimax")
                                        .frequencyPenalty(0.5)
                                        .presencePenalty(0.5)
                                        .reasoningEffort("medium")
                                        .build());
        assertThat(minimaxOpts.getFrequencyPenalty()).isNull();
        assertThat(minimaxOpts.getPresencePenalty()).isNull();
        assertThat(minimaxOpts.getReasoningEffort()).isNull();

        // 4. Perplexity 同时传 frequencyPenalty 与 presencePenalty 时自动剔除冲突项及 reasoningEffort
        OpenAiChatOptions perplexityOpts =
                (OpenAiChatOptions)
                        factory.createChatOptions(
                                ModelConfig.builder(ModelProviderType.PERPLEXITY_AI)
                                        .apiKey("pplx-key")
                                        .frequencyPenalty(0.3)
                                        .presencePenalty(0.4)
                                        .reasoningEffort("low")
                                        .build());
        assertThat(perplexityOpts.getFrequencyPenalty()).isEqualTo(0.3);
        assertThat(perplexityOpts.getPresencePenalty()).isNull();
        assertThat(perplexityOpts.getReasoningEffort()).isNull();
    }

    @Test
    void openAiCompatible_supportsPrivateGatewaysAndDynamicPrefixesWithoutApiKey() {
        // 1. 内置别名 vllm（内网免填 apiKey 自动填充 "EMPTY"）
        ChatModel vllmModel =
                factory.createChatModel(
                        ModelConfig.builder("vllm")
                                .baseUrl("http://10.0.0.15:8000")
                                .model("Qwen/Qwen3-32B")
                                .build());
        assertThat(vllmModel).isInstanceOf(OpenAiChatModel.class);
        assertThat(vllmModel.getOptions().getModel()).isEqualTo("Qwen/Qwen3-32B");

        // 2. 动态前缀 openai-compatible:<custom-id>
        ChatModel dynamicGatewayModel =
                factory.createChatModel(
                        ModelConfig.builder("openai-compatible:corp-oneapi")
                                .baseUrl("https://oneapi.internal.corp")
                                .apiKey("sk-corp")
                                .model("deepseek-v3-internal")
                                .build());
        assertThat(dynamicGatewayModel).isInstanceOf(OpenAiChatModel.class);

        // 3. 任意自定义渠道名 + openaiCompatible(true) 显式标记
        ChatModel arbitraryCompatibleModel =
                factory.createChatModel(
                        ModelConfig.builder("my-enterprise-gateway")
                                .baseUrl("http://192.168.1.100:3000")
                                .model("llama-4-scout")
                                .openaiCompatible(true)
                                .build());
        assertThat(arbitraryCompatibleModel).isInstanceOf(OpenAiChatModel.class);
        assertThat(arbitraryCompatibleModel.getOptions().getModel()).isEqualTo("llama-4-scout");
    }

    @Test
    void capabilitiesDiscoveryAndValidation_reportsSupportedFeaturesAndWarnsOnIncompatibleConfig() {
        // Anthropic 支持 THINKING_BUDGET 和 TOP_K_SAMPLING，不支持 PENALTY_PARAMETERS
        assertThat(factory.supportsCapability("anthropic", ModelCapability.THINKING_BUDGET))
                .isTrue();
        assertThat(factory.supportsCapability("anthropic", ModelCapability.TOP_K_SAMPLING))
                .isTrue();
        assertThat(factory.supportsCapability("anthropic", ModelCapability.PENALTY_PARAMETERS))
                .isFalse();

        // Jinfer 支持 LOCAL_WEIGHTS
        assertThat(factory.supportsCapability("jinfer", ModelCapability.LOCAL_WEIGHTS)).isTrue();
        assertThat(factory.supportsCapability("openai", ModelCapability.LOCAL_WEIGHTS)).isFalse();

        // 给 Mistral 传入不支持的 thinkingBudget、topK 与 frequencyPenalty，校验器应精准返回 3 条告警
        ModelConfig incompatibleMistralConfig =
                ModelConfig.builder("mistral")
                        .apiKey("mistral-key")
                        .thinkingBudget(2048)
                        .topK(40)
                        .frequencyPenalty(0.5)
                        .build();
        List<String> warnings = factory.validateCapabilities(incompatibleMistralConfig);
        assertThat(warnings).hasSize(3);
        assertThat(warnings)
                .anyMatch(msg -> msg.contains(ModelConfig.EXTRA_THINKING_BUDGET))
                .anyMatch(msg -> msg.contains("topK"))
                .anyMatch(msg -> msg.contains("frequencyPenalty"));
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
