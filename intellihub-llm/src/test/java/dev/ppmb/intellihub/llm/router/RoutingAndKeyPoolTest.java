package dev.ppmb.intellihub.llm.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ppmb.intellihub.core.exception.AiAuthenticationException;
import dev.ppmb.intellihub.core.exception.AiRateLimitException;
import dev.ppmb.intellihub.core.exception.AiTimeoutException;
import dev.ppmb.intellihub.llm.config.ModelConfig;
import dev.ppmb.intellihub.llm.factory.ChatModelFactory;
import dev.ppmb.intellihub.llm.registry.IntelliHubModelRegistry;
import dev.ppmb.intellihub.llm.spi.ChatModelBuilderSpi;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

class RoutingAndKeyPoolTest {

    @Test
    void apiKeyPool_distinguishes429TransientCooldownFrom401PermanentDeadIsolation() {
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

        // 1. sk-key-1 触发 401 鉴权失败 -> 直接标记为 DEAD 永久摘除
        pool.recordFailure(
                "sk-key-1", new AiAuthenticationException("deepseek", "401 Invalid API Key", null));
        assertThat(pool.isKeyDead("sk-key-1")).isTrue();
        assertThat(pool.deadKeyCount()).isEqualTo(1);

        // 2. sk-key-2 触发 429 限流 -> 进入 60s 临时冷却
        pool.recordFailure(
                "sk-key-2", new AiRateLimitException("deepseek", "429 Quota Exceeded", null));
        assertThat(pool.isKeyDead("sk-key-2")).isFalse();
        assertThat(pool.availableKeyCount()).isEqualTo(1);
        assertThat(pool.acquireKey()).isEqualTo("sk-key-3");

        // 3. 时钟推进 10 小时：429 冷却的 sk-key-2 已恢复，但 401 DEAD 的 sk-key-1 依然被永久隔离不会放出探活
        fakeClock.addAndGet(Duration.ofHours(10).toMillis());
        assertThat(pool.isKeyDead("sk-key-1")).isTrue();
        assertThat(pool.availableKeyCount()).isEqualTo(2);
        for (int i = 0; i < 6; i++) {
            String key = pool.acquireKey();
            assertThat(key).isIn("sk-key-2", "sk-key-3").isNotEqualTo("sk-key-1");
            pool.recordSuccess(key);
        }

        // 4. 手动调用 reviveKey 后 sk-key-1 才恢复
        pool.reviveKey("sk-key-1");
        assertThat(pool.isKeyDead("sk-key-1")).isFalse();
        assertThat(pool.availableKeyCount()).isEqualTo(3);
    }

    @Test
    void streamMidStreamFailureGuard_abortsSilentFailoverWhenTokensAlreadyEmitted() {
        AtomicInteger secondaryStreamCalls = new AtomicInteger(0);

        ChatModel midStreamFailingPrimary =
                new ChatModel() {
                    @Override
                    public ChatResponse call(Prompt prompt) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public Flux<ChatResponse> stream(Prompt prompt) {
                        ChatResponse firstChunk =
                                new ChatResponse(
                                        List.of(
                                                new Generation(
                                                        new AssistantMessage("前半截DeepSeek回答..."))));
                        return Flux.concat(
                                Flux.just(firstChunk),
                                Flux.error(
                                        new AiTimeoutException(
                                                "deepseek", "Connection reset mid-stream", null)));
                    }
                };

        ChatModel secondaryModel =
                new ChatModel() {
                    @Override
                    public ChatResponse call(Prompt prompt) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public Flux<ChatResponse> stream(Prompt prompt) {
                        secondaryStreamCalls.incrementAndGet();
                        return Flux.just(
                                new ChatResponse(
                                        List.of(
                                                new Generation(
                                                        new AssistantMessage("从头开始的Qwen完整回答")))));
                    }
                };

        RoutingChatModel router =
                RoutingChatModel.builder("mid-stream-guard-route")
                        .strategy(RoutingStrategy.FAILOVER)
                        .target(
                                ModelRouteTarget.ofModel(
                                        "deepseek-primary", "deepseek", midStreamFailingPrimary))
                        .target(ModelRouteTarget.ofModel("qwen-secondary", "qwen", secondaryModel))
                        .build();

        List<String> receivedChunks = new ArrayList<>();
        assertThatThrownBy(() ->
                        router.stream(new Prompt("test"))
                                .doOnNext(resp ->
                                        receivedChunks.add(resp.getResult().getOutput().getText()))
                                .blockLast())
                .isInstanceOf(AiTimeoutException.class);

        // 已下发首包 "前半截DeepSeek回答..." 后断流，严禁静默切换到 qwen-secondary 拼接错乱文本
        assertThat(receivedChunks).containsExactly("前半截DeepSeek回答...");
        assertThat(secondaryStreamCalls.get()).isZero();
    }

