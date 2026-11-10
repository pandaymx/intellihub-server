package dev.ppmb.intellihub.app.dto.agent;

import java.util.Map;

/**
 * 恢复处于挂起审批状态的 Agent 任务请求 DTO。
 */
public class AgentResumeApiRequest {

    /** 挂起任务的快照 ID（必填） */
    private String checkpointId;

    /** 是否批准执行该敏感工具操作（true 批准 / false 拒绝） */
    private Boolean approved;

    /** 可选审批人备注信息或拒绝原因说明 */
    private String reviewerNote;

    /** 可选指定模型渠道或高可用路由组 */
    private String channel;

    /** 可选附加工具执行上下文 */
    private Map<String, Object> toolContext;

    public AgentResumeApiRequest() {}

    public String getCheckpointId() {
        return checkpointId;
    }

    public void setCheckpointId(String checkpointId) {
        this.checkpointId = checkpointId;
    }

    public Boolean getApproved() {
        return approved;
    }

    public void setApproved(Boolean approved) {
        this.approved = approved;
    }

    public String getReviewerNote() {
        return reviewerNote;
    }

    public void setReviewerNote(String reviewerNote) {
        this.reviewerNote = reviewerNote;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public Map<String, Object> getToolContext() {
        return toolContext;
    }

    public void setToolContext(Map<String, Object> toolContext) {
        this.toolContext = toolContext;
    }
}
