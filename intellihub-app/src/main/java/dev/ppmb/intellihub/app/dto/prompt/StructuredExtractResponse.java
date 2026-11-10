package dev.ppmb.intellihub.app.dto.prompt;

import java.util.Map;

/**
 * 结构化输出提取联调响应 DTO。
 */
public class StructuredExtractResponse {

    /** 结构化解析提取出的键值映射对象 */
    private Map<String, Object> data;

    /** 提取所使用的模型渠道 */
    private String channel;

    /** 处理耗时（毫秒） */
    private Long latencyMs;

    public StructuredExtractResponse() {}

    public StructuredExtractResponse(Map<String, Object> data, String channel, Long latencyMs) {
        this.data = data;
        this.channel = channel;
        this.latencyMs = latencyMs;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public void setData(Map<String, Object> data) {
        this.data = data;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Long latencyMs) {
        this.latencyMs = latencyMs;
    }
}
