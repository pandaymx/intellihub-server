package dev.ppmb.intellihub.agent.memory;

import dev.ppmb.intellihub.agent.checkpoint.AgentCheckpointSerializer;
import dev.ppmb.intellihub.core.exception.GlobalErrorCodeConstants;
import dev.ppmb.intellihub.core.exception.IntelliHubExceptionUtil;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;

/**
 * 基于关系型数据库（PostgreSQL / H2 / MySQL）标准 JDBC {@link DataSource} 的生产级 {@link SessionMemoryStore} 实现。
 */
public class JdbcSessionMemoryStore implements SessionMemoryStore {

    public static final String DEFAULT_TABLE_NAME = "intellihub_session_memory";

    private static final Logger log = LoggerFactory.getLogger(JdbcSessionMemoryStore.class);

    private final DataSource dataSource;
    private final String tableName;
    private final int maxMessagesPerSession;

    public JdbcSessionMemoryStore(DataSource dataSource) {
        this(
                dataSource,
                DEFAULT_TABLE_NAME,
                InMemorySessionMemoryStore.DEFAULT_MAX_MESSAGES_PER_SESSION,
                true);
    }

    public JdbcSessionMemoryStore(
            DataSource dataSource,
            String tableName,
            int maxMessagesPerSession,
            boolean initializeSchema) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource 不能为空");
        this.tableName = validateIdentifier(tableName, DEFAULT_TABLE_NAME);
        this.maxMessagesPerSession =
                maxMessagesPerSession > 0
                        ? maxMessagesPerSession
                        : InMemorySessionMemoryStore.DEFAULT_MAX_MESSAGES_PER_SESSION;
        if (initializeSchema) {
            initTableIfNotExists();
        }
    }

    public void initTableIfNotExists() {
        String ddl =
                "CREATE TABLE IF NOT EXISTS "
                        + tableName
                        + " ("
                        + "session_id VARCHAR(128) PRIMARY KEY, "
                        + "messages_json TEXT NOT NULL, "
                        + "message_count INT NOT NULL, "
                        + "updated_at TIMESTAMP NOT NULL"
                        + ")";
        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute(ddl);
        } catch (SQLException ex) {
            log.warn("[JdbcSessionMemoryStore] 自动初始化表 [{}] 失败: {}", tableName, ex.getMessage());
        }
    }

    @Override
    public List<Message> getMessages(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        String sql = "SELECT messages_json FROM " + tableName + " WHERE session_id = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId.strip());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return AgentCheckpointSerializer.deserializeMessages(rs.getString(1));
                }
                return List.of();
            }
        } catch (SQLException ex) {
            throw IntelliHubExceptionUtil.exception(
                    GlobalErrorCodeConstants.INTERNAL_SERVER_ERROR,
                    "JDBC 查询会话记忆失败: " + ex.getMessage());
        }
    }

    @Override
    public void appendMessages(String sessionId, List<Message> messages) {
        if (sessionId == null || sessionId.isBlank() || messages == null || messages.isEmpty()) {
            return;
        }
        List<Message> existing = new ArrayList<>(getMessages(sessionId));
        existing.addAll(messages);
        replaceMessages(sessionId, existing);
    }

    @Override
    public void replaceMessages(String sessionId, List<Message> messages) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        if (messages == null || messages.isEmpty()) {
            clearSession(sessionId);
            return;
        }
        List<Message> trimmed =
                messages.size() <= maxMessagesPerSession
                        ? List.copyOf(messages)
                        : List.copyOf(
                                messages.subList(
                                        messages.size() - maxMessagesPerSession, messages.size()));
        String json = AgentCheckpointSerializer.serializeMessages(trimmed);
        Timestamp now = Timestamp.from(Instant.now());
        String sid = sessionId.strip();

        String updateSql =
                "UPDATE "
                        + tableName
                        + " SET messages_json = ?, message_count = ?, updated_at = ? "
                        + "WHERE session_id = ?";
        String insertSql =
                "INSERT INTO "
                        + tableName
                        + " (session_id, messages_json, message_count, updated_at) "
                        + "VALUES (?, ?, ?, ?)";

        try (Connection conn = dataSource.getConnection()) {
            int updated;
            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setString(1, json);
                ps.setInt(2, trimmed.size());
                ps.setTimestamp(3, now);
                ps.setString(4, sid);
                updated = ps.executeUpdate();
            }
            if (updated == 0) {
                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    ps.setString(1, sid);
                    ps.setString(2, json);
                    ps.setInt(3, trimmed.size());
                    ps.setTimestamp(4, now);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException ex) {
            throw IntelliHubExceptionUtil.exception(
                    GlobalErrorCodeConstants.INTERNAL_SERVER_ERROR,
                    "JDBC 保存会话记忆失败: " + ex.getMessage());
        }
    }

    @Override
    public void clearSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        String sql = "DELETE FROM " + tableName + " WHERE session_id = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId.strip());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw IntelliHubExceptionUtil.exception(
                    GlobalErrorCodeConstants.INTERNAL_SERVER_ERROR,
                    "JDBC 清理会话记忆失败: " + ex.getMessage());
        }
    }

    @Override
    public List<String> listSessionIds() {
        String sql = "SELECT session_id FROM " + tableName + " ORDER BY updated_at DESC";
        List<String> ids = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ids.add(rs.getString(1));
            }
            return ids;
        } catch (SQLException ex) {
            log.warn("[JdbcSessionMemoryStore] 查询会话 ID 列表失败: {}", ex.getMessage());
            return List.of();
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
