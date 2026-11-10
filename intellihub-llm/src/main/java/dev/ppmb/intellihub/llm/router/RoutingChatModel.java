package dev.ppmb.intellihub.llm.router;

import dev.ppmb.intellihub.core.exception.AiAuthenticationException;
import dev.ppmb.intellihub.core.exception.AiRateLimitException;
import dev.ppmb.intellihub.core.exception.GlobalErrorCodeConstants;
import dev.ppmb.intellihub.core.exception.IntelliHubException;
import dev.ppmb.intellihub.core.exception.IntelliHubExceptionUtil;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * 多模型高可用路由与故障转移 {@link ChatModel} 实现（支持 {@link RoutingStrategy#FAILOVER}、{@link
 * RoutingStrategy#ROUND_ROBIN}、{@link RoutingStrategy#WEIGHTED} 及节点级熔断冷却）。
 *
 * <ul>
 *   <li>完全兼容 Spring AI {@link ChatModel} 接口，可直接注入 {@link
 *       org.springframework.ai.chat.client.ChatClient}；
 *   <li>同时支持同步调用 {@link #call(Prompt)} 与响应式流 {@link #stream(Prompt)} 的自动跨渠道降级；
 *   <li>当某个候选渠道触发 429 限流、Key 池耗尽或连续可重试错误达到 {@code failureThreshold} 时，自动进入 {@code
 *       cooldownDuration} 熔断冷却期，期间流量直接路由至健康备选节点；冷却期结束后自动半开恢复。
 * </ul>
 */
public class RoutingChatModel implements ChatModel {

    public static final int DEFAULT_FAILURE_THRESHOLD = 2;
    public static final Duration DEFAULT_COOLDOWN = Duration.ofSeconds(30);

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

    public boolean isTargetHealthy(String targetName) {
        TargetHealthState state = healthStates.get(targetName);
        return state == null || state.isHealthy(clock.getAsLong());
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        List<ModelRouteTarget> executionOrder = selectExecutionOrder();
        IntelliHubException lastException = null;

        for (int i = 0; i < executionOrder.size(); i++) {
            ModelRouteTarget target = executionOrder.get(i);
            try {
                ChatModel delegate = target.resolveChatModel();
                ChatResponse response = delegate.call(prompt);
                recordSuccess(target);
                return response;
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
        List<ModelRouteTarget> order = selectExecutionOrder();
        return order.get(0).resolveChatModel().getOptions();
    }

    private Flux<ChatResponse> streamWithFailover(
            Prompt prompt, List<ModelRouteTarget> executionOrder, int index) {
        return Flux.defer(() -> {
            ModelRouteTarget target = executionOrder.get(index);
            ChatModel delegate;
            try {
                delegate = target.resolveChatModel();
            } catch (Throwable ex) {
                return handleStreamTargetError(prompt, executionOrder, index, target, ex);
            }
            return delegate.stream(prompt)
                    .doOnComplete(() -> recordSuccess(target))
                    .onErrorResume(ex ->
                            handleStreamTargetError(prompt, executionOrder, index, target, ex));
        });
    }

    private Flux<ChatResponse> handleStreamTargetError(
            Prompt prompt,
            List<ModelRouteTarget> executionOrder,
            int index,
            ModelRouteTarget target,
            Throwable ex) {
        IntelliHubException wrapped = IntelliHubExceptionUtil.wrap(target.provider(), ex);
        recordFailure(target, wrapped);
        boolean hasNext = index + 1 < executionOrder.size();
        if (shouldFailover(wrapped) && hasNext) {
            ModelRouteTarget nextTarget = executionOrder.get(index + 1);
            log.warn(
                    "[IntelliHub-Router] Route [{}] target [{} ({})] stream failed ({}), failing over to [{} ({})]",
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
            if (state == null || state.isHealthy(now)) {
                healthy.add(target);
            } else {
                cooling.add(target);
            }
        }

        List<ModelRouteTarget> orderedHealthy = orderByStrategy(healthy);
        if (!cooling.isEmpty()) {
            // 将处于冷却期的节点按冷却到期时间升序排在末尾，作为全部健康节点均不可用时的半开兜底
            cooling.sort(
                    Comparator.comparingLong(t -> {
                        TargetHealthState s = healthStates.get(t.name());
                        return s == null ? 0L : s.cooldownUntilMillis.get();
                    }));
            orderedHealthy.addAll(cooling);
        }
        return orderedHealthy;
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
            state.reset();
        }
    }

    private void recordFailure(ModelRouteTarget target, IntelliHubException ex) {
        TargetHealthState state = healthStates.get(target.name());
        if (state == null) {
            return;
        }
        int failures = state.consecutiveFailures.incrementAndGet();
        boolean immediateCircuitBreak =
                ex instanceof AiRateLimitException
                        || Objects.equals(
                                ex.getCode(),
                                GlobalErrorCodeConstants.AI_KEY_POOL_EXHAUSTED.getCode());
        if (immediateCircuitBreak || (shouldFailover(ex) && failures >= failureThreshold)) {
            long until = clock.getAsLong() + cooldownMillis;
            state.cooldownUntilMillis.set(until);
            log.warn(
                    "[IntelliHub-Router] Target [{} ({})] in route [{}] entered circuit-breaker cooldown for {}ms (failures={})",
                    target.name(),
                    target.provider(),
                    routeName,
                    cooldownMillis,
                    failures);
        }
    }

    private static final class TargetHealthState {
        private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        private final AtomicLong cooldownUntilMillis = new AtomicLong(0L);

        boolean isHealthy(long nowMillis) {
            long until = cooldownUntilMillis.get();
            return until <= 0L || nowMillis >= until;
        }

        void reset() {
            consecutiveFailures.set(0);
            cooldownUntilMillis.set(0L);
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
