package dev.ppmb.intellihub.core.advisor;

import dev.ppmb.intellihub.core.exception.IntelliHubExceptionUtil;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

/**
 * 基于 Spring AI {@link CallAdvisor} 与 {@link StreamAdvisor} 的通用日志、耗时统计与异常包装拦截器。
 *
 * <ul>
 *   <li>自动记录每次同步/流式 AI 调用的 Prompt 摘要、耗时（ms）与输入/输出 Token 消耗；
 *   <li>在响应上下文中注入 {@code intellihub.durationMs} 耗时元数据；
 *   <li>内置线程安全计数器，可随时通过 {@link #getStats()} 获取全局调用统计快照；
 *   <li>捕获底层模型异常并统一通过 {@link IntelliHubExceptionUtil#wrap(Throwable)} 包装。
 * </ul>
 */
public class AiLoggingAndMetricsAdvisor implements CallAdvisor, StreamAdvisor {

    public static final String CONTEXT_DURATION_MS_KEY = "intellihub.durationMs";

    private static final Logger log = LoggerFactory.getLogger(AiLoggingAndMetricsAdvisor.class);

    private final int order;
    private final LongAdder totalCalls = new LongAdder();
    private final LongAdder successCalls = new LongAdder();
    private final LongAdder failedCalls = new LongAdder();
    private final LongAdder totalDurationMs = new LongAdder();
    private final LongAdder totalPromptTokens = new LongAdder();
    private final LongAdder totalCompletionTokens = new LongAdder();
    private final LongAdder totalTokens = new LongAdder();

    public AiLoggingAndMetricsAdvisor() {
        this(Ordered.HIGHEST_PRECEDENCE + 100);
    }

    public AiLoggingAndMetricsAdvisor(int order) {
        this.order = order;
    }

    @Override
    public String getName() {
        return "AiLoggingAndMetricsAdvisor";
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        long startNs = System.nanoTime();
        totalCalls.increment();
        try {
            ChatClientResponse response = chain.nextCall(request);
            long durationMs = (System.nanoTime() - startNs) / 1_000_000;
            recordSuccess(request, response, durationMs);
            return response.mutate().context(CONTEXT_DURATION_MS_KEY, durationMs).build();
        } catch (Throwable ex) {
            long durationMs = (System.nanoTime() - startNs) / 1_000_000;
            failedCalls.increment();
            totalDurationMs.add(durationMs);
            log.warn(
                    "[AI-Call-Failed] duration={}ms promptSummary={} error={}",
                    durationMs,
                    summarizePrompt(request),
                    ex.getMessage());
            throw IntelliHubExceptionUtil.wrap(ex);
        }
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(
            ChatClientRequest request, StreamAdvisorChain chain) {
        long startNs = System.nanoTime();
        totalCalls.increment();
        return chain.nextStream(request)
                .doOnNext(chunk -> recordUsageIfPresent(chunk.chatResponse()))
                .doOnComplete(() -> {
                    long durationMs = (System.nanoTime() - startNs) / 1_000_000;
                    successCalls.increment();
                    totalDurationMs.add(durationMs);
                    log.info(
                            "[AI-Stream-Completed] duration={}ms promptSummary={}",
                            durationMs,
                            summarizePrompt(request));
                })
                .doOnError(ex -> {
                    long durationMs = (System.nanoTime() - startNs) / 1_000_000;
                    failedCalls.increment();
                    totalDurationMs.add(durationMs);
                    log.warn(
                            "[AI-Stream-Failed] duration={}ms promptSummary={} error={}",
                            durationMs,
                            summarizePrompt(request),
                            ex.getMessage());
                })
                .onErrorMap(IntelliHubExceptionUtil::wrap);
    }

    /**
     * 获取当前累计的调用次数、耗时与 Token 统计快照。
     */
    public AiExecutionStats getStats() {
        long total = totalCalls.sum();
        long duration = totalDurationMs.sum();
        double avg = total > 0 ? (double) duration / total : 0.0;
        return new AiExecutionStats(
                total,
                successCalls.sum(),
                failedCalls.sum(),
                duration,
                avg,
                totalPromptTokens.sum(),
                totalCompletionTokens.sum(),
                totalTokens.sum());
    }

    /**
     * 重置统计计数器。
     */
    public void resetStats() {
        totalCalls.reset();
        successCalls.reset();
        failedCalls.reset();
        totalDurationMs.reset();
        totalPromptTokens.reset();
        totalCompletionTokens.reset();
        totalTokens.reset();
    }

    private void recordSuccess(
            ChatClientRequest request, ChatClientResponse response, long durationMs) {
        successCalls.increment();
        totalDurationMs.add(durationMs);
        ChatResponse chatResponse = response.chatResponse();
        int promptTok = 0;
        int completionTok = 0;
        int allTok = 0;
        if (chatResponse != null && chatResponse.getMetadata() != null) {
            ChatResponseMetadata metadata = chatResponse.getMetadata();
            Usage usage = metadata.getUsage();
            if (usage != null) {
                promptTok = usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
                completionTok =
                        usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;
                allTok =
                        usage.getTotalTokens() != null
                                ? usage.getTotalTokens()
                                : (promptTok + completionTok);
                totalPromptTokens.add(promptTok);
                totalCompletionTokens.add(completionTok);
                totalTokens.add(allTok);
            }
        }
        log.info(
                "[AI-Call-Success] duration={}ms promptTokens={} completionTokens={} totalTokens={} promptSummary={}",
                durationMs,
                promptTok,
                completionTok,
                allTok,
                summarizePrompt(request));
    }

    private void recordUsageIfPresent(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getMetadata() == null) {
            return;
        }
        Usage usage = chatResponse.getMetadata().getUsage();
        if (usage == null) {
            return;
        }
        int promptTok = usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
        int completionTok = usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;
        int allTok =
                usage.getTotalTokens() != null
                        ? usage.getTotalTokens()
                        : (promptTok + completionTok);
        if (allTok > 0) {
            totalPromptTokens.add(promptTok);
            totalCompletionTokens.add(completionTok);
            totalTokens.add(allTok);
        }
    }

    private static String summarizePrompt(ChatClientRequest request) {
        if (request == null || request.prompt() == null) {
            return "";
        }
        String contents = request.prompt().getContents();
        if (contents == null) {
            return "";
        }
        String singleLine = contents.replace('\n', ' ').replace('\r', ' ').strip();
        return singleLine.length() > 80 ? singleLine.substring(0, 80) + "..." : singleLine;
    }
}
