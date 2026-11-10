package dev.ppmb.intellihub.llm.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ppmb.intellihub.core.exception.AiRateLimitException;
import dev.ppmb.intellihub.core.exception.AiTimeoutException;
import dev.ppmb.intellihub.core.exception.GlobalErrorCodeConstants;
import dev.ppmb.intellihub.core.exception.IntelliHubException;
import dev.ppmb.intellihub.llm.config.ModelConfig;
import dev.ppmb.intellihub.llm.factory.ChatModelFactory;
import dev.ppmb.intellihub.llm.registry.IntelliHubModelRegistry;
import dev.ppmb.intellihub.llm.spi.ChatModelBuilderSpi;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

class RoutingAndKeyPoolTest {

    @Test
    void apiKeyPool_rotatesKeysAndCoolsDownRateLimitedKeysAndRecoversAfterCooldown() {
        AtomicLong fakeClock = new AtomicLong(1000L);
        ApiKeyPool pool =
                new ApiKeyPool(
                        "deepseek",
                        List.of("sk-key-1", "sk-key-2", "sk-key-3"),
                        Duration.ofSeconds(60),
                        1,
                        fakeClock::get);

        assertThat(pool.acquireKey()).isEqualTo("sk-key-1");
        assertThat(pool.acquireKey()).isEqualTo("sk-key-2");
        assertThat(pool.acquireKey()).isEqualTo("sk-key-3");
        assertThat(pool.acquireKey()).isEqualTo("sk-key-1");

        // 模拟 sk-key-1 与 sk-key-2 触发 429 限流进入 60s 冷却期
        pool.recordFailure(
                "sk-key-1", new AiRateLimitException("deepseek", "429 Too Many Requests", null));
        pool.recordFailure(
                "sk-key-2", new AiRateLimitException("deepseek", "429 Quota Exceeded", null));

        assertThat(pool.availableKeyCount()).isEqualTo(1);
        assertThat(pool.acquireKey()).isEqualTo("sk-key-3");
        assertThat(pool.acquireKey()).isEqualTo("sk-key-3");

        // 模拟最后一个 sk-key-3 也触发限流，此时 KeyPool 耗尽应抛出 AI_KEY_POOL_EXHAUSTED
        pool.recordFailure(
                "sk-key-3", new AiRateLimitException("deepseek", "429 Rate Limit", null));
        assertThat(pool.availableKeyCount()).isZero();
        assertThatThrownBy(pool::acquireKey)
                .isInstanceOf(IntelliHubException.class)
                .satisfies(ex ->
                        assertThat(((IntelliHubException) ex).getCode())
                                .isEqualTo(
                                        GlobalErrorCodeConstants.AI_KEY_POOL_EXHAUSTED.getCode()));

        // 时钟推进 61 秒，冷却期结束，所有 Key 自动恢复可用
        fakeClock.addAndGet(61_000L);
        assertThat(pool.availableKeyCount()).isEqualTo(3);
        assertThat(pool.acquireKey()).isIn("sk-key-1", "sk-key-2", "sk-key-3");
    }

    @Test
    void keyPoolChatModel_automaticallySwitchesToHealthyKeyWhenFirstKeyFails() {
        ChatModelFactory customFactory = new ChatModelFactory();
        customFactory.register(
                new ChatModelBuilderSpi() {
                    @Override
                    public String provider() {
                        return "mock-pooled";
                    }

                    @Override
                    public ChatModel build(ModelConfig config) {
                        return prompt -> {
                            if ("bad-key-1".equals(config.apiKey())) {
                                throw new AiRateLimitException(
                                        "mock-pooled", "429 key-1 rate limited", null);
                            }
                            return new ChatResponse(
                                    List.of(
                                            new Generation(
                                                    new AssistantMessage(
                                                            "success-from-" + config.apiKey()))));
                        };
                    }
                });

        ApiKeyPool keyPool =
                new ApiKeyPool(
                        "mock-pooled", List.of("bad-key-1", "good-key-2"), Duration.ofSeconds(60));
        KeyPoolChatModel pooledModel =
                new KeyPoolChatModel(
                        ModelConfig.builder("mock-pooled").model("test-m").build(),
                        keyPool,
                        customFactory);

        String content = pooledModel.call("hello");
        assertThat(content).isEqualTo("success-from-good-key-2");
        assertThat(keyPool.availableKeyCount()).isEqualTo(1);
    }

