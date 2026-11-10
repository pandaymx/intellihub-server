package dev.ppmb.intellihub.llm.router;

import dev.ppmb.intellihub.core.exception.AiAuthenticationException;
import dev.ppmb.intellihub.core.exception.AiRateLimitException;
import dev.ppmb.intellihub.core.exception.GlobalErrorCodeConstants;
import dev.ppmb.intellihub.core.exception.IntelliHubException;
import dev.ppmb.intellihub.core.exception.IntelliHubExceptionUtil;
import dev.ppmb.intellihub.llm.config.ModelCapability;
import dev.ppmb.intellihub.llm.config.ModelProviderType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

/**
 * 多模型高可用路由与故障转移 {@link ChatModel} 实现（支持 {@link RoutingStrategy#FAILOVER}、{@link
 * RoutingStrategy#ROUND_ROBIN}、{@link RoutingStrategy#WEIGHTED} 及节点级熔断冷却）。
 *
 * <ul>
 *   <li><b>首包状态守卫（防流式中途断流拼接错乱）</b>：在 {@link #stream(Prompt)} 中追踪 {@code hasEmitted}，仅当首包尚未下发前报错才切换备用渠道；一旦已向客户端吐出有效文本
 *       Chunk，中途断流直接抛出异常；
 *   <li><b>401/403 与 429 差异化隔离</b>：429/5xx/超时进入短期冷却（{@code cooldownDuration}），而 401/403 鉴权失败直接标记节点为
 *       {@code DEAD} 长期摘除并打印 {@code ERROR} 告警；
 *   <li><b>CAS 单请求半开探活（防并发惊群）</b>：冷却期满后仅放行 1 个 CAS 抢锁成功的探测请求试水，其余并发流量继续走健康备选节点；
 *   <li><b>跨厂商 {@link ChatOptions} 清洗合并</b>：跨渠道转发时自动以目标渠道的默认 {@code model} 名称覆盖 Prompt 中的旧模型名，并剥离目标厂商不支持的采样或专有扩展属性；
 *   <li><b>全链路路由可观测性</b>：在 {@link ChatResponse#getMetadata()} 中自动注入 {@code intellihub.route.actualChannel}、
 *       {@code intellihub.route.actualProvider} 与 {@code intellihub.route.fallbackCount}。
 * </ul>
 */
public class RoutingChatModel implements ChatModel {

    public static final int DEFAULT_FAILURE_THRESHOLD = 2;
    public static final Duration DEFAULT_COOLDOWN = Duration.ofSeconds(30);

    public static final String METADATA_ROUTE_NAME = "intellihub.route.name";
    public static final String METADATA_ACTUAL_CHANNEL = "intellihub.route.actualChannel";
    public static final String METADATA_ACTUAL_PROVIDER = "intellihub.route.actualProvider";
    public static final String METADATA_FALLBACK_COUNT = "intellihub.route.fallbackCount";

    private static final Logger log = LoggerFactory.getLogger(RoutingChatModel.class);

    private final String routeName;
    private final RoutingStrategy strategy;
    private final List<ModelRouteTarget> targets;
    private final int failureThreshold;
    private final long cooldownMillis;
    private final boolean failoverOnAuthError;
    private final LongSupplier clock;
    private final Map<String, TargetHealthState> healthStates = new ConcurrentHashMap<>();
    private final AtomicInteger roundRobinCursor = new AtomicInteger(0);

