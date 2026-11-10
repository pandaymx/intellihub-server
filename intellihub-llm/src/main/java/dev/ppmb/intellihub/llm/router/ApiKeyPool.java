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
import java.util.concurrent.atomic.AtomicBoolean;
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
 *   <li><b>瞬态与永久故障分级隔离</b>：
 *       <ul>
 *         <li>触发 {@link AiRateLimitException} (HTTP 429) 或连续可重试异常达到阈值时，进入短期冷却隔离（{@code
 *             cooldownDuration}，默认 60s）；
 *         <li>触发 {@link AiAuthenticationException} (HTTP 401/403 密钥无效或欠费) 时，直接标记为 {@code DEAD}
 *             永久摘除并输出 {@code ERROR} 告警日志，避免每分钟放行一次探活导致周期性请求报错；
 *       </ul>
 *   <li><b>CAS 单请求半开探活（防并发惊群）</b>：冷却期满后仅允许 1 个并发请求通过 {@code CAS} 抢占半开探活锁试水，其余并发请求继续走健康
 *       Key；探活成功后全量恢复，失败则重新进入冷却。
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
     * 按 Round-Robin 选取下一个健康或抢占到半开探活锁的 API Key；若全部不可用则抛出 {@code AI_KEY_POOL_EXHAUSTED}。
     */
    public String acquireKey() {
        int size = keys.size();
        long now = clock.getAsLong();
        int start = Math.floorMod(cursor.getAndIncrement(), size);
        for (int i = 0; i < size; i++) {
            String candidate = keys.get((start + i) % size);
            KeyState state = keyStates.get(candidate);
            if (state == null || state.tryAcquire(now)) {
                return candidate;
            }
        }
        throw IntelliHubExceptionUtil.exception(GlobalErrorCodeConstants.AI_KEY_POOL_EXHAUSTED);
    }

    /**
     * 记录指定 Key 调用成功，重置连续失败计数、冷却状态与半开探活锁。
     */
    public void recordSuccess(String apiKey) {
        if (apiKey == null) {
            return;
        }
        KeyState state = keyStates.get(apiKey);
        if (state != null) {
            state.onSuccess();
        }
    }

    /**
     * 记录指定 Key 调用失败：
     *
     * <ul>
     *   <li>若为 401/403 {@link AiAuthenticationException}，标记为 {@code DEAD} 永久隔离并打印 {@code ERROR}
     *       日志；
     *   <li>若为 429 {@link AiRateLimitException} 或可重试异常达到阈值，置入短期冷却并释放半开探活锁。
     * </ul>
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
        if (wrapped instanceof AiAuthenticationException) {
            state.markDead();
            log.error(
                    "[IntelliHub-KeyPool-DEAD] API Key ({}) for provider [{}] marked DEAD (401/403 authentication failure) and isolated from pool: {}",
                    maskKey(apiKey),
                    provider,
                    wrapped.getMessage());
            return;
        }

        int failures = state.consecutiveFailures.incrementAndGet();
        boolean shouldCooldown =
                wrapped instanceof AiRateLimitException
                        || (wrapped.isRetryable() && failures >= failureThreshold)
                        || state.probing.get();
        if (shouldCooldown) {
            long until = clock.getAsLong() + cooldownMillis;
            state.enterCooldown(until);
            log.warn(
                    "[IntelliHub-KeyPool] API Key ({}) for provider [{}] entered cooldown for {}ms due to: {}",
                    maskKey(apiKey),
                    provider,
                    cooldownMillis,
                    wrapped.getMessage());
        } else {
            state.probing.set(false);
        }
    }

    /**
     * 手动复活被标记为 {@code DEAD} 或处于冷却期的 API Key（适用于后台续费或更新配额后恢复）。
     */
    public void reviveKey(String apiKey) {
        if (apiKey == null) {
            return;
        }
        KeyState state = keyStates.get(apiKey);
        if (state != null) {
            state.revive();
        }
    }

    /**
     * 判断指定 Key 是否已被标记为永久失效（{@code DEAD}）。
     */
    public boolean isKeyDead(String apiKey) {
        KeyState state = keyStates.get(apiKey);
        return state != null && state.dead.get();
    }

    /**
     * 返回当前可用（未处于 {@code DEAD}、未在冷却期且未被其他线程占用半开探活锁）的 API Key 数量。
     */
    public int availableKeyCount() {
        long now = clock.getAsLong();
        return (int)
                keys.stream()
                        .map(keyStates::get)
                        .filter(s -> s == null || s.isSelectableSnapshot(now))
                        .count();
    }

    public int deadKeyCount() {
        return (int)
                keys.stream().map(keyStates::get).filter(s -> s != null && s.dead.get()).count();
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
        private final AtomicBoolean dead = new AtomicBoolean(false);
        private final AtomicBoolean probing = new AtomicBoolean(false);
        private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        private final AtomicLong cooldownUntilMillis = new AtomicLong(0L);

        boolean tryAcquire(long nowMillis) {
            if (dead.get()) {
                return false;
            }
            long until = cooldownUntilMillis.get();
            if (until <= 0L) {
                return true;
            }
            if (nowMillis < until) {
                return false;
            }
            // 冷却期已过，进入 HALF_OPEN：仅允许 1 个并发请求通过 CAS 抢占探活锁
            return probing.compareAndSet(false, true);
        }

        boolean isSelectableSnapshot(long nowMillis) {
            if (dead.get()) {
                return false;
            }
            long until = cooldownUntilMillis.get();
            if (until <= 0L) {
                return true;
            }
            return nowMillis >= until && !probing.get();
        }

        void onSuccess() {
            consecutiveFailures.set(0);
            cooldownUntilMillis.set(0L);
            probing.set(false);
        }

        void enterCooldown(long untilMillis) {
            cooldownUntilMillis.set(untilMillis);
            probing.set(false);
        }

        void markDead() {
            dead.set(true);
            probing.set(false);
        }

        void revive() {
            dead.set(false);
            consecutiveFailures.set(0);
            cooldownUntilMillis.set(0L);
            probing.set(false);
        }
    }
}
