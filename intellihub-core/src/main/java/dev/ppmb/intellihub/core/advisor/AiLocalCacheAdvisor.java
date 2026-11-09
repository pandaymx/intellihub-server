package dev.ppmb.intellihub.core.advisor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;

/**
 * 基于 Spring AI {@link CallAdvisor} 的轻量本地缓存拦截器（LRU 容量淘汰 + TTL 过期控制）。
 *
 * <ul>
 *   <li>对相同 Prompt 内容与模型参数的请求自动生成 SHA-256 缓存键；
 *   <li>命中本地缓存时直接短路返回，并在响应上下文中标记 {@code intellihub.cache.hit = true}；
 *   <li>支持通过请求上下文 {@code intellihub.cache.enabled = false} 按次跳过缓存；
 *   <li>零外部组件依赖，开箱即用。
 * </ul>
 */
public class AiLocalCacheAdvisor implements CallAdvisor {

    public static final String CONTEXT_CACHE_ENABLED_KEY = "intellihub.cache.enabled";
    public static final String CONTEXT_CACHE_HIT_KEY = "intellihub.cache.hit";

    private static final Logger log = LoggerFactory.getLogger(AiLocalCacheAdvisor.class);

    private final int maxSize;
    private final long ttlMillis;
    private final int order;
    private final Map<String, CacheEntry> cache;
    private final LongAdder hitCount = new LongAdder();
    private final LongAdder missCount = new LongAdder();

    public AiLocalCacheAdvisor() {
        this(500, Duration.ofMinutes(10), Ordered.HIGHEST_PRECEDENCE + 200);
    }

    public AiLocalCacheAdvisor(int maxSize, Duration ttl) {
        this(maxSize, ttl, Ordered.HIGHEST_PRECEDENCE + 200);
    }

    public AiLocalCacheAdvisor(int maxSize, Duration ttl, int order) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be positive");
        }
        Objects.requireNonNull(ttl, "ttl must not be null");
        this.maxSize = maxSize;
        this.ttlMillis = ttl.toMillis();
        this.order = order;
        this.cache =
                new LinkedHashMap<>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                        return size() > AiLocalCacheAdvisor.this.maxSize;
                    }
                };
    }

    @Override
    public String getName() {
        return "AiLocalCacheAdvisor";
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        if (!isCacheEnabled(request)) {
            return chain.nextCall(request);
        }

        String cacheKey = buildCacheKey(request);
        ChatClientResponse cached = getFromCache(cacheKey);
        if (cached != null) {
            hitCount.increment();
            log.debug("[AI-Cache-Hit] key={}", cacheKey);
            return cached.mutate().context(CONTEXT_CACHE_HIT_KEY, Boolean.TRUE).build();
        }

        missCount.increment();
        ChatClientResponse response = chain.nextCall(request);
        if (response != null && response.chatResponse() != null) {
            putToCache(cacheKey, response);
        }
        return response != null
                ? response.mutate().context(CONTEXT_CACHE_HIT_KEY, Boolean.FALSE).build()
                : null;
    }

    /**
     * 清空全部本地缓存。
     */
    public synchronized void invalidateAll() {
        cache.clear();
    }

    /**
     * 获取当前有效缓存条目数。
     */
    public synchronized int size() {
        evictExpired();
        return cache.size();
    }

    public long getHitCount() {
        return hitCount.sum();
    }

    public long getMissCount() {
        return missCount.sum();
    }

    private synchronized ChatClientResponse getFromCache(String key) {
        CacheEntry entry = cache.get(key);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() > entry.expireAtMillis()) {
            cache.remove(key);
            return null;
        }
        return entry.response();
    }

    private synchronized void putToCache(String key, ChatClientResponse response) {
        long expireAt = System.currentTimeMillis() + ttlMillis;
        cache.put(key, new CacheEntry(response, expireAt));
    }

    private synchronized void evictExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, CacheEntry>> it = cache.entrySet().iterator();
        while (it.hasNext()) {
            if (now > it.next().getValue().expireAtMillis()) {
                it.remove();
            }
        }
    }

    private static boolean isCacheEnabled(ChatClientRequest request) {
        if (request == null || request.context() == null) {
            return true;
        }
        Object flag = request.context().get(CONTEXT_CACHE_ENABLED_KEY);
        if (flag instanceof Boolean b) {
            return b;
        }
        return true;
    }

    static String buildCacheKey(ChatClientRequest request) {
        Prompt prompt = request.prompt();
        StringBuilder raw = new StringBuilder();
        if (prompt != null) {
            ChatOptions options = prompt.getOptions();
            if (options != null) {
                raw.append("model=").append(options.getModel()).append('|');
                raw.append("temp=").append(options.getTemperature()).append('|');
            }
            raw.append("contents=").append(prompt.getContents());
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            return Integer.toHexString(raw.toString().hashCode());
        }
    }

    private record CacheEntry(ChatClientResponse response, long expireAtMillis) {}
}
