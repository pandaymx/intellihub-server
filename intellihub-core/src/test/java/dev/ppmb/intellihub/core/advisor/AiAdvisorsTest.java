package dev.ppmb.intellihub.core.advisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ppmb.intellihub.core.exception.AiRateLimitException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

class AiAdvisorsTest {

    @Test
    void loggingAndMetricsAdvisor_recordsDurationAndTokenUsage() {
        AiLoggingAndMetricsAdvisor advisor = new AiLoggingAndMetricsAdvisor();
        ChatClientRequest request =
                new ChatClientRequest(new Prompt("你好，请介绍一下 Spring AI"), Map.of());

        ChatResponseMetadata metadata =
                ChatResponseMetadata.builder()
                        .usage(new DefaultUsage(12, 28, 40))
                        .model("deepseek-chat")
                        .build();
        ChatResponse chatResponse =
                new ChatResponse(List.of(new Generation(new AssistantMessage("你好！"))), metadata);

        CallAdvisorChain chain =
                new StubCallAdvisorChain(req -> new ChatClientResponse(chatResponse, Map.of()));

        ChatClientResponse result = advisor.adviseCall(request, chain);

        assertThat(result.context())
                .containsKey(AiLoggingAndMetricsAdvisor.CONTEXT_DURATION_MS_KEY);
        AiExecutionStats stats = advisor.getStats();
        assertThat(stats.totalCalls()).isEqualTo(1);
        assertThat(stats.successCalls()).isEqualTo(1);
        assertThat(stats.failedCalls()).isEqualTo(0);
        assertThat(stats.totalPromptTokens()).isEqualTo(12);
        assertThat(stats.totalCompletionTokens()).isEqualTo(28);
        assertThat(stats.totalTokens()).isEqualTo(40);
    }

    @Test
    void loggingAndMetricsAdvisor_wrapsUnderlyingException() {
        AiLoggingAndMetricsAdvisor advisor = new AiLoggingAndMetricsAdvisor();
        ChatClientRequest request = new ChatClientRequest(new Prompt("触发限流测试"), Map.of());

        CallAdvisorChain failingChain =
                new StubCallAdvisorChain(req -> {
                    throw new RuntimeException("HTTP 429 rate limit exceeded");
                });

        assertThatThrownBy(() -> advisor.adviseCall(request, failingChain))
                .isInstanceOf(AiRateLimitException.class);
        assertThat(advisor.getStats().failedCalls()).isEqualTo(1);
    }

    @Test
    void localCacheAdvisor_shortCircuitsRepeatedPromptAndRespectsBypassFlag() {
        AiLocalCacheAdvisor cacheAdvisor = new AiLocalCacheAdvisor(100, Duration.ofMinutes(5));
        AtomicInteger downstreamCalls = new AtomicInteger();

        CallAdvisorChain chain =
                new StubCallAdvisorChain(req -> {
                    int callNum = downstreamCalls.incrementAndGet();
                    ChatResponse resp =
                            new ChatResponse(
                                    List.of(new Generation(new AssistantMessage("回答#" + callNum))));
                    return new ChatClientResponse(resp, Map.of());
                });

        ChatClientRequest req1 = new ChatClientRequest(new Prompt("什么是虚拟线程？"), Map.of());
        ChatClientResponse res1 = cacheAdvisor.adviseCall(req1, chain);
        assertThat(res1.context().get(AiLocalCacheAdvisor.CONTEXT_CACHE_HIT_KEY)).isEqualTo(false);
        assertThat(downstreamCalls.get()).isEqualTo(1);

        // 相同 Prompt 第二次调用应直接命中本地缓存，不触发下游
        ChatClientResponse res2 = cacheAdvisor.adviseCall(req1, chain);
        assertThat(res2.context().get(AiLocalCacheAdvisor.CONTEXT_CACHE_HIT_KEY)).isEqualTo(true);
        assertThat(res2.chatResponse().getResult().getOutput().getText()).isEqualTo("回答#1");
        assertThat(downstreamCalls.get()).isEqualTo(1);
        assertThat(cacheAdvisor.getHitCount()).isEqualTo(1);
        assertThat(cacheAdvisor.getMissCount()).isEqualTo(1);

        // 显式关闭缓存时应穿透调用下游
        ChatClientRequest bypassReq =
                new ChatClientRequest(
                        new Prompt("什么是虚拟线程？"),
                        Map.of(AiLocalCacheAdvisor.CONTEXT_CACHE_ENABLED_KEY, false));
        ChatClientResponse res3 = cacheAdvisor.adviseCall(bypassReq, chain);
        assertThat(res3.chatResponse().getResult().getOutput().getText()).isEqualTo("回答#2");
        assertThat(downstreamCalls.get()).isEqualTo(2);
    }

    private static class StubCallAdvisorChain implements CallAdvisorChain {
        private final java.util.function.Function<ChatClientRequest, ChatClientResponse> handler;

        StubCallAdvisorChain(
                java.util.function.Function<ChatClientRequest, ChatClientResponse> handler) {
            this.handler = handler;
        }

        @Override
        public ChatClientResponse nextCall(ChatClientRequest chatClientRequest) {
            return handler.apply(chatClientRequest);
        }

        @Override
        public List<CallAdvisor> getCallAdvisors() {
            return List.of();
        }

        @Override
        public CallAdvisorChain copy(CallAdvisor after) {
            return this;
        }
    }
}
