package org.example.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MySQL-backed skill persistence service.
 */
@Service
public class SkillDBService {

    private static final Logger logger = LoggerFactory.getLogger(SkillDBService.class);
    private static final Gson gson = new Gson();

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final Object initLock = new Object();
    private volatile boolean initialized;

    public SkillDBService(
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
                        CREATE TABLE IF NOT EXISTS skills (
                            id VARCHAR(128) PRIMARY KEY,
                            name VARCHAR(255) NOT NULL,
                            description TEXT NOT NULL,
                            category VARCHAR(64) NOT NULL DEFAULT 'general',
                            source_type VARCHAR(64) NOT NULL DEFAULT 'manual',
                            tool_code LONGTEXT,
                            prompt_template LONGTEXT,
                            tool_chain_description LONGTEXT,
                            mcp_server_config LONGTEXT,
                            enabled TINYINT NOT NULL DEFAULT 1,
                            usage_count INT NOT NULL DEFAULT 0,
                            success_count INT NOT NULL DEFAULT 0,
                            tags TEXT,
                            created_at VARCHAR(64) NOT NULL,
                            updated_at VARCHAR(64) NOT NULL
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                        """);
                createIndexIfNeeded(stmt, "CREATE INDEX idx_skills_category ON skills(category)");
                createIndexIfNeeded(stmt, "CREATE INDEX idx_skills_enabled ON skills(enabled)");
                initialized = true;
                logger.info("Skill MySQL store initialized: {}", jdbcUrl);
            } catch (SQLException e) {
                logger.error("Failed to initialize skill MySQL store", e);
                throw new RuntimeException("Skill DB init failed", e);
            }
        }
    }

    public Map<String, Object> createSkill(
            String name, String description, String category, String sourceType,
            String toolCode, String promptTemplate, String toolChainDescription,
            Map<String, Object> mcpServerConfig, List<String> tags) {
        initialize();

        String skillId = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        String tagsJson = gson.toJson(tags == null ? List.of() : tags);
        String mcpJson = mcpServerConfig == null ? null : gson.toJson(mcpServerConfig);

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO skills (id, name, description, category, source_type, " +
                             "tool_code, prompt_template, tool_chain_description, mcp_server_config, " +
                             "enabled, usage_count, success_count, tags, created_at, updated_at) " +
                             "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 0, 0, ?, ?, ?)")) {
            ps.setString(1, skillId);
            ps.setString(2, name == null || name.isBlank() ? "未命名技能" : name);
            ps.setString(3, description == null ? "" : description);
            ps.setString(4, category == null || category.isBlank() ? "general" : category);
            ps.setString(5, sourceType == null || sourceType.isBlank() ? "manual" : sourceType);
            ps.setString(6, toolCode);
            ps.setString(7, promptTemplate);
            ps.setString(8, toolChainDescription);
            ps.setString(9, mcpJson);
            ps.setString(10, tagsJson);
            ps.setString(11, now);
            ps.setString(12, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to create skill", e);
            throw new RuntimeException("Skill creation failed", e);
        }
        return getSkill(skillId);
    }

    public Map<String, Object> getSkill(String skillId) {
        initialize();
        if (skillId == null || skillId.isBlank()) {
            return null;
        }
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM skills WHERE id = ?")) {
            ps.setString(1, skillId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rowToMap(rs) : null;
            }
        } catch (SQLException e) {
            logger.error("Failed to get skill {}", skillId, e);
            return null;
        }
    }

    public List<Map<String, Object>> listSkills(String category, boolean enabledOnly, int limit, int offset) {
        initialize();
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = getConnection()) {
            StringBuilder sql = new StringBuilder("SELECT * FROM skills");
            List<Object> params = new ArrayList<>();
            List<String> conditions = new ArrayList<>();

            if (category != null && !category.isBlank()) {
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
            params.add(Math.max(1, limit));
            params.add(Math.max(0, offset));

            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                bindParams(ps, params);
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
        initialize();
        try (Connection conn = getConnection()) {
            StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM skills");
            List<Object> params = new ArrayList<>();
            List<String> conditions = new ArrayList<>();

            if (category != null && !category.isBlank()) {
                conditions.add("category = ?");
                params.add(category);
            }
            if (enabledOnly) {
                conditions.add("enabled = 1");
            }
            if (!conditions.isEmpty()) {
                sql.append(" WHERE ").append(String.join(" AND ", conditions));
            }

            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                bindParams(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to count skills", e);
            return 0;
        }
    }

    public Map<String, Object> updateSkill(String skillId,
            String name, String description, String category,
            String toolCode, String promptTemplate, String toolChainDescription,
            Map<String, Object> mcpServerConfig, Boolean enabled, List<String> tags) {
        initialize();
        if (skillId == null || skillId.isBlank()) {
            return null;
        }

        StringBuilder sql = new StringBuilder("UPDATE skills SET updated_at = ?");
        List<Object> params = new ArrayList<>();
        params.add(Instant.now().toString());

        if (name != null) {
            sql.append(", name = ?");
            params.add(name);
        }
        if (description != null) {
            sql.append(", description = ?");
            params.add(description);
        }
        if (category != null) {
            sql.append(", category = ?");
            params.add(category);
        }
        if (toolCode != null) {
            sql.append(", tool_code = ?");
            params.add(toolCode);
        }
        if (promptTemplate != null) {
            sql.append(", prompt_template = ?");
            params.add(promptTemplate);
        }
        if (toolChainDescription != null) {
            sql.append(", tool_chain_description = ?");
            params.add(toolChainDescription);
        }
        if (mcpServerConfig != null) {
            sql.append(", mcp_server_config = ?");
            params.add(gson.toJson(mcpServerConfig));
        }
        if (enabled != null) {
            sql.append(", enabled = ?");
            params.add(enabled ? 1 : 0);
        }
        if (tags != null) {
            sql.append(", tags = ?");
            params.add(gson.toJson(tags));
        }
        sql.append(" WHERE id = ?");
        params.add(skillId);

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            bindParams(ps, params);
            if (ps.executeUpdate() == 0) {
                return null;
            }
        } catch (SQLException e) {
            logger.error("Failed to update skill {}", skillId, e);
            return null;
        }
        return getSkill(skillId);
    }

    public boolean deleteSkill(String skillId) {
        initialize();
        if (skillId == null || skillId.isBlank()) {
            return false;
        }
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM skills WHERE id = ?")) {
            ps.setString(1, skillId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.error("Failed to delete skill {}", skillId, e);
            return false;
        }
    }

    public void incrementUsage(String skillId) {
        initialize();
        updateCounters(skillId, false);
    }

    public void recordSuccess(String skillId) {
        initialize();
        updateCounters(skillId, true);
    }

    public List<Map<String, Object>> getEnabledSkills() {
        initialize();
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM skills WHERE enabled = 1 ORDER BY updated_at DESC")) {
            while (rs.next()) {
                result.add(rowToMap(rs));
            }
        } catch (SQLException e) {
            logger.error("Failed to get enabled skills", e);
        }
        return result;
    }

    private void updateCounters(String skillId, boolean success) {
        if (skillId == null || skillId.isBlank()) {
            return;
        }
        String sql = success
                ? "UPDATE skills SET usage_count = usage_count + 1, success_count = success_count + 1, updated_at = ? WHERE id = ?"
                : "UPDATE skills SET usage_count = usage_count + 1, updated_at = ? WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, Instant.now().toString());
            ps.setString(2, skillId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to update skill counters {}", skillId, e);
        }
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

    private void bindParams(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
        }
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
        map.put("tags", parseTags(rs.getString("tags")));

        String mcpConfig = rs.getString("mcp_server_config");
        if (mcpConfig != null && !mcpConfig.isBlank()) {
            try {
                map.put("mcp_server_config", gson.fromJson(mcpConfig, new TypeToken<Map<String, Object>>() {}.getType()));
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

    private List<String> parseTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) {
            return List.of();
        }
        try {
            return gson.fromJson(tagsJson, new TypeToken<List<String>>() {}.getType());
        } catch (Exception e) {
            return List.of();
        }
    }
}