    @Test
    void halfOpenCasProbe_preventsThunderingHerdByAllowingOnlySingleConcurrentProbe()
            throws Exception {
        AtomicLong fakeClock = new AtomicLong(1_000L);
        AtomicInteger primaryCalls = new AtomicInteger(0);
        AtomicInteger secondaryCalls = new AtomicInteger(0);
        CountDownLatch probeEnteredPrimary = new CountDownLatch(1);
        CountDownLatch allowProbeToFinish = new CountDownLatch(1);

        ChatModel slowRecoveringPrimary =
                prompt -> {
                    int callNum = primaryCalls.incrementAndGet();
                    if (callNum == 1) {
                        throw new AiRateLimitException("openai", "429 Rate Limit", null);
                    }
                    // 第二次调用（半开探活请求）：进入后通知并发线程，并等待放行信号
                    probeEnteredPrimary.countDown();
                    try {
                        allowProbeToFinish.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return new ChatResponse(
                            List.of(new Generation(new AssistantMessage("primary-probe-ok"))));
                };

        ChatModel fastSecondary =
                prompt -> {
                    secondaryCalls.incrementAndGet();
                    return new ChatResponse(
                            List.of(new Generation(new AssistantMessage("secondary-ok"))));
                };

        RoutingChatModel router =
                RoutingChatModel.builder("thundering-herd-route")
                        .strategy(RoutingStrategy.FAILOVER)
                        .failureThreshold(1)
                        .cooldownDuration(Duration.ofSeconds(30))
                        .clock(fakeClock::get)
                        .target(
                                ModelRouteTarget.ofModel(
                                        "primary-node", "openai", slowRecoveringPrimary))
                        .target(ModelRouteTarget.ofModel("secondary-node", "qwen", fastSecondary))
                        .build();

        // 1. 触发主节点进入 30s 冷却
        assertThat(router.call("trigger-cooldown")).isEqualTo("secondary-ok");
        assertThat(primaryCalls.get()).isEqualTo(1);

        // 2. 时钟推进 31 秒，冷却到期；启动线程 A 发起探活请求并停留在主节点内部
        fakeClock.addAndGet(31_000L);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> probeFuture = pool.submit(() -> router.call("probe-request"));
            assertThat(probeEnteredPrimary.await(5, TimeUnit.SECONDS)).isTrue();

            // 3. 在探活请求尚未完成期间，并发进来 5 个请求：由于 CAS 探活锁已被占用，这 5 个请求应全部直接走备节点，绝不冲击正在恢复的主节点
            for (int i = 0; i < 5; i++) {
                assertThat(router.call("concurrent-" + i)).isEqualTo("secondary-ok");
            }
            assertThat(primaryCalls.get()).isEqualTo(2);

            // 4. 放行探活请求成功返回，主节点全量恢复，后续请求正常走主节点
            allowProbeToFinish.countDown();
            assertThat(probeFuture.get(5, TimeUnit.SECONDS)).isEqualTo("primary-probe-ok");
            assertThat(router.call("after-recovery")).isEqualTo("primary-probe-ok");
            assertThat(primaryCalls.get()).isEqualTo(3);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void crossVendorFailover_sanitizesChatOptionsAndInjectsRouteObservabilityMetadata() {
        AtomicReference<ChatOptions> receivedByBackup = new AtomicReference<>();

        ChatModel failingDeepSeek =
                new ChatModel() {
                    @Override
                    public ChatResponse call(Prompt prompt) {
                        throw new AiRateLimitException("deepseek", "429 busy", null);
                    }

                    @Override
                    public ChatOptions getOptions() {
                        return OpenAiChatOptions.builder().model("deepseek-reasoner").build();
                    }
                };

        ChatModel backupClaude =
                new ChatModel() {
                    @Override
                    public ChatResponse call(Prompt prompt) {
                        receivedByBackup.set(prompt.getOptions());
                        return new ChatResponse(
                                List.of(
                                        new Generation(
                                                new AssistantMessage("claude-fallback-answer"))));
                    }

                    @Override
                    public ChatOptions getOptions() {
                        return AnthropicChatOptions.builder()
                                .model("claude-sonnet-4-20250514")
                                .maxTokens(4096)
                                .build();
                    }
                };

        RoutingChatModel router =
                RoutingChatModel.builder("cross-vendor-route")
                        .strategy(RoutingStrategy.FAILOVER)
                        .target(ModelRouteTarget.ofModel("ds-primary", "deepseek", failingDeepSeek))
                        .target(
                                ModelRouteTarget.ofModel(
                                        "claude-backup", "anthropic", backupClaude))
                        .build();

        // 构造携带源渠道模型名 deepseek-reasoner 及 Anthropic 不支持的 frequencyPenalty 的 Prompt
        Prompt incomingPrompt =
                new Prompt(
                        "Analyze this",
                        ChatOptions.builder()
                                .model("deepseek-reasoner")
                                .temperature(0.25)
                                .topK(30)
                                .frequencyPenalty(0.8)
                                .build());

        ChatResponse response = router.call(incomingPrompt);

        // 1. 验证跨厂商 Options 清洗：模型名被强制替换为备选渠道的 claude-sonnet-4-20250514，保留 temperature/topK，剥离不支持的
        // frequencyPenalty
        ChatOptions backupOpts = receivedByBackup.get();
        assertThat(backupOpts).isNotNull();
        assertThat(backupOpts.getModel()).isEqualTo("claude-sonnet-4-20250514");
        assertThat(backupOpts.getTemperature()).isEqualTo(0.25);
        assertThat(backupOpts.getTopK()).isEqualTo(30);
        assertThat(backupOpts.getFrequencyPenalty()).isNull();

        // 2. 验证 ChatResponse.getMetadata() 中的路由可观测性指标
        assertThat((String) response.getMetadata().get(RoutingChatModel.METADATA_ROUTE_NAME))
                .isEqualTo("cross-vendor-route");
        assertThat((String) response.getMetadata().get(RoutingChatModel.METADATA_ACTUAL_CHANNEL))
                .isEqualTo("claude-backup");
        assertThat((String) response.getMetadata().get(RoutingChatModel.METADATA_ACTUAL_PROVIDER))
                .isEqualTo("anthropic");
        assertThat((Integer) response.getMetadata().get(RoutingChatModel.METADATA_FALLBACK_COUNT))
                .isEqualTo(1);
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
