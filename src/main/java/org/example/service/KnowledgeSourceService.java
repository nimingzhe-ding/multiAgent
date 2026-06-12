package org.example.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.lang.reflect.Type;
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
import java.util.Locale;
import java.util.Map;

@Service
public class KnowledgeSourceService {

    public static final String VISIBILITY_PRIVATE = "private";
    public static final String VISIBILITY_SHARED = "shared";
    public static final String STATUS_INDEXED = "indexed";
    public static final String STATUS_INDEX_FAILED = "index_failed";
    public static final String STATUS_PENDING = "pending";

    private static final Gson GSON = new Gson();
    private static final Type STRING_LIST_TYPE = new TypeToken<List<String>>() {}.getType();

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final Object initLock = new Object();
    private volatile boolean initialized;

    public KnowledgeSourceService(
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
                        CREATE TABLE IF NOT EXISTS knowledge_sources (
                            id VARCHAR(128) PRIMARY KEY,
                            source_key VARCHAR(1024) NOT NULL,
                            name VARCHAR(512) NOT NULL,
                            owner_user_id VARCHAR(128) NULL,
                            visibility VARCHAR(32) NOT NULL DEFAULT 'shared',
                            category VARCHAR(128) NOT NULL DEFAULT 'general',
                            tags TEXT,
                            source_type VARCHAR(32) NOT NULL,
                            extension VARCHAR(32),
                            size BIGINT NOT NULL DEFAULT 0,
                            relative_path VARCHAR(1024),
                            index_status VARCHAR(64) NOT NULL DEFAULT 'pending',
                            index_message TEXT,
                            chunk_count INT NOT NULL DEFAULT 0,
                            created_at VARCHAR(64) NOT NULL,
                            updated_at VARCHAR(64) NOT NULL,
                            last_modified VARCHAR(64) NOT NULL,
                            UNIQUE KEY uq_knowledge_source_key (source_key(512))
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                        """);
                createIndexIfNeeded(stmt, "CREATE INDEX idx_knowledge_visibility ON knowledge_sources(visibility)");
                createIndexIfNeeded(stmt, "CREATE INDEX idx_knowledge_owner ON knowledge_sources(owner_user_id)");
                createIndexIfNeeded(stmt, "CREATE INDEX idx_knowledge_category ON knowledge_sources(category)");
                createIndexIfNeeded(stmt, "CREATE INDEX idx_knowledge_updated_at ON knowledge_sources(updated_at)");
                initialized = true;
            } catch (SQLException e) {
                throw new RuntimeException("Knowledge source DB init failed", e);
            }
        }
    }

    private void createIndexIfNeeded(Statement stmt, String sql) {
        try {
            stmt.execute(sql);
        } catch (SQLException ignored) {
            // MySQL raises duplicate-key errors for existing indexes; that is safe here.
        }
    }

    public List<Map<String, Object>> listSources(String userId, String visibility, String category, String tag, String query) {
        initialize();
        List<Map<String, Object>> result = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT * FROM knowledge_sources
                WHERE (visibility = 'shared' OR owner_user_id = ?)
                """);
        List<Object> params = new ArrayList<>();
        params.add(userId);

        String normalizedVisibility = normalizeVisibilityOrNull(visibility);
        if (normalizedVisibility != null) {
            sql.append(" AND visibility = ?");
            params.add(normalizedVisibility);
        }
        if (category != null && !category.isBlank()) {
            sql.append(" AND category = ?");
            params.add(category.trim());
        }
        if (tag != null && !tag.isBlank()) {
            sql.append(" AND tags LIKE ?");
            params.add("%" + tag.trim() + "%");
        }
        if (query != null && !query.isBlank()) {
            sql.append(" AND (LOWER(name) LIKE ? OR LOWER(source_key) LIKE ? OR LOWER(category) LIKE ? OR LOWER(tags) LIKE ?)");
            String like = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
            params.add(like);
            params.add(like);
            params.add(like);
            params.add(like);
        }
        sql.append(" ORDER BY updated_at DESC LIMIT 500");

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(rowToMap(rs));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new RuntimeException("list knowledge sources failed", e);
        }
    }

    public Map<String, Object> findVisibleByNameOrSource(String nameOrSource, String userId) {
        initialize();
        if (nameOrSource == null || nameOrSource.isBlank()) {
            return null;
        }
        String sql = """
                SELECT * FROM knowledge_sources
                WHERE (name = ? OR source_key = ? OR relative_path = ?)
                  AND (visibility = 'shared' OR owner_user_id = ?)
                LIMIT 1
                """;
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, nameOrSource);
            ps.setString(2, nameOrSource);
            ps.setString(3, nameOrSource);
            ps.setString(4, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rowToMap(rs) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("find knowledge source failed", e);
        }
    }

    public Map<String, Object> upsertSource(SourceInput input) {
        initialize();
        String now = Instant.now().toString();
        String id = stableId(input.sourceKey());
        String visibility = normalizeVisibility(input.visibility());
        List<String> tags = normalizeTags(input.tags());

        String sql = """
                INSERT INTO knowledge_sources
                  (id, source_key, name, owner_user_id, visibility, category, tags, source_type, extension, size,
                   relative_path, index_status, index_message, chunk_count, created_at, updated_at, last_modified)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                  name = VALUES(name),
                  owner_user_id = VALUES(owner_user_id),
                  visibility = VALUES(visibility),
                  category = VALUES(category),
                  tags = VALUES(tags),
                  source_type = VALUES(source_type),
                  extension = VALUES(extension),
                  size = VALUES(size),
                  relative_path = VALUES(relative_path),
                  index_status = VALUES(index_status),
                  index_message = VALUES(index_message),
                  chunk_count = VALUES(chunk_count),
                  updated_at = VALUES(updated_at),
                  last_modified = VALUES(last_modified)
                """;
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, input.sourceKey());
            ps.setString(3, input.name() == null || input.name().isBlank() ? input.sourceKey() : input.name());
            ps.setString(4, input.ownerUserId());
            ps.setString(5, visibility);
            ps.setString(6, input.category() == null || input.category().isBlank() ? "general" : input.category().trim());
            ps.setString(7, GSON.toJson(tags));
            ps.setString(8, input.sourceType());
            ps.setString(9, input.extension());
            ps.setLong(10, Math.max(0L, input.size()));
            ps.setString(11, input.relativePath());
            ps.setString(12, input.indexStatus() == null || input.indexStatus().isBlank() ? STATUS_PENDING : input.indexStatus());
            ps.setString(13, input.indexMessage());
            ps.setInt(14, Math.max(0, input.chunkCount()));
            ps.setString(15, now);
            ps.setString(16, now);
            ps.setString(17, input.lastModified() == null || input.lastModified().isBlank() ? now : input.lastModified());
            ps.executeUpdate();
            return findBySourceKey(input.sourceKey());
        } catch (SQLException e) {
            throw new RuntimeException("upsert knowledge source failed", e);
        }
    }

    public void updateIndexStatus(String sourceKey, String status, String message, int chunkCount) {
        initialize();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE knowledge_sources
                     SET index_status = ?, index_message = ?, chunk_count = ?, updated_at = ?
                     WHERE source_key = ?
                     """)) {
            ps.setString(1, status);
            ps.setString(2, message);
            ps.setInt(3, Math.max(0, chunkCount));
            ps.setString(4, Instant.now().toString());
            ps.setString(5, sourceKey);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("update knowledge index status failed", e);
        }
    }

    public boolean deleteIfOwnedOrShared(String nameOrSource, String userId) {
        Map<String, Object> source = findVisibleByNameOrSource(nameOrSource, userId);
        if (source == null) {
            return false;
        }
        Object owner = source.get("owner_user_id");
        Object visibility = source.get("visibility");
        boolean shared = VISIBILITY_SHARED.equals(visibility);
        boolean ownerMatches = owner == null || owner.toString().isBlank() || owner.equals(userId);
        if (!shared && !ownerMatches) {
            return false;
        }
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM knowledge_sources WHERE id = ?")) {
            ps.setString(1, source.get("id").toString());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("delete knowledge source failed", e);
        }
    }

    public Map<String, Object> findBySourceKey(String sourceKey) {
        initialize();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM knowledge_sources WHERE source_key = ?")) {
            ps.setString(1, sourceKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rowToMap(rs) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("find knowledge source by source key failed", e);
        }
    }

    public boolean isSourceVisible(String sourceKey, String userId) {
        if (sourceKey == null || sourceKey.isBlank()) {
            return true;
        }
        Map<String, Object> source = findBySourceKey(sourceKey);
        if (source == null) {
            return true;
        }
        return VISIBILITY_SHARED.equals(source.get("visibility")) || userId != null && userId.equals(source.get("owner_user_id"));
    }

    public static String normalizeVisibility(String visibility) {
        String value = visibility == null ? "" : visibility.trim().toLowerCase(Locale.ROOT);
        return VISIBILITY_PRIVATE.equals(value) ? VISIBILITY_PRIVATE : VISIBILITY_SHARED;
    }

    private String normalizeVisibilityOrNull(String visibility) {
        if (visibility == null || visibility.isBlank()) {
            return null;
        }
        return normalizeVisibility(visibility);
    }

    public static List<String> normalizeTags(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> tags = new ArrayList<>();
        for (String item : value.split(",")) {
            String tag = item.trim();
            if (!tag.isBlank() && tags.size() < 20) {
                tags.add(tag);
            }
        }
        return tags;
    }

    public static List<String> normalizeTags(List<String> value) {
        if (value == null) {
            return List.of();
        }
        List<String> tags = new ArrayList<>();
        for (String item : value) {
            if (item != null && !item.trim().isBlank() && tags.size() < 20) {
                tags.add(item.trim());
            }
        }
        return tags;
    }

    private void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object param = params.get(i);
            if (param instanceof Integer value) {
                ps.setInt(i + 1, value);
            } else {
                ps.setString(i + 1, param == null ? null : param.toString());
            }
        }
    }

    private String stableId(String sourceKey) {
        return java.util.UUID.nameUUIDFromBytes(sourceKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private Map<String, Object> rowToMap(ResultSet rs) throws SQLException {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", rs.getString("id"));
        map.put("source_key", rs.getString("source_key"));
        map.put("name", rs.getString("name"));
        map.put("owner_user_id", rs.getString("owner_user_id"));
        map.put("visibility", rs.getString("visibility"));
        map.put("category", rs.getString("category"));
        map.put("tags", parseTags(rs.getString("tags")));
        map.put("source_type", rs.getString("source_type"));
        map.put("extension", rs.getString("extension"));
        map.put("size", rs.getLong("size"));
        map.put("relative_path", rs.getString("relative_path"));
        map.put("index_status", rs.getString("index_status"));
        map.put("index_message", rs.getString("index_message"));
        map.put("chunk_count", rs.getInt("chunk_count"));
        map.put("created_at", rs.getString("created_at"));
        map.put("updated_at", rs.getString("updated_at"));
        map.put("last_modified", rs.getString("last_modified"));
        return map;
    }

    private List<String> parseTags(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> tags = GSON.fromJson(json, STRING_LIST_TYPE);
            return tags == null ? List.of() : tags;
        } catch (Exception e) {
            return List.of();
        }
    }

    public record SourceInput(
            String sourceKey,
            String name,
            String ownerUserId,
            String visibility,
            String category,
            List<String> tags,
            String sourceType,
            String extension,
            long size,
            String relativePath,
            String indexStatus,
            String indexMessage,
            int chunkCount,
            String lastModified) {
    }
}