    private RoutingChatModel(Builder builder) {
        this.routeName = Objects.requireNonNullElse(builder.routeName, "default-route");
        this.strategy = Objects.requireNonNullElse(builder.strategy, RoutingStrategy.FAILOVER);
        if (builder.targets == null || builder.targets.isEmpty()) {
            throw IntelliHubExceptionUtil.invalidParamException(
                    "构建 RoutingChatModel [{}] 失败：至少需要配置 1 个候选目标节点", this.routeName);
        }
        this.targets = List.copyOf(builder.targets);
        this.failureThreshold = Math.max(1, builder.failureThreshold);
        this.cooldownMillis =
                builder.cooldownDuration != null
                        ? Math.max(0L, builder.cooldownDuration.toMillis())
                        : DEFAULT_COOLDOWN.toMillis();
        this.failoverOnAuthError = builder.failoverOnAuthError;
        this.clock = Objects.requireNonNullElse(builder.clock, System::currentTimeMillis);
        for (ModelRouteTarget target : this.targets) {
            this.healthStates.put(target.name(), new TargetHealthState());
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Builder builder(String routeName) {
        return new Builder().routeName(routeName);
    }

    public String getRouteName() {
        return routeName;
    }

    public RoutingStrategy getStrategy() {
        return strategy;
    }

    public List<ModelRouteTarget> getTargets() {
        return targets;
    }

    /**
     * 判断指定目标节点当前是否健康（未处于 {@code DEAD}、未在冷却期，且未被其他并发请求占用半开探活锁）。
     */
    public boolean isTargetHealthy(String targetName) {
        TargetHealthState state = healthStates.get(targetName);
        return state == null || state.isHealthySnapshot(clock.getAsLong());
    }

    /**
     * 判断指定目标节点是否因 401/403 鉴权失败被标记为 {@code DEAD}。
     */
    public boolean isTargetDead(String targetName) {
        TargetHealthState state = healthStates.get(targetName);
        return state != null && state.dead.get();
    }

    /**
     * 手动复活指定目标节点（清除 {@code DEAD} 与冷却状态）。
     */
    public void reviveTarget(String targetName) {
        TargetHealthState state = healthStates.get(targetName);
        if (state != null) {
            state.revive();
        }
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        List<ModelRouteTarget> executionOrder = selectExecutionOrder();
        IntelliHubException lastException = null;

        for (int i = 0; i < executionOrder.size(); i++) {
            ModelRouteTarget target = executionOrder.get(i);
            try {
                ChatModel delegate = target.resolveChatModel();
                Prompt effectivePrompt = sanitizePromptForTarget(prompt, delegate, target, i > 0);
                ChatResponse response = delegate.call(effectivePrompt);
                recordSuccess(target);
                return attachRouteMetadata(response, target, i);
            } catch (Throwable ex) {
                IntelliHubException wrapped = IntelliHubExceptionUtil.wrap(target.provider(), ex);
                recordFailure(target, wrapped);
                lastException = wrapped;

                boolean hasNext = i + 1 < executionOrder.size();
                if (!shouldFailover(wrapped) || !hasNext) {
                    throw wrapped;
                }
                ModelRouteTarget nextTarget = executionOrder.get(i + 1);
                log.warn(
                        "[IntelliHub-Router] Route [{}] target [{} ({})] call failed ({}), failing over to [{} ({})]",
                        routeName,
                        target.name(),
                        target.provider(),
                        wrapped.getMessage(),
                        nextTarget.name(),
                        nextTarget.provider());
            }
        }
        throw lastException != null
                ? lastException
                : IntelliHubExceptionUtil.exception(GlobalErrorCodeConstants.SERVICE_UNAVAILABLE);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        List<ModelRouteTarget> executionOrder = selectExecutionOrder();
        return streamWithFailover(prompt, executionOrder, 0);
    }

    @Override
    public ChatOptions getOptions() {
        for (ModelRouteTarget target : targets) {
            if (!isTargetDead(target.name())) {
                return target.resolveChatModel().getOptions();
            }
        }
        return targets.get(0).resolveChatModel().getOptions();
    }

    private Flux<ChatResponse> streamWithFailover(
            Prompt prompt, List<ModelRouteTarget> executionOrder, int index) {
        return Flux.defer(() -> {
            ModelRouteTarget target = executionOrder.get(index);
            ChatModel delegate;
            Prompt effectivePrompt;
            try {
                delegate = target.resolveChatModel();
                effectivePrompt = sanitizePromptForTarget(prompt, delegate, target, index > 0);
            } catch (Throwable ex) {
                return handleStreamTargetError(prompt, executionOrder, index, target, false, ex);
            }
            AtomicBoolean hasEmitted = new AtomicBoolean(false);
            return delegate.stream(effectivePrompt)
                    .doOnNext(chunk -> {
                        if (KeyPoolChatModel.hasEmittedContent(chunk)) {
                            hasEmitted.set(true);
                        }
                    })
                    .map(chunk -> attachRouteMetadata(chunk, target, index))
                    .doOnComplete(() -> recordSuccess(target))
                    .onErrorResume(ex ->
                            handleStreamTargetError(
                                    prompt, executionOrder, index, target, hasEmitted.get(), ex));
        });
    }

    private Flux<ChatResponse> handleStreamTargetError(
            Prompt prompt,
            List<ModelRouteTarget> executionOrder,
            int index,
            ModelRouteTarget target,
            boolean hasEmitted,
            Throwable ex) {
        IntelliHubException wrapped = IntelliHubExceptionUtil.wrap(target.provider(), ex);
        recordFailure(target, wrapped);
        if (hasEmitted) {
            log.warn(
                    "[IntelliHub-Router] Mid-stream failure after token emission on route [{}] target [{} ({})], aborting failover to prevent garbled output: {}",
                    routeName,
                    target.name(),
                    target.provider(),
                    wrapped.getMessage());
            return Flux.error(wrapped);
        }
        boolean hasNext = index + 1 < executionOrder.size();
        if (shouldFailover(wrapped) && hasNext) {
            ModelRouteTarget nextTarget = executionOrder.get(index + 1);
            log.warn(
                    "[IntelliHub-Router] Pre-first-token stream failure on route [{}] target [{} ({})] ({}), failing over to [{} ({})]",
                    routeName,
                    target.name(),
                    target.provider(),
                    wrapped.getMessage(),
                    nextTarget.name(),
                    nextTarget.provider());
            return streamWithFailover(prompt, executionOrder, index + 1);
        }
        return Flux.error(wrapped);
    }

    /**
     * 跨渠道 Prompt 与 {@link ChatOptions} 清洗合并：
     *
     * <ul>
     *   <li>若 {@code prompt.getOptions()} 为空，直接原样转发；
     *   <li>当发生跨渠道降级（或传入了其他厂商专属的 {@code ChatOptions} 实现类 / 不匹配的 {@code model} 名称）时，提取标准可移植字段（{@code
     *       temperature}、{@code topP}、{@code maxTokens}、{@code stopSequences}），剔除目标渠道不支持的 {@code
     *       topK} 或惩罚参数，并强制使用目标渠道自身的默认 {@code model} 名称，避免将云端模型名或专有参数发往备选模型导致报错。
     * </ul>
     */
    static Prompt sanitizePromptForTarget(
            Prompt prompt, ChatModel targetModel, ModelRouteTarget target, boolean isFallback) {
        if (prompt == null || prompt.getOptions() == null) {
            return prompt;
        }
        ChatOptions incoming = prompt.getOptions();
        ChatOptions targetDefault = targetModel != null ? targetModel.getOptions() : null;

        boolean crossVendorOptions =
                targetDefault != null
                        && !targetDefault.getClass().isAssignableFrom(incoming.getClass());
        boolean modelMismatch =
                targetDefault != null
                        && targetDefault.getModel() != null
                        && incoming.getModel() != null
                        && !targetDefault.getModel().equals(incoming.getModel());

        if (!isFallback && !crossVendorOptions && !modelMismatch) {
            return prompt;
        }

        Set<ModelCapability> targetCaps =
                ModelProviderType.fromCode(target.provider())
                        .map(ModelProviderType::getCapabilities)
                        .orElse(Set.of());

        ChatOptions.Builder<?> sanitized;
        if (targetDefault != null) {
            sanitized = targetDefault.mutate();
        } else if (incoming instanceof ToolCallingChatOptions) {
            sanitized = ToolCallingChatOptions.builder();
        } else {
            sanitized = ChatOptions.builder();
        }

        if (targetDefault != null
                && targetDefault.getModel() != null
                && !targetDefault.getModel().isBlank()) {
            sanitized.model(targetDefault.getModel());
        } else if (incoming.getModel() != null && !isFallback) {
            sanitized.model(incoming.getModel());
        }

        if (incoming instanceof ToolCallingChatOptions incomingToolOpts
                && sanitized instanceof ToolCallingChatOptions.Builder<?> toolBuilder
                && targetCaps.contains(ModelCapability.TOOL_CALLING)) {
            if (incomingToolOpts.getToolCallbacks() != null
                    && !incomingToolOpts.getToolCallbacks().isEmpty()) {
                toolBuilder.toolCallbacks(incomingToolOpts.getToolCallbacks());
            }
            if (incomingToolOpts.getToolContext() != null
                    && !incomingToolOpts.getToolContext().isEmpty()) {
                toolBuilder.toolContext(incomingToolOpts.getToolContext());
            }
        }

        if (incoming.getTemperature() != null) {
            sanitized.temperature(incoming.getTemperature());
        }
        if (incoming.getTopP() != null) {
            sanitized.topP(incoming.getTopP());
        }
        if (incoming.getMaxTokens() != null) {
            sanitized.maxTokens(incoming.getMaxTokens());
        }
        if (incoming.getStopSequences() != null && !incoming.getStopSequences().isEmpty()) {
            sanitized.stopSequences(incoming.getStopSequences());
        }
        if (targetCaps.contains(ModelCapability.TOP_K_SAMPLING)) {
            if (incoming.getTopK() != null) {
                sanitized.topK(incoming.getTopK());
            }
        } else {
            sanitized.topK(null);
        }
        if (targetCaps.contains(ModelCapability.PENALTY_PARAMETERS)) {
            if (incoming.getFrequencyPenalty() != null) {
                sanitized.frequencyPenalty(incoming.getFrequencyPenalty());
            }
            if (incoming.getPresencePenalty() != null) {
                sanitized.presencePenalty(incoming.getPresencePenalty());
            }
        } else {
            sanitized.frequencyPenalty(null);
            sanitized.presencePenalty(null);
        }
        return new Prompt(prompt.getInstructions(), sanitized.build());
    }

    private ChatResponse attachRouteMetadata(
            ChatResponse response, ModelRouteTarget target, int fallbackCount) {
        if (response == null) {
            return null;
        }
        return ChatResponse.builder()
                .from(response)
                .metadata(METADATA_ROUTE_NAME, routeName)
                .metadata(METADATA_ACTUAL_CHANNEL, target.name())
                .metadata(METADATA_ACTUAL_PROVIDER, target.provider())
                .metadata(METADATA_FALLBACK_COUNT, fallbackCount)
                .build();
    }

    private boolean shouldFailover(IntelliHubException ex) {
        if (ex.isRetryable()) {
            return true;
        }
        if (Objects.equals(
                ex.getCode(), GlobalErrorCodeConstants.AI_KEY_POOL_EXHAUSTED.getCode())) {
            return true;
        }
        return failoverOnAuthError && ex instanceof AiAuthenticationException;
    }

    private List<ModelRouteTarget> selectExecutionOrder() {
        long now = clock.getAsLong();
        List<ModelRouteTarget> healthy = new ArrayList<>(targets.size());
        List<ModelRouteTarget> cooling = new ArrayList<>();

        for (ModelRouteTarget target : targets) {
            TargetHealthState state = healthStates.get(target.name());
            if (state == null) {
                healthy.add(target);
                continue;
            }
            if (state.dead.get()) {
                continue;
            }
            if (state.tryAcquireForExecution(now)) {
                healthy.add(target);
            } else {
                cooling.add(target);
            }
        }

        List<ModelRouteTarget> ordered = orderByStrategy(healthy);
        if (ordered.isEmpty() && !cooling.isEmpty()) {
            // 仅当所有非 DEAD 节点均处于冷却期时，选取最快到期的一个节点兜底
            cooling.sort(
                    Comparator.comparingLong(t -> {
                        TargetHealthState s = healthStates.get(t.name());
                        return s == null ? 0L : s.cooldownUntilMillis.get();
                    }));
            ordered.addAll(cooling);
        }
        if (ordered.isEmpty()) {
            // 若全部节点均被标记为 DEAD，保留原始列表以便抛出明确的鉴权错误
            return new ArrayList<>(targets);
        }
        return ordered;
    }

    private List<ModelRouteTarget> orderByStrategy(List<ModelRouteTarget> candidates) {
        if (candidates.size() <= 1) {
            return new ArrayList<>(candidates);
        }
        return switch (strategy) {
            case FAILOVER -> new ArrayList<>(candidates);
            case ROUND_ROBIN -> {
                int size = candidates.size();
                int start = Math.floorMod(roundRobinCursor.getAndIncrement(), size);
                List<ModelRouteTarget> rotated = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    rotated.add(candidates.get((start + i) % size));
                }
                yield rotated;
            }
            case WEIGHTED -> {
                List<ModelRouteTarget> weightedOrder = new ArrayList<>(candidates.size());
                int totalWeight = candidates.stream().mapToInt(ModelRouteTarget::weight).sum();
                int pick = ThreadLocalRandom.current().nextInt(Math.max(1, totalWeight));
                int cumulative = 0;
                int primaryIndex = 0;
                for (int i = 0; i < candidates.size(); i++) {
                    cumulative += candidates.get(i).weight();
                    if (pick < cumulative) {
                        primaryIndex = i;
                        break;
                    }
                }
                weightedOrder.add(candidates.get(primaryIndex));
                for (int i = 0; i < candidates.size(); i++) {
                    if (i != primaryIndex) {
                        weightedOrder.add(candidates.get(i));
                    }
                }
                yield weightedOrder;
            }
        };
    }

    private void recordSuccess(ModelRouteTarget target) {
        TargetHealthState state = healthStates.get(target.name());
        if (state != null) {
            state.onSuccess();
        }
    }

    private void recordFailure(ModelRouteTarget target, IntelliHubException ex) {
        TargetHealthState state = healthStates.get(target.name());
        if (state == null) {
            return;
        }
        if (ex instanceof AiAuthenticationException) {
            state.markDead();
            log.error(
                    "[IntelliHub-Router-DEAD] Target [{} ({})] in route [{}] marked DEAD due to 401/403 authentication failure and isolated from routing: {}",
                    target.name(),
                    target.provider(),
                    routeName,
                    ex.getMessage());
            return;
        }

        int failures = state.consecutiveFailures.incrementAndGet();
        boolean immediateCircuitBreak =
                ex instanceof AiRateLimitException
                        || Objects.equals(
                                ex.getCode(),
                                GlobalErrorCodeConstants.AI_KEY_POOL_EXHAUSTED.getCode())
                        || state.probing.get();
        if (immediateCircuitBreak || (shouldFailover(ex) && failures >= failureThreshold)) {
            long until = clock.getAsLong() + cooldownMillis;
            state.enterCooldown(until);
            log.warn(
                    "[IntelliHub-Router] Target [{} ({})] in route [{}] entered circuit-breaker cooldown for {}ms (failures={})",
                    target.name(),
                    target.provider(),
                    routeName,
                    cooldownMillis,
                    failures);
        } else {
            state.probing.set(false);
        }
    }

    private static final class TargetHealthState {
        private final AtomicBoolean dead = new AtomicBoolean(false);
        private final AtomicBoolean probing = new AtomicBoolean(false);
        private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        private final AtomicLong cooldownUntilMillis = new AtomicLong(0L);

        boolean tryAcquireForExecution(long nowMillis) {
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
            // 冷却期已满，进入 HALF_OPEN：仅允许 1 个并发请求通过 CAS 抢占单次探活权，防止并发惊群
            return probing.compareAndSet(false, true);
        }

        boolean isHealthySnapshot(long nowMillis) {
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

    public static final class Builder {
        private String routeName = "default-route";
        private RoutingStrategy strategy = RoutingStrategy.FAILOVER;
        private final List<ModelRouteTarget> targets = new ArrayList<>();
        private int failureThreshold = DEFAULT_FAILURE_THRESHOLD;
        private Duration cooldownDuration = DEFAULT_COOLDOWN;
        private boolean failoverOnAuthError = true;
        private LongSupplier clock = System::currentTimeMillis;

        public Builder routeName(String routeName) {
            this.routeName = routeName;
            return this;
        }

        public Builder strategy(RoutingStrategy strategy) {
            this.strategy = strategy;
            return this;
        }

        public Builder target(ModelRouteTarget target) {
            if (target != null) {
                this.targets.add(target);
            }
            return this;
        }

        public Builder targets(Collection<ModelRouteTarget> targets) {
            if (targets != null) {
                this.targets.addAll(targets);
            }
            return this;
        }

        public Builder failureThreshold(int failureThreshold) {
            this.failureThreshold = failureThreshold;
            return this;
        }

        public Builder cooldownDuration(Duration cooldownDuration) {
            this.cooldownDuration = cooldownDuration;
            return this;
        }

        public Builder failoverOnAuthError(boolean failoverOnAuthError) {
            this.failoverOnAuthError = failoverOnAuthError;
            return this;
        }

        public Builder clock(LongSupplier clock) {
            this.clock = clock;
            return this;
        }

        public RoutingChatModel build() {
            return new RoutingChatModel(this);
        }
    }
}
