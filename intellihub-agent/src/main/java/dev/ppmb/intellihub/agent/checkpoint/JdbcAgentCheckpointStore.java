package dev.ppmb.intellihub.agent.checkpoint;

import dev.ppmb.intellihub.agent.engine.AgentCheckpoint;
import dev.ppmb.intellihub.core.exception.GlobalErrorCodeConstants;
import dev.ppmb.intellihub.core.exception.IntelliHubExceptionUtil;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于关系型数据库（PostgreSQL / H2 / MySQL）标准 JDBC {@link DataSource} 的生产级 {@link AgentCheckpointStore}
 * 实现。
 *
 * <p>支持多实例集群下跨节点共享 {@code AWAITING_APPROVAL} 挂起状态，任意工作节点收到审批回调均可恢复执行。
 */
public class JdbcAgentCheckpointStore implements AgentCheckpointStore {

    public static final String DEFAULT_TABLE_NAME = "intellihub_agent_checkpoints";

    private static final Logger log = LoggerFactory.getLogger(JdbcAgentCheckpointStore.class);

    private final DataSource dataSource;
    private final String tableName;

    public JdbcAgentCheckpointStore(DataSource dataSource) {
        this(dataSource, DEFAULT_TABLE_NAME, true);
    }

    public JdbcAgentCheckpointStore(
            DataSource dataSource, String tableName, boolean initializeSchema) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource 不能为空");
        this.tableName = validateIdentifier(tableName, DEFAULT_TABLE_NAME);
        if (initializeSchema) {
            initTableIfNotExists();
        }
    }

    public void initTableIfNotExists() {
        String ddl =
                "CREATE TABLE IF NOT EXISTS "
                        + tableName
                        + " ("
                        + "checkpoint_id VARCHAR(128) PRIMARY KEY, "
                        + "approval_ticket_id VARCHAR(255), "
                        + "suspended_iteration INT NOT NULL, "
                        + "payload_json TEXT NOT NULL, "
                        + "updated_at TIMESTAMP NOT NULL"
                        + ")";
        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute(ddl);
        } catch (SQLException ex) {
            log.warn("[JdbcAgentCheckpointStore] 自动初始化表 [{}] 失败: {}", tableName, ex.getMessage());
        }
    }

    @Override
    public void save(AgentCheckpoint checkpoint) {
        if (checkpoint == null
                || checkpoint.checkpointId() == null
                || checkpoint.checkpointId().isBlank()) {
            return;
        }
        String cpId = checkpoint.checkpointId().strip();
        String json = AgentCheckpointSerializer.serializeCheckpoint(checkpoint);
        Timestamp now = Timestamp.from(Instant.now());

        String updateSql =
                "UPDATE "
                        + tableName
                        + " SET approval_ticket_id = ?, suspended_iteration = ?, payload_json = ?, updated_at = ? "
                        + "WHERE checkpoint_id = ?";
        String insertSql =
                "INSERT INTO "
                        + tableName
                        + " (checkpoint_id, approval_ticket_id, suspended_iteration, payload_json, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?)";

        try (Connection conn = dataSource.getConnection()) {
            int updated;
            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setString(1, checkpoint.approvalTicketId());
                ps.setInt(2, checkpoint.suspendedAtIteration());
                ps.setString(3, json);
                ps.setTimestamp(4, now);
                ps.setString(5, cpId);
                updated = ps.executeUpdate();
            }
            if (updated == 0) {
                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    ps.setString(1, cpId);
                    ps.setString(2, checkpoint.approvalTicketId());
                    ps.setInt(3, checkpoint.suspendedAtIteration());
                    ps.setString(4, json);
                    ps.setTimestamp(5, now);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException ex) {
            throw IntelliHubExceptionUtil.exception(
                    GlobalErrorCodeConstants.INTERNAL_SERVER_ERROR,
                    "JDBC 保存 AgentCheckpoint 失败: " + ex.getMessage());
        }
    }

    @Override
    public Optional<AgentCheckpoint> findById(String checkpointId) {
        if (checkpointId == null || checkpointId.isBlank()) {
            return Optional.empty();
        }
        String sql = "SELECT payload_json FROM " + tableName + " WHERE checkpoint_id = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, checkpointId.strip());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String json = rs.getString(1);
                    return Optional.of(AgentCheckpointSerializer.deserializeCheckpoint(json));
                }
                return Optional.empty();
            }
        } catch (SQLException ex) {
            throw IntelliHubExceptionUtil.exception(
                    GlobalErrorCodeConstants.INTERNAL_SERVER_ERROR,
                    "JDBC 查询 AgentCheckpoint 失败: " + ex.getMessage());
        }
    }

    @Override
    public Optional<AgentCheckpoint> remove(String checkpointId) {
        if (checkpointId == null || checkpointId.isBlank()) {
            return Optional.empty();
        }
        Optional<AgentCheckpoint> found = findById(checkpointId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        String delSql = "DELETE FROM " + tableName + " WHERE checkpoint_id = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(delSql)) {
            ps.setString(1, checkpointId.strip());
            int affected = ps.executeUpdate();
            return affected > 0 ? found : Optional.empty();
        } catch (SQLException ex) {
            throw IntelliHubExceptionUtil.exception(
                    GlobalErrorCodeConstants.INTERNAL_SERVER_ERROR,
                    "JDBC 删除 AgentCheckpoint 失败: " + ex.getMessage());
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
            log.warn("[JdbcAgentCheckpointStore] 统计挂起快照数失败: {}", ex.getMessage());
            return 0;
        }
    }

    static String validateIdentifier(String candidate, String fallback) {
        String target = (candidate == null || candidate.isBlank()) ? fallback : candidate.strip();
        if (!target.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            throw IntelliHubExceptionUtil.invalidParamException("非法数据表标识符: " + target);
        }
        return target;
    }
}
