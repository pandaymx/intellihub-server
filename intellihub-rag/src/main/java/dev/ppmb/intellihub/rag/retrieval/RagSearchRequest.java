package dev.ppmb.intellihub.rag.retrieval;

import dev.ppmb.intellihub.core.exception.IntelliHubExceptionUtil;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 混合检索请求参数定义（支持 Top-K、最低阈值、多租户元数据强过滤与 RRF 权重调节）。
 */
public final class RagSearchRequest {

    public static final int DEFAULT_TOP_K = 5;
    public static final double DEFAULT_MIN_SCORE = 0.0;
    public static final double DEFAULT_VECTOR_WEIGHT = 0.6;
    public static final double DEFAULT_BM25_WEIGHT = 0.4;
    public static final int DEFAULT_RRF_K = 60;

    private final String query;
    private final int topK;
    private final double minScore;
    private final RagSearchMode searchMode;
    private final Map<String, Object> metadataFilters;
    private final double vectorWeight;
    private final double bm25Weight;
    private final int rrfK;

    private RagSearchRequest(Builder builder) {
        if (builder.query == null || builder.query.isBlank()) {
            throw IntelliHubExceptionUtil.invalidParamException("RAG 检索 query 不能为空");
        }
        if (builder.topK <= 0) {
            throw IntelliHubExceptionUtil.invalidParamException(
                    "RAG 检索 topK 必须大于 0，当前值: {}", builder.topK);
        }
        this.query = builder.query.strip();
        this.topK = builder.topK;
        this.minScore = builder.minScore;
        this.searchMode = builder.searchMode != null ? builder.searchMode : RagSearchMode.HYBRID;
        this.metadataFilters =
                builder.metadataFilters.isEmpty()
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(builder.metadataFilters));
        this.vectorWeight = builder.vectorWeight;
        this.bm25Weight = builder.bm25Weight;
        this.rrfK = Math.max(1, builder.rrfK);
    }

    public static RagSearchRequest of(String query) {
        return builder(query).build();
    }

    public static RagSearchRequest of(String query, int topK) {
        return builder(query).topK(topK).build();
    }

    public static Builder builder(String query) {
        return new Builder().query(query);
    }

    public String query() {
        return query;
    }

    public int topK() {
        return topK;
    }

    public double minScore() {
        return minScore;
    }

    public RagSearchMode searchMode() {
        return searchMode;
    }

    public Map<String, Object> metadataFilters() {
        return metadataFilters;
    }

    public double vectorWeight() {
        return vectorWeight;
    }

    public double bm25Weight() {
        return bm25Weight;
    }

    public int rrfK() {
        return rrfK;
    }

    public static final class Builder {
        private String query;
        private int topK = DEFAULT_TOP_K;
        private double minScore = DEFAULT_MIN_SCORE;
        private RagSearchMode searchMode = RagSearchMode.HYBRID;
        private final Map<String, Object> metadataFilters = new LinkedHashMap<>();
        private double vectorWeight = DEFAULT_VECTOR_WEIGHT;
        private double bm25Weight = DEFAULT_BM25_WEIGHT;
        private int rrfK = DEFAULT_RRF_K;

        public Builder query(String query) {
            this.query = query;
            return this;
        }

        public Builder topK(int topK) {
            this.topK = topK;
            return this;
        }

        public Builder minScore(double minScore) {
            this.minScore = minScore;
            return this;
        }

        public Builder searchMode(RagSearchMode searchMode) {
            this.searchMode = searchMode;
            return this;
        }

        public Builder filterMetadata(String key, Object value) {
            if (key != null && !key.isBlank() && value != null) {
                this.metadataFilters.put(key.strip(), value);
            }
            return this;
        }

        public Builder metadataFilters(Map<String, Object> filters) {
            if (filters != null) {
                filters.forEach(this::filterMetadata);
            }
            return this;
        }

        public Builder vectorWeight(double vectorWeight) {
            this.vectorWeight = vectorWeight;
            return this;
        }

        public Builder bm25Weight(double bm25Weight) {
            this.bm25Weight = bm25Weight;
            return this;
        }

        public Builder rrfK(int rrfK) {
            this.rrfK = rrfK;
            return this;
        }

        public RagSearchRequest build() {
            return new RagSearchRequest(this);
        }
    }
}
