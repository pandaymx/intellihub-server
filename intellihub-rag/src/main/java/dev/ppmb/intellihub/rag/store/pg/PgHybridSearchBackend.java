package dev.ppmb.intellihub.rag.store.pg;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ppmb.intellihub.core.exception.GlobalErrorCodeConstants;
import dev.ppmb.intellihub.core.exception.IntelliHubExceptionUtil;
import dev.ppmb.intellihub.rag.retrieval.ScoredDocument;
import dev.ppmb.intellihub.rag.store.HybridKnowledgeStore;
import dev.ppmb.intellihub.rag.store.LexicalSearchBackend;
import dev.ppmb.intellihub.rag.store.VectorSearchBackend;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * 基于 PostgreSQL {@code pgvector}（HNSW 近似最近邻索引）与 {@code tsvector}（GIN 倒排索引）的生产级双路下推检索后端。
 *
 * <p>同时实现 {@link VectorSearchBackend} 与 {@link LexicalSearchBackend}，将多租户 {@code JSONB @>} 过滤、
 * 余弦距离排序（{@code <=>}）与全文检索词频打分（{@code ts_rank_cd}）完全下推至 PostgreSQL 数据库执行。
 */
public class PgHybridSearchBackend implements VectorSearchBackend, LexicalSearchBackend {

    public static final String DEFAULT_TABLE_NAME = "intellihub_kb_chunks";
    public static final int DEFAULT_DIMENSIONS = 768;

    private static final Logger log = LoggerFactory.getLogger(PgHybridSearchBackend.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DataSource dataSource;
    private final EmbeddingModel embeddingModel;
    private final String tableName;
    private final int dimensions;

    public PgHybridSearchBackend(DataSource dataSource, EmbeddingModel embeddingModel) {
        this(dataSource, embeddingModel, DEFAULT_TABLE_NAME, DEFAULT_DIMENSIONS, true);
    }

    public PgHybridSearchBackend(
            DataSource dataSource,
            EmbeddingModel embeddingModel,
            String tableName,
            int dimensions,
            boolean initializeSchema) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource 不能为空");
        this.embeddingModel = embeddingModel;
        this.tableName = validateIdentifier(tableName, DEFAULT_TABLE_NAME);
        this.dimensions =
                dimensions > 0
                        ? dimensions
                        : (embeddingModel != null && embeddingModel.dimensions() > 0
                                ? embeddingModel.dimensions()
                                : DEFAULT_DIMENSIONS);
        if (initializeSchema) {
            initSchemaIfNotExists();
        }
    }

