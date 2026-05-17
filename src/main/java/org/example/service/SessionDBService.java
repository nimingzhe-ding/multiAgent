package org.example.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SQLite session persistence service.
 * Thread-safe via ThreadLocal connection + WAL mode.
 */
@Service
public class SessionDBService {

    private static final Logger logger = LoggerFactory.getLogger(SessionDBService.class);

    private final String dbPath;
    private final ThreadLocal<Connection> threadLocalConn = new ThreadLocal<>();
    private final Object initLock = new Object();
    private volatile boolean initialized;

    public SessionDBService(@Value("${sqlite.db-path:./data/sessions.db}") String dbPath) {
        this.dbPath = dbPath;
    }

    private Connection getConnection() throws SQLException {
        Connection conn = threadLocalConn.get();
        if (conn == null || conn.isClosed()) {
            conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("PRAGMA busy_timeout=5000");
            }
            threadLocalConn.set(conn);
        }
        return conn;
    }

    public void initialize() {
        synchronized (initLock) {
            if (initialized) return;
            try {
                // Ensure parent directory exists
                java.io.File dbFile = new java.io.File(dbPath);
                java.io.File parent = dbFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }

                try (Statement stmt = getConnection().createStatement()) {
                    stmt.execute("""
                        CREATE TABLE IF NOT EXISTS sessions (
                            id TEXT PRIMARY KEY,
                            title TEXT NOT NULL DEFAULT '新对话',
                            created_at TEXT NOT NULL,
                            updated_at TEXT NOT NULL,
                            message_count INTEGER NOT NULL DEFAULT 0
                        )
                    """);
                    stmt.execute("""
                        CREATE TABLE IF NOT EXISTS session_messages (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            session_id TEXT NOT NULL,
                            role TEXT NOT NULL,
                            content TEXT NOT NULL,
                            created_at TEXT NOT NULL,
                            FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE
                        )
                    """);
                    stmt.execute("""
                        CREATE INDEX IF NOT EXISTS idx_sessions_updated_at
                        ON sessions(updated_at DESC)
                    """);
                    stmt.execute("""
                        CREATE INDEX IF NOT EXISTS idx_session_messages_session_id
                        ON session_messages(session_id, id)
                    """);
                }
                initialized = true;
                logger.info("Session DB initialized at {}", dbPath);
            } catch (SQLException e) {
                logger.error("Failed to initialize session DB", e);
                throw new RuntimeException("Session DB init failed", e);
            }
        }
    }

    public List<Map<String, Object>> listSessions(int limit, int offset) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            try (PreparedStatement ps = getConnection().prepareStatement(
                    "SELECT id, title, created_at, updated_at, message_count FROM sessions ORDER BY updated_at DESC LIMIT ? OFFSET ?")) {
                ps.setInt(1, limit);
                ps.setInt(2, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.add(rowToMap(rs));
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to list sessions", e);
        }
        return result;
    }

    public int countSessions() {
        try {
            try (Statement stmt = getConnection().createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM sessions")) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            logger.error("Failed to count sessions", e);
        }
        return 0;
    }

    public Map<String, Object> getSession(String sessionId) {
        try {
            try (PreparedStatement ps = getConnection().prepareStatement(
                    "SELECT id, title, created_at, updated_at, message_count FROM sessions WHERE id = ?")) {
                ps.setString(1, sessionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rowToMap(rs);
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to get session {}", sessionId, e);
        }
        return null;
    }

    public Map<String, Object> createSession(String sessionId, String title) {
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = java.util.UUID.randomUUID().toString();
        }
        Map<String, Object> existing = getSession(sessionId);
        if (existing != null) return existing;

        String now = Instant.now().toString();
        try {
            try (PreparedStatement ps = getConnection().prepareStatement(
                    "INSERT INTO sessions (id, title, created_at, updated_at, message_count) VALUES (?, ?, ?, ?, 0)")) {
                ps.setString(1, sessionId);
                ps.setString(2, title != null ? title : "新对话");
                ps.setString(3, now);
                ps.setString(4, now);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.error("Failed to create session {}", sessionId, e);
        }
        return getSession(sessionId);
    }

    public void appendMessagePair(String sessionId, String userQuestion, String aiAnswer) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId cannot be empty");
        }
        createSession(sessionId, null);

        String now = Instant.now().toString();
        try {
            Connection conn = getConnection();
            boolean oldAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement insert = conn.prepareStatement(
                    "INSERT INTO session_messages (session_id, role, content, created_at) VALUES (?, ?, ?, ?)");
                 PreparedStatement update = conn.prepareStatement(
                         "UPDATE sessions SET updated_at = ?, message_count = " +
                         "(SELECT COUNT(*) / 2 FROM session_messages WHERE session_id = ?) WHERE id = ?")) {
                insert.setString(1, sessionId);
                insert.setString(2, "user");
                insert.setString(3, userQuestion != null ? userQuestion : "");
                insert.setString(4, now);
                insert.executeUpdate();

                insert.setString(1, sessionId);
                insert.setString(2, "assistant");
                insert.setString(3, aiAnswer != null ? aiAnswer : "");
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
        List<Map<String, String>> result = new ArrayList<>();
        if (sessionId == null || sessionId.isBlank()) {
            return result;
        }

        int limit = maxMessages > 0 ? maxMessages : 100;
        try {
            try (PreparedStatement ps = getConnection().prepareStatement("""
                    SELECT role, content FROM (
                        SELECT id, role, content
                        FROM session_messages
                        WHERE session_id = ?
                        ORDER BY id DESC
                        LIMIT ?
                    ) ORDER BY id ASC
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
            }
        } catch (SQLException e) {
            logger.error("Failed to list messages for session {}", sessionId, e);
        }
        return result;
    }

    public void clearSessionMessages(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        String now = Instant.now().toString();
        try {
            Connection conn = getConnection();
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
        String now = Instant.now().toString();
        try {
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

            try (PreparedStatement ps = getConnection().prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                if (ps.executeUpdate() == 0) return null;
            }
        } catch (SQLException e) {
            logger.error("Failed to update session {}", sessionId, e);
        }
        return getSession(sessionId);
    }

    public boolean deleteSession(String sessionId) {
        try {
            clearSessionMessages(sessionId);
            try (PreparedStatement ps = getConnection().prepareStatement(
                    "DELETE FROM sessions WHERE id = ?")) {
                ps.setString(1, sessionId);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            logger.error("Failed to delete session {}", sessionId, e);
            return false;
        }
    }

    public List<Map<String, Object>> syncSessions(List<Map<String, Object>> sessions) {
        for (Map<String, Object> s : sessions) {
            String id = (String) s.get("id");
            if (id == null) continue;
            Map<String, Object> existing = getSession(id);
            if (existing == null) {
                createSession(id, (String) s.getOrDefault("title", "新对话"));
            } else {
                String payloadUpdated = (String) s.get("updated_at");
                String dbUpdated = (String) existing.get("updated_at");
                if (payloadUpdated != null && (dbUpdated == null || payloadUpdated.compareTo(dbUpdated) > 0)) {
                    updateSession(id, (String) s.get("title"),
                            s.get("message_count") instanceof Number n ? n.intValue() : null);
                }
            }
        }
        return listSessions(100, 0);
    }

    private Map<String, Object> rowToMap(ResultSet rs) throws SQLException {
        Map<String, Object> map = new HashMap<>();
        map.put("id", rs.getString("id"));
        map.put("title", rs.getString("title"));
        map.put("created_at", rs.getString("created_at"));
        map.put("updated_at", rs.getString("updated_at"));
        map.put("message_count", rs.getInt("message_count"));
        return map;
    }

    @PreDestroy
    public void close() {
        Connection conn = threadLocalConn.get();
        if (conn != null) {
            try { conn.close(); } catch (SQLException ignored) {}
            threadLocalConn.remove();
        }
    }
}
