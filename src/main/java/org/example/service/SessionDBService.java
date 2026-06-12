package org.example.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SessionDBService {

    private static final Logger logger = LoggerFactory.getLogger(SessionDBService.class);

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final Object initLock = new Object();
    private volatile boolean initialized;

    public SessionDBService(
            @Value("${spring.datasource.url}") String jdbcUrl,
            @Value("${spring.datasource.username:}") String username,
            @Value("${spring.datasource.password:}") String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    private Connection getConnection() throws SQLException {
        if (username == null || username.isBlank()) {
            return DriverManager.getConnection(jdbcUrl);
        }
        return DriverManager.getConnection(jdbcUrl, username, password == null ? "" : password);
    }

    public void initialize() {
        synchronized (initLock) {
            if (initialized) {
                return;
            }
            try (Connection conn = getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS sessions (
                            id VARCHAR(128) PRIMARY KEY,
                            user_id VARCHAR(128) NULL,
                            title VARCHAR(512) NOT NULL DEFAULT '新对话',
                            created_at VARCHAR(64) NOT NULL,
                            updated_at VARCHAR(64) NOT NULL,
                            message_count INT NOT NULL DEFAULT 0
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                        """);
                addColumnIfNeeded(conn, "sessions", "user_id", "VARCHAR(128) NULL");

                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS session_messages (
                            id BIGINT AUTO_INCREMENT PRIMARY KEY,
                            session_id VARCHAR(128) NOT NULL,
                            role VARCHAR(32) NOT NULL,
                            content LONGTEXT NOT NULL,
                            created_at VARCHAR(64) NOT NULL,
                            CONSTRAINT fk_session_messages_session
                                FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                        """);
                createIndexIfNeeded(stmt, "CREATE INDEX idx_sessions_updated_at ON sessions(updated_at DESC)");
                createIndexIfNeeded(stmt, "CREATE INDEX idx_sessions_user_updated_at ON sessions(user_id, updated_at DESC)");
                createIndexIfNeeded(stmt, "CREATE INDEX idx_session_messages_session_id ON session_messages(session_id, id)");
                initialized = true;
                logger.info("Session MySQL store initialized: {}", jdbcUrl);
            } catch (SQLException e) {
                logger.error("Failed to initialize session MySQL store", e);
                throw new RuntimeException("Session DB init failed", e);
            }
        }
    }

    public List<Map<String, Object>> listSessions(int limit, int offset) {
        return listSessions(limit, offset, null);
    }

    public List<Map<String, Object>> listSessions(int limit, int offset, String userId) {
        initialize();
        List<Map<String, Object>> result = new ArrayList<>();
        String sql = hasUser(userId)
                ? "SELECT id, user_id, title, created_at, updated_at, message_count FROM sessions WHERE user_id = ? ORDER BY updated_at DESC LIMIT ? OFFSET ?"
                : "SELECT id, user_id, title, created_at, updated_at, message_count FROM sessions ORDER BY updated_at DESC LIMIT ? OFFSET ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int index = 1;
            if (hasUser(userId)) {
                ps.setString(index++, userId);
            }
            ps.setInt(index++, Math.max(1, limit));
            ps.setInt(index, Math.max(0, offset));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(rowToMap(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to list sessions", e);
        }
        return result;
    }

    public int countSessions() {
        return countSessions(null);
    }

    public int countSessions(String userId) {
        initialize();
        String sql = hasUser(userId)
                ? "SELECT COUNT(*) FROM sessions WHERE user_id = ?"
                : "SELECT COUNT(*) FROM sessions";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (hasUser(userId)) {
                ps.setString(1, userId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            logger.error("Failed to count sessions", e);
            return 0;
        }
    }

    public Map<String, Object> getSession(String sessionId) {
        return getSession(sessionId, null);
    }

    public Map<String, Object> getSession(String sessionId, String userId) {
        initialize();
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        String sql = hasUser(userId)
                ? "SELECT id, user_id, title, created_at, updated_at, message_count FROM sessions WHERE id = ? AND user_id = ?"
                : "SELECT id, user_id, title, created_at, updated_at, message_count FROM sessions WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            if (hasUser(userId)) {
                ps.setString(2, userId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rowToMap(rs) : null;
            }
        } catch (SQLException e) {
            logger.error("Failed to get session {}", sessionId, e);
            return null;
        }
    }

    public Map<String, Object> createSession(String sessionId, String title) {
        return createSession(sessionId, title, null);
    }

    public Map<String, Object> createSession(String sessionId, String title, String userId) {
        initialize();
        String effectiveId = (sessionId == null || sessionId.isBlank()) ? UUID.randomUUID().toString() : sessionId;
        Map<String, Object> existing = getSession(effectiveId, userId);
        if (existing != null) {
            return existing;
        }
        Map<String, Object> existingById = getSession(effectiveId);
        if (hasUser(userId) && existingById != null) {
            Object owner = existingById.get("user_id");
            if (owner == null || owner.toString().isBlank()) {
                bindSessionToUser(effectiveId, userId);
                return getSession(effectiveId, userId);
            }
            throw new SecurityException("Session does not belong to current user");
        }

        String now = Instant.now().toString();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO sessions (id, user_id, title, created_at, updated_at, message_count) VALUES (?, ?, ?, ?, ?, 0)")) {
            ps.setString(1, effectiveId);
            ps.setString(2, normalizeUserId(userId));
            ps.setString(3, title == null || title.isBlank() ? "新对话" : title);
            ps.setString(4, now);
            ps.setString(5, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            if (e.getErrorCode() != 1062) {
                logger.error("Failed to create session {}", effectiveId, e);
            }
        }
        return getSession(effectiveId, userId);
    }

    public void appendMessagePair(String sessionId, String userQuestion, String aiAnswer) {
        appendMessagePair(sessionId, userQuestion, aiAnswer, null);
    }

    public void appendMessagePair(String sessionId, String userQuestion, String aiAnswer, String userId) {
        initialize();
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId cannot be empty");
        }
        Map<String, Object> session = createSession(sessionId, null, userId);
        if (hasUser(userId) && session == null) {
            throw new SecurityException("Session does not belong to current user");
        }

        String now = Instant.now().toString();
        try (Connection conn = getConnection()) {
            boolean oldAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement insert = conn.prepareStatement(
                    "INSERT INTO session_messages (session_id, role, content, created_at) VALUES (?, ?, ?, ?)");
                 PreparedStatement update = conn.prepareStatement(
                         "UPDATE sessions SET updated_at = ?, message_count = " +
                                 "(SELECT COUNT(*) / 2 FROM session_messages WHERE session_id = ?) WHERE id = ?")) {
                insert.setString(1, sessionId);
                insert.setString(2, "user");
                insert.setString(3, userQuestion == null ? "" : userQuestion);
                insert.setString(4, now);
                insert.executeUpdate();

                insert.setString(1, sessionId);
                insert.setString(2, "assistant");
                insert.setString(3, aiAnswer == null ? "" : aiAnswer);
                insert.setString(4, now);
                insert.executeUpdate();

                update.setString(1, now);
                update.setString(2, sessionId);
                update.setString(3, sessionId);
                update.executeUpdate();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(oldAutoCommit);
            }
        } catch (SQLException e) {
            logger.error("Failed to append messages for session {}", sessionId, e);
            throw new RuntimeException("append session messages failed", e);
        }
    }

    public List<Map<String, String>> listSessionMessages(String sessionId, int maxMessages) {
        return listSessionMessages(sessionId, maxMessages, null);
    }

    public List<Map<String, String>> listSessionMessages(String sessionId, int maxMessages, String userId) {
        initialize();
        List<Map<String, String>> result = new ArrayList<>();
        if (sessionId == null || sessionId.isBlank()) {
            return result;
        }
        if (hasUser(userId) && getSession(sessionId, userId) == null) {
            return result;
        }

        int limit = maxMessages > 0 ? maxMessages : 100;
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT role, content FROM (
                         SELECT id, role, content
                         FROM session_messages
                         WHERE session_id = ?
                         ORDER BY id DESC
                         LIMIT ?
                     ) recent ORDER BY id ASC
                     """)) {
            ps.setString(1, sessionId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> message = new HashMap<>();
                    message.put("role", rs.getString("role"));
                    message.put("content", rs.getString("content"));
                    result.add(message);
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to list messages for session {}", sessionId, e);
        }
        return result;
    }

    public void clearSessionMessages(String sessionId) {
        clearSessionMessages(sessionId, null);
    }

    public void clearSessionMessages(String sessionId, String userId) {
        initialize();
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        if (hasUser(userId) && getSession(sessionId, userId) == null) {
            return;
        }
        String now = Instant.now().toString();
        try (Connection conn = getConnection()) {
            boolean oldAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement delete = conn.prepareStatement("DELETE FROM session_messages WHERE session_id = ?");
                 PreparedStatement update = conn.prepareStatement(
                         "UPDATE sessions SET updated_at = ?, message_count = 0 WHERE id = ?")) {
                delete.setString(1, sessionId);
                delete.executeUpdate();
                update.setString(1, now);
                update.setString(2, sessionId);
                update.executeUpdate();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(oldAutoCommit);
            }
        } catch (SQLException e) {
            logger.error("Failed to clear messages for session {}", sessionId, e);
            throw new RuntimeException("clear session messages failed", e);
        }
    }

    public Map<String, Object> updateSession(String sessionId, String title, Integer messageCount) {
        return updateSession(sessionId, title, messageCount, null);
    }

    public Map<String, Object> updateSession(String sessionId, String title, Integer messageCount, String userId) {
        initialize();
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        String now = Instant.now().toString();
        try (Connection conn = getConnection()) {
            StringBuilder sql = new StringBuilder("UPDATE sessions SET updated_at = ?");
            List<Object> params = new ArrayList<>();
            params.add(now);
            if (title != null) {
                sql.append(", title = ?");
                params.add(title);
            }
            if (messageCount != null) {
                sql.append(", message_count = ?");
                params.add(messageCount);
            }
            sql.append(" WHERE id = ?");
            params.add(sessionId);
            if (hasUser(userId)) {
                sql.append(" AND user_id = ?");
                params.add(userId);
            }

            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                if (ps.executeUpdate() == 0) {
                    return null;
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to update session {}", sessionId, e);
            return null;
        }
        return getSession(sessionId, userId);
    }

    public boolean deleteSession(String sessionId) {
        return deleteSession(sessionId, null);
    }

    public boolean deleteSession(String sessionId, String userId) {
        initialize();
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        String sql = hasUser(userId)
                ? "DELETE FROM sessions WHERE id = ? AND user_id = ?"
                : "DELETE FROM sessions WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            if (hasUser(userId)) {
                ps.setString(2, userId);
            }
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.error("Failed to delete session {}", sessionId, e);
            return false;
        }
    }

    public List<Map<String, Object>> syncSessions(List<Map<String, Object>> sessions) {
        return syncSessions(sessions, null);
    }

    public List<Map<String, Object>> syncSessions(List<Map<String, Object>> sessions, String userId) {
        initialize();
        if (sessions == null) {
            return listSessions(100, 0, userId);
        }
        for (Map<String, Object> session : sessions) {
            Object idObj = session.get("id");
            if (!(idObj instanceof String id) || id.isBlank()) {
                continue;
            }
            Map<String, Object> existing = getSession(id, userId);
            if (existing == null) {
                createSession(id, (String) session.getOrDefault("title", "新对话"), userId);
            } else {
                String payloadUpdated = (String) session.get("updated_at");
                String dbUpdated = (String) existing.get("updated_at");
                if (payloadUpdated != null && (dbUpdated == null || payloadUpdated.compareTo(dbUpdated) > 0)) {
                    updateSession(id, (String) session.get("title"),
                            session.get("message_count") instanceof Number n ? n.intValue() : null,
                            userId);
                }
            }
        }
        return listSessions(100, 0, userId);
    }

    private void createIndexIfNeeded(Statement stmt, String sql) throws SQLException {
        try {
            stmt.execute(sql);
        } catch (SQLException e) {
            if (e.getErrorCode() == 1061) {
                return;
            }
            throw e;
        }
    }

    private void addColumnIfNeeded(Connection conn, String table, String column, String definition) throws SQLException {
        try (ResultSet rs = conn.getMetaData().getColumns(null, null, table, column)) {
            if (rs.next()) {
                return;
            }
        }
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        } catch (SQLException e) {
            if (e.getErrorCode() != 1060) {
                throw e;
            }
        }
    }

    private boolean hasUser(String userId) {
        return userId != null && !userId.isBlank();
    }

    private String normalizeUserId(String userId) {
        return hasUser(userId) ? userId : null;
    }

    private void bindSessionToUser(String sessionId, String userId) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE sessions SET user_id = ? WHERE id = ? AND user_id IS NULL")) {
            ps.setString(1, userId);
            ps.setString(2, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to bind session {} to user {}", sessionId, userId, e);
            throw new RuntimeException("bind session owner failed", e);
        }
    }

    private Map<String, Object> rowToMap(ResultSet rs) throws SQLException {
        Map<String, Object> map = new HashMap<>();
        map.put("id", rs.getString("id"));
        map.put("user_id", rs.getString("user_id"));
        map.put("title", rs.getString("title"));
        map.put("created_at", rs.getString("created_at"));
        map.put("updated_at", rs.getString("updated_at"));
        map.put("message_count", rs.getInt("message_count"));
        return map;
    }
}