    /**
     * 自动初始化 PostgreSQL {@code vector} 插件、知识切片表、JSONB GIN 索引、全文 GIN 索引与 HNSW 向量索引。
     */
    public void initSchemaIfNotExists() {
        String extSql = "CREATE EXTENSION IF NOT EXISTS vector";
        String tableSql =
                "CREATE TABLE IF NOT EXISTS "
                        + tableName
                        + " ("
                        + "id VARCHAR(255) PRIMARY KEY, "
                        + "content TEXT NOT NULL, "
                        + "metadata JSONB NOT NULL DEFAULT '{}'::jsonb, "
                        + "embedding vector("
                        + dimensions
                        + "), "
                        + "fts_vector tsvector GENERATED ALWAYS AS (to_tsvector('simple', coalesce(content, ''))) STORED, "
                        + "updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP"
                        + ")";
        String metaIdxSql =
                "CREATE INDEX IF NOT EXISTS idx_"
                        + tableName
                        + "_meta_gin ON "
                        + tableName
                        + " USING GIN (metadata jsonb_path_ops)";
        String ftsIdxSql =
                "CREATE INDEX IF NOT EXISTS idx_"
                        + tableName
                        + "_fts_gin ON "
                        + tableName
                        + " USING GIN (fts_vector)";
        String hnswIdxSql =
                "CREATE INDEX IF NOT EXISTS idx_"
                        + tableName
                        + "_vec_hnsw ON "
                        + tableName
                        + " USING hnsw (embedding vector_cosine_ops)";

        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute(extSql);
            stmt.execute(tableSql);
            stmt.execute(metaIdxSql);
            stmt.execute(ftsIdxSql);
            stmt.execute(hnswIdxSql);
            log.info(
                    "[PgHybridSearchBackend] PostgreSQL pgvector 表 [{}] (dim={}) 与 HNSW/GIN 索引就绪",
                    tableName,
                    dimensions);
        } catch (SQLException ex) {
            log.warn("[PgHybridSearchBackend] 初始化 PostgreSQL pgvector 表结构失败: {}", ex.getMessage());
        }
    }

    @Override
    public void upsert(Collection<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        String sql =
                "INSERT INTO "
                        + tableName
                        + " (id, content, metadata, embedding, updated_at) "
                        + "VALUES (?, ?, ?::jsonb, ?::vector, CURRENT_TIMESTAMP) "
                        + "ON CONFLICT (id) DO UPDATE SET "
                        + "content = EXCLUDED.content, "
                        + "metadata = EXCLUDED.metadata, "
                        + "embedding = EXCLUDED.embedding, "
                        + "updated_at = CURRENT_TIMESTAMP";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Document doc : documents) {
                if (doc == null || doc.getText() == null || doc.getText().isBlank()) {
                    continue;
                }
                String id =
                        (doc.getId() != null && !doc.getId().isBlank())
                                ? doc.getId().strip()
                                : UUID.randomUUID().toString();
                float[] vec = computeEmbeddingSafely(doc.getText());
                Map<String, Object> meta = new LinkedHashMap<>(doc.getMetadata());
                if (vec != null && vec.length > 0) {
                    meta.put("vector_dim", vec.length);
                }
                ps.setString(1, id);
                ps.setString(2, doc.getText());
                ps.setString(3, toJsonString(meta));
                ps.setString(4, vec != null && vec.length > 0 ? toVectorLiteral(vec) : null);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException ex) {
            throw IntelliHubExceptionUtil.exception(
                    GlobalErrorCodeConstants.INTERNAL_SERVER_ERROR,
                    "PostgreSQL pgvector 写入知识切片失败: " + ex.getMessage());
        }
    }

    @Override
    public boolean delete(String documentId) {
        if (documentId == null || documentId.isBlank()) {
            return false;
        }
        String sql = "DELETE FROM " + tableName + " WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, documentId.strip());
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw IntelliHubExceptionUtil.exception(
                    GlobalErrorCodeConstants.INTERNAL_SERVER_ERROR,
                    "PostgreSQL 删除知识切片失败: " + ex.getMessage());
        }
    }

    @Override
    public void clear() {
        String sql = "TRUNCATE TABLE " + tableName;
        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException ex) {
            throw IntelliHubExceptionUtil.exception(
                    GlobalErrorCodeConstants.INTERNAL_SERVER_ERROR,
                    "PostgreSQL 清空知识切片表失败: " + ex.getMessage());
        }
    }

    @Override
    public int size() {
        String sql = "SELECT COUNT(*) FROM " + tableName;
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException ex) {
            log.warn("[PgHybridSearchBackend] 查询切片总数失败: {}", ex.getMessage());
            return 0;
        }
    }

    @Override
    public boolean isAvailable() {
        return embeddingModel != null;
    }

    @Override
    public List<ScoredDocument> searchSimilar(
            String query, int topK, Map<String, Object> metadataFilters) {
        float[] queryVec = computeEmbeddingSafely(query);
        if (queryVec == null || queryVec.length == 0) {
            return List.of();
        }
        String vecLiteral = toVectorLiteral(queryVec);
        String filterJson = toJsonString(metadataFilters != null ? metadataFilters : Map.of());
        String sql =
                "SELECT id, content, metadata::text, 1.0 - (embedding <=> ?::vector) AS vector_score "
                        + "FROM "
                        + tableName
                        + " WHERE embedding IS NOT NULL AND metadata @> ?::jsonb "
                        + "ORDER BY embedding <=> ?::vector ASC "
                        + "LIMIT ?";

        List<ScoredDocument> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, vecLiteral);
            ps.setString(2, filterJson);
            ps.setString(3, vecLiteral);
            ps.setInt(4, Math.max(1, topK));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString(1);
                    String content = rs.getString(2);
                    Map<String, Object> meta = parseJsonMap(rs.getString(3));
                    double sim = rs.getDouble(4);
                    if (sim > 0.0) {
                        Document doc =
                                Document.builder().id(id).text(content).metadata(meta).build();
                        results.add(new ScoredDocument(doc, sim, sim, null, null));
                    }
                }
            }
            return results;
        } catch (SQLException ex) {
            log.warn("[PgHybridSearchBackend] pgvector 向量下推检索异常: {}", ex.getMessage());
            return List.of();
        }
    }

    @Override
    public List<ScoredDocument> searchLexical(
            String query, int topK, Map<String, Object> metadataFilters) {
        List<String> tokens =
                HybridKnowledgeStore.tokenize(query).stream()
                        .map(t -> t.replaceAll("[^a-zA-Z0-9_\\u4e00-\\u9fa5]", ""))
                        .filter(t -> !t.isEmpty())
                        .distinct()
                        .limit(24)
                        .toList();
        if (tokens.isEmpty()) {
            return List.of();
        }
        String tsQueryExpr = tokens.stream().collect(Collectors.joining(" | "));
        String filterJson = toJsonString(metadataFilters != null ? metadataFilters : Map.of());
        String sql =
                "SELECT id, content, metadata::text, "
                        + "ts_rank_cd(fts_vector, to_tsquery('simple', ?)) AS lexical_score "
                        + "FROM "
                        + tableName
                        + " WHERE metadata @> ?::jsonb "
                        + "AND fts_vector @@ to_tsquery('simple', ?) "
                        + "ORDER BY lexical_score DESC "
                        + "LIMIT ?";

        List<ScoredDocument> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tsQueryExpr);
            ps.setString(2, filterJson);
            ps.setString(3, tsQueryExpr);
            ps.setInt(4, Math.max(1, topK));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString(1);
                    String content = rs.getString(2);
                    Map<String, Object> meta = parseJsonMap(rs.getString(3));
                    double bm25 = rs.getDouble(4);
                    if (bm25 > 0.0) {
                        Document doc =
                                Document.builder().id(id).text(content).metadata(meta).build();
                        results.add(new ScoredDocument(doc, bm25, null, bm25, null));
                    }
                }
            }
            return results;
        } catch (SQLException ex) {
            log.warn("[PgHybridSearchBackend] PostgreSQL 全文下推检索异常: {}", ex.getMessage());
            return List.of();
        }
    }

    public static String toVectorLiteral(float[] vector) {
        if (vector == null || vector.length == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder(vector.length * 10);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    private float[] computeEmbeddingSafely(String text) {
        if (embeddingModel == null || text == null || text.isBlank()) {
            return null;
        }
        try {
            return embeddingModel.embed(text);
        } catch (RuntimeException ex) {
            log.warn("[PgHybridSearchBackend] 生成 Embedding 向量失败: {}", ex.getMessage());
            return null;
        }
    }

    private static String toJsonString(Map<String, Object> map) {
        try {
            return MAPPER.writeValueAsString(map != null ? map : Map.of());
        } catch (Exception ex) {
            return "{}";
        }
    }

    private static Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private static String validateIdentifier(String candidate, String fallback) {
        String target = (candidate == null || candidate.isBlank()) ? fallback : candidate.strip();
        if (!target.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            throw IntelliHubExceptionUtil.invalidParamException("非法数据表标识符: " + target);
        }
        return target;
    }
}
