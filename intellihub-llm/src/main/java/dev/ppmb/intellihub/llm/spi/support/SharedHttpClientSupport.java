package dev.ppmb.intellihub.llm.spi.support;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import dev.ppmb.intellihub.core.context.SpringContextHolder;
import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.bedrock.converse.BedrockProxyChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * SPI 底层共享 HTTP 基础设施与模型实例资源回收管理器。
 *
 * <ul>
 *   <li>提供全局共享的 {@link RestClient.Builder} 与 {@link WebClient.Builder}（复用底层 JDK {@link HttpClient}
 *       与 Reactor Netty 连接池，避免每次构建模型都创建独立连接池）；
 *   <li>支持将模型绑定的外部 {@link AutoCloseable} 资源（如 AWS Bedrock SDK Client、Google GenAI Client、Jinfer
 *       本地引擎、OpenAI/Anthropic SDK Client）注册并在缓存淘汰或容器销毁时自动释放。
 * </ul>
 */
public final class SharedHttpClientSupport {

    private static final Logger log = LoggerFactory.getLogger(SharedHttpClientSupport.class);

    private static final HttpClient SHARED_JDK_HTTP_CLIENT =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

    private static final RestClient.Builder FALLBACK_REST_CLIENT_BUILDER =
            RestClient.builder()
                    .requestFactory(new JdkClientHttpRequestFactory(SHARED_JDK_HTTP_CLIENT));

    private static final WebClient.Builder FALLBACK_WEB_CLIENT_BUILDER = WebClient.builder();

    private static final Map<ChatModel, List<AutoCloseable>> ATTACHED_RESOURCES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private SharedHttpClientSupport() {}

    /**
     * 获取共享的 {@link RestClient.Builder}（优先从 Spring 容器获取并 clone，未启动容器时回退到基于共享 {@link
     * HttpClient} 连接池的构建器）。
     */
    public static RestClient.Builder sharedRestClientBuilder() {
        return SpringContextHolder.getBeanIfAvailable(RestClient.Builder.class)
                .map(RestClient.Builder::clone)
                .orElseGet(FALLBACK_REST_CLIENT_BUILDER::clone);
    }

    /**
     * 获取共享的 {@link WebClient.Builder}（优先从 Spring 容器获取并 clone，未启动容器时回退到全局构建器）。
     */
    public static WebClient.Builder sharedWebClientBuilder() {
        return SpringContextHolder.getBeanIfAvailable(WebClient.Builder.class)
                .map(WebClient.Builder::clone)
                .orElseGet(FALLBACK_WEB_CLIENT_BUILDER::clone);
    }

    /**
     * 将底层 SDK 客户端等 {@link AutoCloseable} 资源挂载到指定 {@link ChatModel} 实例，以便在淘汰或关闭时统一释放。
     */
    public static void attachCloseable(ChatModel chatModel, AutoCloseable... closeables) {
        if (chatModel == null || closeables == null || closeables.length == 0) {
            return;
        }
        synchronized (ATTACHED_RESOURCES) {
            List<AutoCloseable> list =
                    ATTACHED_RESOURCES.computeIfAbsent(chatModel, k -> new ArrayList<>());
            for (AutoCloseable closeable : closeables) {
                if (closeable != null) {
                    list.add(closeable);
                }
            }
        }
    }

    /**
     * 优雅关闭并释放 {@link ChatModel} 及其关联的底层连接池、SDK 客户端或本地推理权重。
     */
    public static void closeQuietly(ChatModel chatModel) {
        if (chatModel == null) {
            return;
        }
        List<AutoCloseable> attached;
        synchronized (ATTACHED_RESOURCES) {
            attached = ATTACHED_RESOURCES.remove(chatModel);
        }
        if (attached != null) {
            for (AutoCloseable closeable : attached) {
                closeSingle(closeable, chatModel.getClass().getSimpleName() + ".attachedResource");
            }
        }
        if (chatModel instanceof AutoCloseable closeable) {
            closeSingle(closeable, chatModel.getClass().getSimpleName());
        }
        if (chatModel instanceof DisposableBean disposable) {
            try {
                disposable.destroy();
            } catch (Exception ex) {
                log.warn(
                        "[IntelliHub-LLM] Destroy DisposableBean {} failed: {}",
                        chatModel.getClass().getSimpleName(),
                        ex.getMessage());
            }
        }
        if (chatModel instanceof AnthropicChatModel anthropicChatModel) {
            closeAnthropicClients(anthropicChatModel);
        } else if (chatModel instanceof OpenAiChatModel openAiChatModel) {
            closeOpenAiClients(openAiChatModel);
        } else if (chatModel instanceof BedrockProxyChatModel bedrockChatModel) {
            closeBedrockClients(bedrockChatModel);
        }
    }

    private static void closeAnthropicClients(AnthropicChatModel model) {
        try {
            if (model.getAnthropicClient() != null) {
                model.getAnthropicClient().close();
            }
        } catch (Exception ex) {
            log.debug("Close AnthropicClient ignored: {}", ex.getMessage());
        }
        try {
            if (model.getAnthropicClientAsync() != null) {
                model.getAnthropicClientAsync().close();
            }
        } catch (Exception ex) {
            log.debug("Close AnthropicClientAsync ignored: {}", ex.getMessage());
        }
    }

    private static void closeOpenAiClients(OpenAiChatModel model) {
        Object syncClient = readFieldQuietly(model, "openAiClient");
        if (syncClient instanceof OpenAIClient client) {
            closeSingle(client::close, "OpenAIClient");
        }
        Object asyncClient = readFieldQuietly(model, "openAiClientAsync");
        if (asyncClient instanceof OpenAIClientAsync clientAsync) {
            closeSingle(clientAsync::close, "OpenAIClientAsync");
        }
    }

    private static void closeBedrockClients(BedrockProxyChatModel model) {
        Object syncClient = readFieldQuietly(model, "bedrockRuntimeClient");
        if (syncClient instanceof AutoCloseable closeable) {
            closeSingle(closeable, "BedrockRuntimeClient");
        }
        Object asyncClient = readFieldQuietly(model, "bedrockRuntimeAsyncClient");
        if (asyncClient instanceof AutoCloseable closeable) {
            closeSingle(closeable, "BedrockRuntimeAsyncClient");
        }
    }

    private static Object readFieldQuietly(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (Exception ex) {
            return null;
        }
    }

    private static void closeSingle(AutoCloseable closeable, String resourceName) {
        try {
            closeable.close();
        } catch (Exception ex) {
            log.warn(
                    "[IntelliHub-LLM] Close resource {} failed: {}", resourceName, ex.getMessage());
        }
    }
}
