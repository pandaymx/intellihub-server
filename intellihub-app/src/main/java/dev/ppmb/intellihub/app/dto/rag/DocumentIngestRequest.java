package dev.ppmb.intellihub.app.dto.rag;

import java.util.Map;

/**
 * 知识库文档摄取入库请求 DTO。
 */
public class DocumentIngestRequest {

    /** 待摄取的原始文档内容（支持 Markdown 或纯文本） */
    private String content;

    /** 可选文档标题（将自动注入切片元数据） */
    private String title;

    /** 可选多租户隔离标识符 */
    private String tenantId;

    /** 可选知识库隔离标识符 */
    private String kbId;

    /** 可选额外附加自定义元数据键值对 */
    private Map<String, Object> metadata;

    public DocumentIngestRequest() {}

    public DocumentIngestRequest(String content) {
        this.content = content;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getKbId() {
        return kbId;
    }

    public void setKbId(String kbId) {
        this.kbId = kbId;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
}
