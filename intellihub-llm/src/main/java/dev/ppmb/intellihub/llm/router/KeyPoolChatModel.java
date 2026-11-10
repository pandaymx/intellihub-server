package dev.ppmb.intellihub.llm.router;

import dev.ppmb.intellihub.core.exception.AiAuthenticationException;
import dev.ppmb.intellihub.core.exception.IntelliHubException;
import dev.ppmb.intellihub.core.exception.IntelliHubExceptionUtil;
import dev.ppmb.intellihub.llm.config.ModelConfig;
import dev.ppmb.intellihub.llm.factory.ChatModelFactory;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * 基于 {@link ApiKeyPool} 的多 Key 轮询与自动故障切换 {@link ChatModel} 装饰器。
 *
 * <ul>
 *   <li>每次调用时从 {@link ApiKeyPool} 获取当前健康的 API Key，并通过 {@link ChatModelFactory}
 *       复用该 Key 对应的缓存底层 {@link ChatModel} 实例；
 *   <li>在流式调用 {@link #stream(Prompt)} 中内置 {@code hasEmitted} 首包状态守卫：仅在<b>首包尚未吐字前</b>发生异常时自动切换
 *       Key 重试；一旦已有有效文本 Chunk 下发至客户端，若中途断流则直接向上抛出异常，严防半截残句与新回答拼接错乱。
 * </ul>
 */
public class KeyPoolChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(KeyPoolChatModel.class);

    private final ModelConfig baseConfig;
    private final ApiKeyPool apiKeyPool;
    private final ChatModelFactory chatModelFactory;

    public KeyPoolChatModel(
            ModelConfig baseConfig, ApiKeyPool apiKeyPool, ChatModelFactory chatModelFactory) {
        this.baseConfig = Objects.requireNonNull(baseConfig, "baseConfig must not be null");
        this.apiKeyPool = Objects.requireNonNull(apiKeyPool, "apiKeyPool must not be null");
        this.chatModelFactory =
                Objects.requireNonNull(chatModelFactory, "chatModelFactory must not be null");
    }

    public ApiKeyPool getApiKeyPool() {
        return apiKeyPool;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        int maxAttempts = Math.max(1, apiKeyPool.totalKeyCount());
        IntelliHubException lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String key = apiKeyPool.acquireKey();
            try {
                ChatModel delegate = resolveModelForKey(key);
                ChatResponse response = delegate.call(prompt);
                apiKeyPool.recordSuccess(key);
                return response;
            } catch (Throwable ex) {
                apiKeyPool.recordFailure(key, ex);
                lastException = IntelliHubExceptionUtil.wrap(baseConfig.provider(), ex);
                boolean canSwitchKey =
                        (lastException.isRetryable()
                                        || lastException instanceof AiAuthenticationException)
                                && attempt < maxAttempts
                                && apiKeyPool.availableKeyCount() > 0;
                if (!canSwitchKey) {
                    throw lastException;
                }
                log.warn(
                        "[IntelliHub-KeyPool] Key ({}) call failed on provider [{}], switching to next available key (attempt {}/{}): {}",
                        ApiKeyPool.maskKey(key),
                        baseConfig.provider(),
                        attempt,
                        maxAttempts,
                        lastException.getMessage());
            }
        }
        throw lastException != null
                ? lastException
                : IntelliHubExceptionUtil.invalidParamException("KeyPool 调用失败");
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        int maxAttempts = Math.max(1, apiKeyPool.totalKeyCount());
        return streamWithKeyRetry(prompt, 1, maxAttempts);
    }

    @Override
    public ChatOptions getOptions() {
        String firstKey = apiKeyPool.getKeys().get(0);
        return chatModelFactory.createChatOptions(baseConfig.toBuilder().apiKey(firstKey).build());
    }

    private Flux<ChatResponse> streamWithKeyRetry(Prompt prompt, int attempt, int maxAttempts) {
        return Flux.defer(() -> {
            String key = apiKeyPool.acquireKey();
            ChatModel delegate;
            try {
                delegate = resolveModelForKey(key);
            } catch (Throwable ex) {
                apiKeyPool.recordFailure(key, ex);
                return handleStreamError(prompt, attempt, maxAttempts, key, false, ex);
            }
            AtomicBoolean hasEmitted = new AtomicBoolean(false);
            return delegate.stream(prompt)
                    .doOnNext(chunk -> {
                        if (hasEmittedContent(chunk)) {
                            hasEmitted.set(true);
                        }
                    })
                    .doOnComplete(() -> apiKeyPool.recordSuccess(key))
                    .onErrorResume(ex -> {
                        apiKeyPool.recordFailure(key, ex);
                        return handleStreamError(
                                prompt, attempt, maxAttempts, key, hasEmitted.get(), ex);
                    });
        });
    }

    private Flux<ChatResponse> handleStreamError(
            Prompt prompt,
            int attempt,
            int maxAttempts,
            String key,
            boolean hasEmitted,
            Throwable ex) {
        IntelliHubException wrapped = IntelliHubExceptionUtil.wrap(baseConfig.provider(), ex);
        if (hasEmitted) {
            log.warn(
                    "[IntelliHub-KeyPool] Mid-stream failure after token emission on key ({}) of provider [{}], aborting silent retry to prevent garbled output: {}",
                    ApiKeyPool.maskKey(key),
                    baseConfig.provider(),
                    wrapped.getMessage());
            return Flux.error(wrapped);
        }
        boolean canSwitchKey =
                (wrapped.isRetryable() || wrapped instanceof AiAuthenticationException)
                        && attempt < maxAttempts
                        && apiKeyPool.availableKeyCount() > 0;
        if (canSwitchKey) {
            log.warn(
                    "[IntelliHub-KeyPool] Pre-first-token stream failure on key ({}) of provider [{}], switching to next available key (attempt {}/{}): {}",
                    ApiKeyPool.maskKey(key),
                    baseConfig.provider(),
                    attempt,
                    maxAttempts,
                    wrapped.getMessage());
            return streamWithKeyRetry(prompt, attempt + 1, maxAttempts);
        }
        return Flux.error(wrapped);
    }

    static boolean hasEmittedContent(ChatResponse chunk) {
        if (chunk == null || chunk.getResults() == null || chunk.getResults().isEmpty()) {
            return false;
        }
        return chunk.getResults().stream()
                .anyMatch(gen ->
                        gen != null
                                && gen.getOutput() != null
                                && ((gen.getOutput().getText() != null
                                                && !gen.getOutput().getText().isEmpty())
                                        || (gen.getOutput().getToolCalls() != null
                                                && !gen.getOutput().getToolCalls().isEmpty())));
    }

    private ChatModel resolveModelForKey(String apiKey) {
        return chatModelFactory.createChatModel(baseConfig.toBuilder().apiKey(apiKey).build());
    }
}
