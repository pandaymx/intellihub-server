package dev.ppmb.intellihub.agent.tool;

import java.util.Map;

/**
 * 高风险工具执行前审批/鉴权拦截器（High-Risk Tool Approval Gate）。
 *
 * <p>对于涉及写库、转账、删除或外部系统变更的高风险工具，在 Agent 真正执行 {@code ToolCallback} 前触发此钩子；若返回拒绝，执行器将把拒绝原因作为
 * {@code ToolResponse} 观察结果反馈给大模型，而不会触发实际副作用。
 */
@FunctionalInterface
public interface ToolApprovalHandler {

    /**
     * 评估是否允许执行指定工具调用。
     *
     * @param toolName 工具名称
     * @param rawJsonArguments 大模型生成的原始 JSON 参数字符串
     * @param toolContext 工具调用上下文元数据（如租户 ID、操作人 ID、审批令牌等）
     * @return 审批决策结果
     */
    ApprovalDecision evaluate(
            String toolName, String rawJsonArguments, Map<String, Object> toolContext);

    record ApprovalDecision(boolean approved, String reason) {

        public static ApprovalDecision allow() {
            return new ApprovalDecision(true, "APPROVED");
        }

        public static ApprovalDecision reject(String reason) {
            return new ApprovalDecision(
                    false,
                    reason == null || reason.isBlank()
                            ? "High-risk tool call rejected by policy"
                            : reason);
        }
    }
}