    @Test
    void routingChatModel_failoverAndCircuitBreakerAndStreamRecovery() {
        AtomicLong fakeClock = new AtomicLong(10_000L);
        AtomicInteger primaryCalls = new AtomicInteger(0);
        AtomicInteger secondaryCalls = new AtomicInteger(0);

        ChatModel brokenPrimary =
                new ChatModel() {
                    @Override
                    public ChatResponse call(Prompt prompt) {
                        primaryCalls.incrementAndGet();
                        throw new AiTimeoutException("claude", "Read timed out", null);
                    }

                    @Override
                    public Flux<ChatResponse> stream(Prompt prompt) {
                        primaryCalls.incrementAndGet();
                        return Flux.error(
                                new AiRateLimitException("claude", "429 Overloaded", null));
                    }
                };

        ChatModel healthySecondary =
                new ChatModel() {
                    @Override
                    public ChatResponse call(Prompt prompt) {
                        secondaryCalls.incrementAndGet();
                        return new ChatResponse(
                                List.of(
                                        new Generation(
                                                new AssistantMessage("fallback-deepseek-ok"))));
                    }

                    @Override
                    public Flux<ChatResponse> stream(Prompt prompt) {
                        secondaryCalls.incrementAndGet();
                        return Flux.just(
                                new ChatResponse(
                                        List.of(
                                                new Generation(
                                                        new AssistantMessage(
                                                                "stream-fallback-ok")))));
                    }
                };

        RoutingChatModel router =
                RoutingChatModel.builder("primary-to-secondary")
                        .strategy(RoutingStrategy.FAILOVER)
                        .failureThreshold(1)
                        .cooldownDuration(Duration.ofSeconds(30))
                        .clock(fakeClock::get)
                        .target(ModelRouteTarget.ofModel("claude-primary", "claude", brokenPrimary))
                        .target(
                                ModelRouteTarget.ofModel(
                                        "deepseek-secondary", "deepseek", healthySecondary))
                        .build();

        // 第一次调用：主节点超时失败，自动降级到备节点，且主节点进入 30s 熔断冷却
        assertThat(router.call("hi")).isEqualTo("fallback-deepseek-ok");
        assertThat(primaryCalls.get()).isEqualTo(1);
        assertThat(secondaryCalls.get()).isEqualTo(1);
        assertThat(router.isTargetHealthy("claude-primary")).isFalse();

        // 第二次调用（冷却期内）：直接跳过已熔断的主节点，零延迟命中备节点
        assertThat(router.call("hi again")).isEqualTo("fallback-deepseek-ok");
        assertThat(primaryCalls.get()).isEqualTo(1);
        assertThat(secondaryCalls.get()).isEqualTo(2);

        // 时钟推进 31 秒，主节点半开恢复，验证 stream() 流式调用同样支持自动降级
        fakeClock.addAndGet(31_000L);
        assertThat(router.isTargetHealthy("claude-primary")).isTrue();
        List<ChatResponse> streamChunks =
                router.stream(new Prompt("stream test")).collectList().block();
        assertThat(streamChunks).hasSize(1);
        assertThat(streamChunks.get(0).getResult().getOutput().getText())
                .isEqualTo("stream-fallback-ok");
        assertThat(primaryCalls.get()).isEqualTo(2);
        assertThat(secondaryCalls.get()).isEqualTo(3);
    }

    @Test
    void routingChatModel_roundRobinDistributesTrafficAcrossHealthyTargets() {
        List<String> invokedOrder = new ArrayList<>();
        ChatModel nodeA =
                prompt -> {
                    invokedOrder.add("A");
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("A"))));
                };
        ChatModel nodeB =
                prompt -> {
                    invokedOrder.add("B");
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("B"))));
                };

        RoutingChatModel rrRouter =
                RoutingChatModel.builder("rr-route")
                        .strategy(RoutingStrategy.ROUND_ROBIN)
                        .target(ModelRouteTarget.ofModel("node-a", "openai", nodeA))
                        .target(ModelRouteTarget.ofModel("node-b", "qwen", nodeB))
                        .build();

        rrRouter.call("1");
        rrRouter.call("2");
        rrRouter.call("3");
        rrRouter.call("4");

        assertThat(invokedOrder).containsExactly("A", "B", "A", "B");
    }

    @Test
    void modelRegistry_registersChannelsAndRoutesAndCreatesChatClients() {
        ChatModelFactory factory = new ChatModelFactory();
        IntelliHubModelRegistry registry = new IntelliHubModelRegistry(factory);

        registry.registerKeyPoolChannel(
                        "deepseek-pool",
                        ModelConfig.builder("deepseek").model("deepseek-chat").build(),
                        List.of("sk-ds-1", "sk-ds-2"),
                        Duration.ofSeconds(30))
                .registerChannel("qwen-backup", ModelConfig.of("qwen", "sk-qwen", "qwen-plus"))
                .registerFailoverRoute("default-ha", List.of("deepseek-pool", "qwen-backup"))
                .setDefaultChannelOrRoute("default-ha");

        assertThat(registry.getChannelNames())
                .containsExactlyInAnyOrder("deepseek-pool", "qwen-backup");
        assertThat(registry.getRouteNames()).containsExactly("default-ha");
        assertThat(registry.getDefaultChatModel()).isInstanceOf(RoutingChatModel.class);
        assertThat(registry.getChatModel("deepseek-pool")).isInstanceOf(KeyPoolChatModel.class);
        assertThat(registry.getDefaultChatClient()).isNotNull();
    }
}
