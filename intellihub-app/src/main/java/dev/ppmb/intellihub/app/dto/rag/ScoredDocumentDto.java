package dev.ppmb.intellihub.app.dto.rag;

import dev.ppmb.intellihub.rag.retrieval.ScoredDocument;
import java.util.Map;

/**
 * 带有相关性得分明细的检索切片传输对象。
 */
public class ScoredDocumentDto {

    private String id;
    private String text;
    private double score;
    private Double vectorScore;
    private Double bm25Score;
    private Double rerankScore;
    private Map<String, Object> metadata;

    public ScoredDocumentDto() {}

    public static ScoredDocumentDto fromDomain(ScoredDocument doc) {
        if (doc == null || doc.document() == null) {
            return null;
        }
        ScoredDocumentDto dto = new ScoredDocumentDto();
        dto.setId(doc.document().getId());
        dto.setText(doc.document().getText());
        dto.setScore(doc.score());
        dto.setVectorScore(doc.vectorScore());
        dto.setBm25Score(doc.bm25Score());
        dto.setRerankScore(doc.rerankScore());
        dto.setMetadata(doc.document().getMetadata());
        return dto;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }

    public Double getVectorScore() {
        return vectorScore;
    }

    public void setVectorScore(Double vectorScore) {
        this.vectorScore = vectorScore;
    }

    public Double getBm25Score() {
        return bm25Score;
    }

    public void setBm25Score(Double bm25Score) {
        this.bm25Score = bm25Score;
    }

    public Double getRerankScore() {
        return rerankScore;
    }

    public void setRerankScore(Double rerankScore) {
        this.rerankScore = rerankScore;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
}
