package dev.ppmb.intellihub.app.dto.rag;

import java.util.List;

/**
 * 文档摄取入库结果响应 DTO。
 */
public class IngestResultResponse {

    /** 成功切分并入库的切片总数 */
    private int chunkCount;

    /** 生成的各文档切片 ID 列表 */
    private List<String> chunkIds;

    public IngestResultResponse() {}

    public IngestResultResponse(int chunkCount, List<String> chunkIds) {
        this.chunkCount = chunkCount;
        this.chunkIds = chunkIds;
    }

    public int getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(int chunkCount) {
        this.chunkCount = chunkCount;
    }

    public List<String> getChunkIds() {
        return chunkIds;
    }

    public void setChunkIds(List<String> chunkIds) {
        this.chunkIds = chunkIds;
    }
}
