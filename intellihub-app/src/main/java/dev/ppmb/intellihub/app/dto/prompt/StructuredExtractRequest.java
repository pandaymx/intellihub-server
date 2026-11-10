package dev.ppmb.intellihub.app.dto.prompt;

import java.util.List;

/**
 * 结构化输出提取联调请求 DTO。
 */
public class StructuredExtractRequest {

    /** 待分析抽取的非结构化原始自然语言文本 */
    private String text;

    /** 可选指定期望抽取的字段或实体列表（如 ["姓名", "手机号", "预约时间", "意向产品"]） */
    private List<String> targetFields;

    /** 可选指定模型渠道或路由组 */
    private String channel;

    public StructuredExtractRequest() {}

    public StructuredExtractRequest(String text) {
        this.text = text;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public List<String> getTargetFields() {
        return targetFields;
    }

    public void setTargetFields(List<String> targetFields) {
        this.targetFields = targetFields;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }
}
