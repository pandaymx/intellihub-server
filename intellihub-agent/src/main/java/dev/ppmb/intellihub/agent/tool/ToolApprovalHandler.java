package dev.ppmb.intellihub.agent.tool;

import java.util.Map;

/**
 * 高风险工具执行前审批/鉴权拦截器（High-Risk Tool Approval Gate）。
 *
 * <p>支持三种决策模式：
 *
 * <ul>
 *   <li>{@link ApprovalStatus#APPROVED}：同步规则鉴权通过（如 RBAC/ABAC 校验通过），立即执行工具；
 *   <li>{@link ApprovalStatus#REJECTED}：同步拦截拒绝，将拒绝原因作为 {@code ToolResponse} 观察反馈给大模型；
 *   <li>{@link ApprovalStatus#AWAITING_HUMAN_APPROVAL}：异步人工审批挂起（Human-in-the-Loop Checkpointing）——
 *       <b>立即释放当前工作线程</b>，保存 {@code AgentCheckpoint} 快照并返回 {@code AWAITING_APPROVAL} 状态，待人工在 Web
 *       端点击批准或拒绝后通过 {@code ReActAgentExecutor.resume(...)} 恢复执行。
 * </ul>
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

    enum ApprovalStatus {
        APPROVED,
        REJECTED,
        AWAITING_HUMAN_APPROVAL
    }

    record ApprovalDecision(ApprovalStatus status, String reason, String approvalTicketId) {

        public ApprovalDecision(boolean approved, String reason) {
            this(approved ? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED, reason, null);
        }

        public boolean approved() {
            return status == ApprovalStatus.APPROVED;
        }

        public boolean isAwaitingHumanApproval() {
            return status == ApprovalStatus.AWAITING_HUMAN_APPROVAL;
        }

        public static ApprovalDecision allow() {
            return new ApprovalDecision(ApprovalStatus.APPROVED, "APPROVED", null);
        }

        public static ApprovalDecision reject(String reason) {
            return new ApprovalDecision(
                    ApprovalStatus.REJECTED,
                    reason == null || reason.isBlank()
                            ? "High-risk tool call rejected by policy"
                            : reason,
                    null);
        }

        public static ApprovalDecision awaitHumanApproval(String approvalTicketId, String reason) {
            return new ApprovalDecision(
                    ApprovalStatus.AWAITING_HUMAN_APPROVAL,
                    reason == null || reason.isBlank()
                            ? "Awaiting human-in-the-loop approval"
                            : reason,
                    approvalTicketId);
        }
    }
}
