package dev.ppmb.intellihub.agent.tool;

import dev.ppmb.intellihub.core.exception.GlobalErrorCodeConstants;
import dev.ppmb.intellihub.core.exception.IntelliHubException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

/**
 * 受治理的 Agent 工具注册与安全执行中心（Governed Tool Registry）。
 *
 * <p>核心工程治理能力：
 *
 * <ul>
 *   <li><b>统一工具纳管</b>：支持直接注册 Spring AI {@link ToolCallback}，或自动扫描任意带有 {@link Tool @Tool} 注解的
 *       Java Bean 方法；
 *   <li><b>高风险工具审批门禁（Approval Gate）</b>：被标记为高风险的工具在执行前必须通过 {@link ToolApprovalHandler} 校验；
 *   <li><b>超大返回截断保护（Output Truncation Guard）</b>：限制单次工具返回文本最大长度（默认 {@code 4000} 字符），防止未分页查询撑爆大模型上下文窗口；
 *   <li><b>异常转结构化观察（Error-to-Observation）</b>：捕获工具内部运行时异常并转为结构化错误描述返回给大模型，供下一轮 ReAct 自我修正参数。
 * </ul>
 */
public class GovernedToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(GovernedToolRegistry.class);

    public static final int DEFAULT_MAX_TOOL_OUTPUT_CHARS = 4000;

    private final Map<String, ToolCallback> tools = new ConcurrentHashMap<>();
    private final Set<String> highRiskTools = ConcurrentHashMap.newKeySet();
    private final int maxToolOutputChars;
    private volatile ToolApprovalHandler approvalHandler =
            (name, args, ctx) -> ToolApprovalHandler.ApprovalDecision.allow();

    public GovernedToolRegistry() {
        this(DEFAULT_MAX_TOOL_OUTPUT_CHARS);
    }

    public GovernedToolRegistry(int maxToolOutputChars) {
        if (maxToolOutputChars < 128) {
            throw new IllegalArgumentException("maxToolOutputChars 不能小于 128");
        }
        this.maxToolOutputChars = maxToolOutputChars;
    }

    /**
     * 注册单个 {@link ToolCallback}。
     */
    public GovernedToolRegistry register(ToolCallback callback) {
        Objects.requireNonNull(callback, "ToolCallback 不能为空");
        String name = callback.getToolDefinition().name();
        tools.put(name, callback);
        return this;
    }

    /**
     * 批量注册 {@link ToolCallback}。
     */
    public GovernedToolRegistry registerAll(Collection<ToolCallback> callbacks) {
        if (callbacks != null) {
            callbacks.forEach(this::register);
        }
        return this;
    }

    /**
     * 扫描并注册任意包含 {@link Tool @Tool} 注解方法的对象实例。
     */
    public GovernedToolRegistry registerToolObjects(Object... toolObjects) {
        if (toolObjects == null || toolObjects.length == 0) {
            return this;
        }
        ToolCallback[] callbacks =
                MethodToolCallbackProvider.builder()
                        .toolObjects(toolObjects)
                        .build()
                        .getToolCallbacks();
        for (ToolCallback cb : callbacks) {
            register(cb);
        }
        return this;
    }

    /**
     * 将指定工具标记为高风险工具（执行前强制经过 {@link ToolApprovalHandler} 审批）。
     */
    public GovernedToolRegistry markHighRisk(String... toolNames) {
        if (toolNames != null) {
            for (String name : toolNames) {
                if (name != null && !name.isBlank()) {
                    highRiskTools.add(name.strip());
                }
            }
        }
        return this;
    }

    public GovernedToolRegistry setApprovalHandler(ToolApprovalHandler approvalHandler) {
        this.approvalHandler = Objects.requireNonNull(approvalHandler, "approvalHandler 不能为空");
        return this;
    }

    public Optional<ToolCallback> findTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(tools.get(toolName.strip()));
    }

    public List<ToolCallback> getAllToolCallbacks() {
        return List.copyOf(tools.values());
    }

    public List<ToolCallback> resolveToolCallbacks(Collection<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return getAllToolCallbacks();
        }
        return toolNames.stream().map(tools::get).filter(Objects::nonNull).toList();
    }

    public Set<String> getRegisteredToolNames() {
        return Collections.unmodifiableSet(tools.keySet());
    }

    public boolean isHighRisk(String toolName) {
        return toolName != null && highRiskTools.contains(toolName.strip());
    }

    /**
     * 安全执行指定工具调用，返回经审批校验、异常捕获与长度截断后的观察结果。
     */
    public ToolInvocationOutcome executeTool(
            String toolName, String rawJsonArguments, Map<String, Object> contextMap) {
        long start = System.currentTimeMillis();
        String safeArgs =
                (rawJsonArguments == null || rawJsonArguments.isBlank())
                        ? "{}"
                        : rawJsonArguments.strip();
        Map<String, Object> safeContext = contextMap != null ? contextMap : Map.of();

        ToolCallback callback = tools.get(toolName);
        if (callback == null) {
            long cost = System.currentTimeMillis() - start;
            String msg =
                    "{\"status\":\"ERROR\",\"tool\":\""
                            + escapeJson(toolName)
                            + "\",\"error\":\"Tool not found in GovernedToolRegistry\"}";
            return new ToolInvocationOutcome(msg, false, false, cost);
        }

        // 1. 高风险工具执行前审批门禁
        if (isHighRisk(toolName)) {
            ToolApprovalHandler.ApprovalDecision decision =
                    approvalHandler.evaluate(toolName, safeArgs, safeContext);
            if (decision == null || !decision.approved()) {
                long cost = System.currentTimeMillis() - start;
                String reason =
                        decision != null ? decision.reason() : "Rejected by approval handler";
                log.warn("[GovernedToolRegistry] 高风险工具 [{}] 调用被审批门禁拦截: {}", toolName, reason);
                String msg =
                        "{\"status\":\"APPROVAL_REJECTED\",\"tool\":\""
                                + escapeJson(toolName)
                                + "\",\"reason\":\""
                                + escapeJson(reason)
                                + "\"}";
                return new ToolInvocationOutcome(msg, false, true, cost);
            }
        }

        // 2. 实际调用工具并捕获异常 + 截断超长输出
        try {
            String rawResult = callback.call(safeArgs, new ToolContext(safeContext));
            String truncated = truncateOutputIfNeeded(rawResult);
            long cost = System.currentTimeMillis() - start;
            return new ToolInvocationOutcome(truncated, true, false, cost);
        } catch (RuntimeException ex) {
            if (ex instanceof IntelliHubException ihe
                    && Objects.equals(
                            ihe.getCode(), GlobalErrorCodeConstants.AI_GUARD_REJECTED.getCode())) {
                throw ihe;
            }
            long cost = System.currentTimeMillis() - start;
            log.warn(
                    "[GovernedToolRegistry] 工具 [{}] 执行异常 (耗时 {}ms): {}",
                    toolName,
                    cost,
                    ex.getMessage());
            String errorObs =
                    "{\"status\":\"ERROR\",\"tool\":\""
                            + escapeJson(toolName)
                            + "\",\"error\":\""
                            + escapeJson(
                                    ex.getMessage() != null
                                            ? ex.getMessage()
                                            : ex.getClass().getSimpleName())
                            + "\"}";
            return new ToolInvocationOutcome(errorObs, false, false, cost);
        }
    }

    private String truncateOutputIfNeeded(String output) {
        if (output == null) {
            return "";
        }
        if (output.length() <= maxToolOutputChars) {
            return output;
        }
        return output.substring(0, maxToolOutputChars)
                + "...[TRUNCATED totalChars="
                + output.length()
                + "]";
    }

    private static String escapeJson(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    public int getMaxToolOutputChars() {
        return maxToolOutputChars;
    }

    public record ToolInvocationOutcome(
            String observation, boolean success, boolean approvalRejected, long durationMs) {}
}
