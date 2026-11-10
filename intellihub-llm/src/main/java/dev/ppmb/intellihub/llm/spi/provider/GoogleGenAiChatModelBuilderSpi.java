package dev.ppmb.intellihub.llm.spi.provider;

import com.google.genai.Client;
import com.google.genai.types.ClientOptions;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.ProxyOptions;
import com.google.genai.types.ProxyType;
import dev.ppmb.intellihub.core.exception.IntelliHubExceptionUtil;
import dev.ppmb.intellihub.llm.config.ModelConfig;
import dev.ppmb.intellihub.llm.config.ModelProviderType;
import dev.ppmb.intellihub.llm.spi.ChatModelBuilderSpi;
import dev.ppmb.intellihub.llm.spi.support.SharedHttpClientSupport;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;

/**
 * 基于 Spring AI 原生 {@link GoogleGenAiChatModel} 的构建器（支持 Gemini API Key 模式、Vertex AI Express Mode 与
 * GCP Project/Location 模式，并支持 HTTP 代理环境变量自动透传）。
 */
public class GoogleGenAiChatModelBuilderSpi implements ChatModelBuilderSpi {

    private static final Logger log = LoggerFactory.getLogger(GoogleGenAiChatModelBuilderSpi.class);

    /** 自定义代理地址扩展属性 Key（如 {@code "http://127.0.0.1:7897"}） */
    public static final String EXTRA_PROXY_URL = "proxyUrl";

    @Override
    public String provider() {
        return ModelProviderType.GOOGLE_GENAI.getCode();
    }

    @Override
    public boolean supports(String provider) {
        return ModelProviderType.GOOGLE_GENAI.matches(provider);
    }

    @Override
    public GoogleGenAiChatOptions buildOptions(ModelConfig config) {
        String model = config.resolveModel(ModelProviderType.GOOGLE_GENAI.getDefaultModel());
        GoogleGenAiChatOptions.Builder optionsBuilder =
                GoogleGenAiChatOptions.builder().model(model);

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
            optionsBuilder.maxOutputTokens(config.maxTokens());
        }
        if (!config.stopSequences().isEmpty()) {
            optionsBuilder.stopSequences(config.stopSequences());
        }

        config.getExtraBoolean(ModelConfig.EXTRA_THINKING_ENABLED)
                .ifPresent(optionsBuilder::includeThoughts);
        config.getExtraInt(ModelConfig.EXTRA_THINKING_BUDGET)
                .ifPresent(optionsBuilder::thinkingBudget);

        return optionsBuilder.build();
    }

    @Override
    public ChatModel build(ModelConfig config) {
        boolean vertexAi = config.getExtraBoolean(ModelConfig.EXTRA_VERTEX_AI).orElse(false);
        if (!vertexAi && (config.apiKey() == null || config.apiKey().isBlank())) {
            throw IntelliHubExceptionUtil.invalidParamException(
                    "构建 GoogleGenAiChatModel 失败：非 Vertex AI 模式下 apiKey 不能为空");
        }

        Client.Builder clientBuilder = Client.builder();
        if (config.apiKey() != null && !config.apiKey().isBlank()) {
            clientBuilder.apiKey(config.apiKey().strip());
        }
        if (vertexAi) {
            clientBuilder.vertexAI(true);
            config.getExtraString(ModelConfig.EXTRA_GCP_PROJECT).ifPresent(clientBuilder::project);
            config.getExtraString(ModelConfig.EXTRA_GCP_LOCATION)
                    .ifPresent(clientBuilder::location);
        }

        if ((config.baseUrl() != null && !config.baseUrl().isBlank())
                || config.timeout() != null
                || !config.customHeaders().isEmpty()) {
            HttpOptions.Builder httpBuilder = HttpOptions.builder();
            if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
                httpBuilder.baseUrl(config.baseUrl().strip());
            }
            if (config.timeout() != null) {
                httpBuilder.timeout((int) config.timeout().toMillis());
            }
            if (!config.customHeaders().isEmpty()) {
                httpBuilder.headers(config.customHeaders());
            }
            clientBuilder.httpOptions(httpBuilder.build());
        }

        resolveProxyOptions(config)
                .ifPresent(proxyOpts ->
                        clientBuilder.clientOptions(
                                ClientOptions.builder().proxyOptions(proxyOpts).build()));

        Client genAiClient = clientBuilder.build();
        GoogleGenAiChatModel chatModel =
                GoogleGenAiChatModel.builder()
                        .genAiClient(genAiClient)
                        .options(buildOptions(config))
                        .build();
        SharedHttpClientSupport.attachCloseable(chatModel, genAiClient);
        return chatModel;
    }

    static Optional<ProxyOptions> resolveProxyOptions(ModelConfig config) {
        String rawProxy =
                config.getExtraString(EXTRA_PROXY_URL)
                        .orElseGet(GoogleGenAiChatModelBuilderSpi::resolveEnvProxyUrl);
        if (rawProxy == null || rawProxy.isBlank()) {
            return Optional.empty();
        }
        try {
            String normalized =
                    rawProxy.contains("://") ? rawProxy.strip() : "http://" + rawProxy.strip();
            URI uri = URI.create(normalized);
            String host = uri.getHost();
            int port = uri.getPort();
            if (host == null || host.isBlank() || port <= 0) {
                return Optional.empty();
            }
            return Optional.of(
                    ProxyOptions.builder()
                            .type(ProxyType.Known.HTTP)
                            .host(host)
                            .port(port)
                            .build());
        } catch (Exception ex) {
            log.debug(
                    "[GoogleGenAiChatModelBuilderSpi] 忽略无法解析的代理地址 [{}]: {}",
                    rawProxy,
                    ex.getMessage());
            return Optional.empty();
        }
    }

    private static String resolveEnvProxyUrl() {
        for (String envKey : List.of("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy")) {
            String val = System.getenv(envKey);
            if (val != null && !val.isBlank()) {
                return val.strip();
            }
        }
        return null;
    }
}
