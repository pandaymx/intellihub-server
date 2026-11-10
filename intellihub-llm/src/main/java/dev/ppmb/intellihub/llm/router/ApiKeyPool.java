package dev.ppmb.intellihub.llm.router;

import dev.ppmb.intellihub.core.exception.AiAuthenticationException;
import dev.ppmb.intellihub.core.exception.AiRateLimitException;
import dev.ppmb.intellihub.core.exception.GlobalErrorCodeConstants;
import dev.ppmb.intellihub.core.exception.IntelliHubException;
import dev.ppmb.intellihub.core.exception.IntelliHubExceptionUtil;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 多 API Key 轮询与故障冷却池（Key Pool & Circuit Breaker）。
 *
 * <ul>
 *   <li>支持同渠道配置多个 API Key 按 Round-Robin 无锁轮询分发请求；
 *   <li>当某个 Key 触发 {@link AiRateLimitException} (HTTP 429)、{@link AiAuthenticationException} (HTTP
 *       401/403) 或连续可重试失败达到阈值时，自动将该 Key 置入冷却隔离期（{@code cooldownDuration}）；
 *   <li>冷却期满后自动恢复调度；若池内所有 Key 均处于冷却期，则抛出 {@link
 *       GlobalErrorCodeConstants#AI_KEY_POOL_EXHAUSTED}，供上层跨渠道路由器触发降级。
 * </ul>
 */
public class ApiKeyPool {

    public static final Duration DEFAULT_COOLDOWN = Duration.ofSeconds(60);
    public static final int DEFAULT_FAILURE_THRESHOLD = 1;

    private static final Logger log = LoggerFactory.getLogger(ApiKeyPool.class);

    private final String provider;
    private final List<String> keys;
    private final Map<String, KeyState> keyStates = new ConcurrentHashMap<>();
    private final long cooldownMillis;
    private final int failureThreshold;
    private final LongSupplier clock;
    private final AtomicInteger cursor = new AtomicInteger(0);

    public ApiKeyPool(String provider, Collection<String> apiKeys) {
        this(
                provider,
                apiKeys,
                DEFAULT_COOLDOWN,
                DEFAULT_FAILURE_THRESHOLD,
                System::currentTimeMillis);
    }

    public ApiKeyPool(String provider, Collection<String> apiKeys, Duration cooldownDuration) {
        this(
                provider,
                apiKeys,
                cooldownDuration,
                DEFAULT_FAILURE_THRESHOLD,
                System::currentTimeMillis);
    }

    public ApiKeyPool(
            String provider,
            Collection<String> apiKeys,
            Duration cooldownDuration,
            int failureThreshold,
            LongSupplier clock) {
        this.provider = Objects.requireNonNullElse(provider, "unknown");
        if (apiKeys == null || apiKeys.isEmpty()) {
            throw IntelliHubExceptionUtil.exception(GlobalErrorCodeConstants.AI_KEY_POOL_EXHAUSTED);
        }
        List<String> cleaned =
                apiKeys.stream()
                        .filter(Objects::nonNull)
                        .map(String::strip)
                        .filter(s -> !s.isEmpty())
                        .distinct()
                        .toList();
        if (cleaned.isEmpty()) {
            throw IntelliHubExceptionUtil.exception(GlobalErrorCodeConstants.AI_KEY_POOL_EXHAUSTED);
        }
        this.keys = cleaned;
        this.cooldownMillis =
                cooldownDuration != null ? Math.max(0L, cooldownDuration.toMillis()) : 60_000L;
        this.failureThreshold = Math.max(1, failureThreshold);
        this.clock = Objects.requireNonNullElse(clock, System::currentTimeMillis);
        for (String key : cleaned) {
            this.keyStates.put(key, new KeyState());
        }
    }

    /**
     * 按 Round-Robin 选取下一个未处于冷却期的健康 API Key；若全部处于冷却期则抛出 {@code AI_KEY_POOL_EXHAUSTED}。
     */
    public String acquireKey() {
        int size = keys.size();
        long now = clock.getAsLong();
        int start = Math.floorMod(cursor.getAndIncrement(), size);
        for (int i = 0; i < size; i++) {
            String candidate = keys.get((start + i) % size);
            KeyState state = keyStates.get(candidate);
            if (state == null || state.isAvailable(now)) {
                return candidate;
            }
        }
        throw IntelliHubExceptionUtil.exception(GlobalErrorCodeConstants.AI_KEY_POOL_EXHAUSTED);
    }

    /**
     * 记录指定 Key 调用成功，重置连续失败计数与冷却状态。
     */
    public void recordSuccess(String apiKey) {
        if (apiKey == null) {
            return;
        }
        KeyState state = keyStates.get(apiKey);
        if (state != null) {
            state.reset();
        }
    }

    /**
     * 记录指定 Key 调用失败，在触发 429 限流、401 鉴权错误或达到连续失败阈值时进入冷却隔离。
     */
    public void recordFailure(String apiKey, Throwable error) {
        if (apiKey == null || error == null) {
            return;
        }
        KeyState state = keyStates.get(apiKey);
        if (state == null) {
            return;
        }
        IntelliHubException wrapped = IntelliHubExceptionUtil.wrap(provider, error);
        int failures = state.consecutiveFailures.incrementAndGet();
        boolean immediateCooldown =
                wrapped instanceof AiRateLimitException
                        || wrapped instanceof AiAuthenticationException;
        if (immediateCooldown || (wrapped.isRetryable() && failures >= failureThreshold)) {
            long until = clock.getAsLong() + cooldownMillis;
            state.cooldownUntilMillis.set(until);
            log.warn(
                    "[IntelliHub-KeyPool] API Key ({}) for provider [{}] entered cooldown for {}ms due to: {}",
                    maskKey(apiKey),
                    provider,
                    cooldownMillis,
                    wrapped.getMessage());
        }
    }

    /**
     * 返回当前可用（未处于冷却期）的 API Key 数量。
     */
    public int availableKeyCount() {
        long now = clock.getAsLong();
        return (int)
                keys.stream()
                        .map(keyStates::get)
                        .filter(s -> s == null || s.isAvailable(now))
                        .count();
    }

    public int totalKeyCount() {
        return keys.size();
    }

    public List<String> getKeys() {
        return keys;
    }

    static String maskKey(String key) {
        if (key == null || key.length() <= 6) {
            return "***";
        }
        return key.substring(0, 3) + "***" + key.substring(key.length() - 3);
    }

    private static final class KeyState {
        private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        private final AtomicLong cooldownUntilMillis = new AtomicLong(0L);

        boolean isAvailable(long nowMillis) {
            long until = cooldownUntilMillis.get();
            return until <= 0L || nowMillis >= until;
        }

        void reset() {
            consecutiveFailures.set(0);
            cooldownUntilMillis.set(0L);
        }
    }
}
