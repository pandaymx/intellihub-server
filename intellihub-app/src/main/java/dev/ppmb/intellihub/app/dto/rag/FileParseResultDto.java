package dev.ppmb.intellihub.app.dto.rag;

import dev.ppmb.intellihub.rag.parser.ParsedDocument;
import java.util.List;
import java.util.Map;

/**
 * 单个上传文件的解析与（可选）切分入库结果 DTO。
 */
public class FileParseResultDto {

    private String fileName;
    private String fileFormat;
    private String title;
    private String markdownContent;
    private int characterCount;
    private int chunkCount;
    private List<String> chunkIds;
    private Map<String, Object> metadata;

    public FileParseResultDto() {}

    public static FileParseResultDto fromParsed(
            ParsedDocument parsed, int chunkCount, List<String> chunkIds) {
        FileParseResultDto dto = new FileParseResultDto();
        dto.setFileName(parsed.fileName());
        dto.setFileFormat(parsed.fileFormat());
        dto.setTitle(parsed.title());
        dto.setMarkdownContent(parsed.markdownContent());
        dto.setCharacterCount(parsed.characterCount());
        dto.setChunkCount(chunkCount);
        dto.setChunkIds(chunkIds != null ? chunkIds : List.of());
        dto.setMetadata(parsed.metadata());
        return dto;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFileFormat() {
        return fileFormat;
    }

    public void setFileFormat(String fileFormat) {
        this.fileFormat = fileFormat;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getMarkdownContent() {
        return markdownContent;
    }

    public void setMarkdownContent(String markdownContent) {
        this.markdownContent = markdownContent;
    }

    public int getCharacterCount() {
        return characterCount;
    }

    public void setCharacterCount(int characterCount) {
        this.characterCount = characterCount;
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

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
}
