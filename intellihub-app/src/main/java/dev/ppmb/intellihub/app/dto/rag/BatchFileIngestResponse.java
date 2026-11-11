package dev.ppmb.intellihub.app.dto.rag;

import java.util.List;

/**
 * 多格式文件批量上传、解析与入库响应 DTO。
 */
public class BatchFileIngestResponse {

    private int fileCount;
    private int totalChunkCount;
    private List<String> allChunkIds;
    private List<FileParseResultDto> files;

    public BatchFileIngestResponse() {}

    public BatchFileIngestResponse(
            int fileCount,
            int totalChunkCount,
            List<String> allChunkIds,
            List<FileParseResultDto> files) {
        this.fileCount = fileCount;
        this.totalChunkCount = totalChunkCount;
        this.allChunkIds = allChunkIds;
        this.files = files;
    }

    public int getFileCount() {
        return fileCount;
    }

    public void setFileCount(int fileCount) {
        this.fileCount = fileCount;
    }

    public int getTotalChunkCount() {
        return totalChunkCount;
    }

    public void setTotalChunkCount(int totalChunkCount) {
        this.totalChunkCount = totalChunkCount;
    }

    public List<String> getAllChunkIds() {
        return allChunkIds;
    }

    public void setAllChunkIds(List<String> allChunkIds) {
        this.allChunkIds = allChunkIds;
    }

    public List<FileParseResultDto> getFiles() {
        return files;
    }

    public void setFiles(List<FileParseResultDto> files) {
        this.files = files;
    }
}
