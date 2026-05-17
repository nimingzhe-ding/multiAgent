package org.example.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * Thread-safe SQLite skill persistence service.
 * Shares the same SQLite database file with SessionDBService.
 */
@Service
public class SkillDBService {

    private static final Logger logger = LoggerFactory.getLogger(SkillDBService.class);
    private static final Gson gson = new Gson();

    private final String dbPath;
    private final ThreadLocal<Connection> threadLocalConn = new ThreadLocal<>();
    private final Object initLock = new Object();
    private volatile boolean initialized;

    public SkillDBService(@Value("${sqlite.db-path:./data/sessions.db}") String dbPath) {
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
                java.io.File dbFile = new java.io.File(dbPath);
                java.io.File parent = dbFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }

                try (Statement stmt = getConnection().createStatement()) {
                    stmt.execute("""
                        CREATE TABLE IF NOT EXISTS skills (
                            id TEXT PRIMARY KEY,
                            name TEXT NOT NULL,
                            description TEXT NOT NULL DEFAULT '',
                            category TEXT NOT NULL DEFAULT 'general',
                            source_type TEXT NOT NULL DEFAULT 'manual',
                            tool_code TEXT,
                            prompt_template TEXT,
                            tool_chain_description TEXT,
                            mcp_server_config TEXT,
                            enabled INTEGER NOT NULL DEFAULT 1,
                            usage_count INTEGER NOT NULL DEFAULT 0,
                            success_count INTEGER NOT NULL DEFAULT 0,
                            tags TEXT,
                            created_at TEXT NOT NULL,
                            updated_at TEXT NOT NULL
                        )
                    """);
                    stmt.execute("""
                        CREATE INDEX IF NOT EXISTS idx_skills_category ON skills(category)
                    """);
                    stmt.execute("""
                        CREATE INDEX IF NOT EXISTS idx_skills_enabled ON skills(enabled)
                    """);
                }
                initialized = true;
                logger.info("Skill DB initialized at {}", dbPath);
            } catch (SQLException e) {
                logger.error("Failed to initialize skill DB", e);
                throw new RuntimeException("Skill DB init failed", e);
            }
        }
    }

    public Map<String, Object> createSkill(
            String name, String description, String category, String sourceType,
            String toolCode, String promptTemplate, String toolChainDescription,
            Map<String, Object> mcpServerConfig, List<String> tags) {
        String skillId = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        String tagsJson = gson.toJson(tags != null ? tags : List.of());
        String mcpJson = mcpServerConfig != null ? gson.toJson(mcpServerConfig) : null;

        try {
            try (PreparedStatement ps = getConnection().prepareStatement(
                    "INSERT INTO skills (id, name, description, category, source_type, " +
                    "tool_code, prompt_template, tool_chain_description, mcp_server_config, " +
                    "enabled, usage_count, success_count, tags, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 0, 0, ?, ?, ?)")) {
                ps.setString(1, skillId);
                ps.setString(2, name);
                ps.setString(3, description != null ? description : "");
                ps.setString(4, category != null ? category : "general");
                ps.setString(5, sourceType != null ? sourceType : "manual");
                ps.setString(6, toolCode);
                ps.setString(7, promptTemplate);
                ps.setString(8, toolChainDescription);
                ps.setString(9, mcpJson);
                ps.setString(10, tagsJson);
                ps.setString(11, now);
                ps.setString(12, now);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.error("Failed to create skill", e);
            throw new RuntimeException("Skill creation failed", e);
        }
        return getSkill(skillId);
    }

    public Map<String, Object> getSkill(String skillId) {
        try {
            try (PreparedStatement ps = getConnection().prepareStatement(
                    "SELECT * FROM skills WHERE id = ?")) {
                ps.setString(1, skillId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rowToMap(rs);
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to get skill {}", skillId, e);
        }
        return null;
    }

    public List<Map<String, Object>> listSkills(String category, boolean enabledOnly, int limit, int offset) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            StringBuilder sql = new StringBuilder("SELECT * FROM skills");
            List<Object> params = new ArrayList<>();

            List<String> conditions = new ArrayList<>();
            if (category != null && !category.isEmpty()) {
                conditions.add("category = ?");
                params.add(category);
            }
            if (enabledOnly) {
                conditions.add("enabled = 1");
            }
            if (!conditions.isEmpty()) {
                sql.append(" WHERE ").append(String.join(" AND ", conditions));
            }
            sql.append(" ORDER BY updated_at DESC LIMIT ? OFFSET ?");
            params.add(limit);
            params.add(offset);

            try (PreparedStatement ps = getConnection().prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.add(rowToMap(rs));
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to list skills", e);
        }
        return result;
    }

    public int countSkills(String category, boolean enabledOnly) {
        try {
            List<String> conditions = new ArrayList<>();
            List<Object> params = new ArrayList<>();
            if (category != null && !category.isEmpty()) {
                conditions.add("category = ?");
                params.add(category);
            }
            if (enabledOnly) {
                conditions.add("enabled = 1");
            }
            String sql = "SELECT COUNT(*) FROM skills";
            if (!conditions.isEmpty()) {
                sql += " WHERE " + String.join(" AND ", conditions);
            }

            try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to count skills", e);
        }
        return 0;
    }

    public Map<String, Object> updateSkill(String skillId,
            String name, String description, String category,
            String toolCode, String promptTemplate, String toolChainDescription,
            Map<String, Object> mcpServerConfig, Boolean enabled, List<String> tags) {
        String now = Instant.now().toString();
        try {
            StringBuilder sql = new StringBuilder("UPDATE skills SET updated_at = ?");
            List<Object> values = new ArrayList<>();
            values.add(now);

            if (name != null) { sql.append(", name = ?"); values.add(name); }
            if (description != null) { sql.append(", description = ?"); values.add(description); }
            if (category != null) { sql.append(", category = ?"); values.add(category); }
            if (toolCode != null) { sql.append(", tool_code = ?"); values.add(toolCode); }
            if (promptTemplate != null) { sql.append(", prompt_template = ?"); values.add(promptTemplate); }
            if (toolChainDescription != null) { sql.append(", tool_chain_description = ?"); values.add(toolChainDescription); }
            if (mcpServerConfig != null) { sql.append(", mcp_server_config = ?"); values.add(gson.toJson(mcpServerConfig)); }
            if (enabled != null) { sql.append(", enabled = ?"); values.add(enabled ? 1 : 0); }
            if (tags != null) { sql.append(", tags = ?"); values.add(gson.toJson(tags)); }

            sql.append(" WHERE id = ?");
            values.add(skillId);

            try (PreparedStatement ps = getConnection().prepareStatement(sql.toString())) {
                for (int i = 0; i < values.size(); i++) {
                    ps.setObject(i + 1, values.get(i));
                }
                if (ps.executeUpdate() == 0) return null;
            }
        } catch (SQLException e) {
            logger.error("Failed to update skill {}", skillId, e);
            return null;
        }
        return getSkill(skillId);
    }

    public boolean deleteSkill(String skillId) {
        try {
            try (PreparedStatement ps = getConnection().prepareStatement(
                    "DELETE FROM skills WHERE id = ?")) {
                ps.setString(1, skillId);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            logger.error("Failed to delete skill {}", skillId, e);
            return false;
        }
    }

    public void incrementUsage(String skillId) {
        String now = Instant.now().toString();
        try {
            try (PreparedStatement ps = getConnection().prepareStatement(
                    "UPDATE skills SET usage_count = usage_count + 1, updated_at = ? WHERE id = ?")) {
                ps.setString(1, now);
                ps.setString(2, skillId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.error("Failed to increment usage for skill {}", skillId, e);
        }
    }

    public void recordSuccess(String skillId) {
        String now = Instant.now().toString();
        try {
            try (PreparedStatement ps = getConnection().prepareStatement(
                    "UPDATE skills SET success_count = success_count + 1, " +
                    "usage_count = usage_count + 1, updated_at = ? WHERE id = ?")) {
                ps.setString(1, now);
                ps.setString(2, skillId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.error("Failed to record success for skill {}", skillId, e);
        }
    }

    public List<Map<String, Object>> getEnabledSkills() {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            try (Statement stmt = getConnection().createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT * FROM skills WHERE enabled = 1 ORDER BY updated_at DESC")) {
                while (rs.next()) {
                    result.add(rowToMap(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to get enabled skills", e);
        }
        return result;
    }

    private Map<String, Object> rowToMap(ResultSet rs) throws SQLException {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", rs.getString("id"));
        map.put("name", rs.getString("name"));
        map.put("description", rs.getString("description"));
        map.put("category", rs.getString("category"));
        map.put("source_type", rs.getString("source_type"));
        map.put("tool_code", rs.getString("tool_code"));
        map.put("prompt_template", rs.getString("prompt_template"));
        map.put("tool_chain_description", rs.getString("tool_chain_description"));
        map.put("enabled", rs.getInt("enabled") == 1);
        map.put("usage_count", rs.getInt("usage_count"));
        map.put("success_count", rs.getInt("success_count"));

        // Parse JSON fields
        String tagsStr = rs.getString("tags");
        if (tagsStr != null && !tagsStr.isEmpty()) {
            try {
                map.put("tags", gson.fromJson(tagsStr, new TypeToken<List<String>>(){}.getType()));
            } catch (Exception e) {
                map.put("tags", List.of());
            }
        } else {
            map.put("tags", List.of());
        }

        String mcpStr = rs.getString("mcp_server_config");
        if (mcpStr != null && !mcpStr.isEmpty()) {
            try {
                map.put("mcp_server_config", gson.fromJson(mcpStr, new TypeToken<Map<String, Object>>(){}.getType()));
            } catch (Exception e) {
                map.put("mcp_server_config", null);
            }
        }

        int usage = rs.getInt("usage_count");
        int success = rs.getInt("success_count");
        map.put("success_rate", usage > 0 ? Math.round(success * 100.0 / usage) / 100.0 : 0.0);

        map.put("created_at", rs.getString("created_at"));
        map.put("updated_at", rs.getString("updated_at"));
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
