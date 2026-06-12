package org.example.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.lang.reflect.Type;
import java.net.URI;
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

@Service
public class McpConnectionService {

    private static final Gson GSON = new Gson();
    private static final Type STRING_LIST_TYPE = new TypeToken<List<String>>() {}.getType();
    private static final Type STRING_MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final Object initLock = new Object();
    private volatile boolean initialized;

    public McpConnectionService(
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
                        CREATE TABLE IF NOT EXISTS mcp_connections (
                            id VARCHAR(64) PRIMARY KEY,
                            name VARCHAR(128) NOT NULL UNIQUE,
                            transport VARCHAR(32) NOT NULL,
                            url VARCHAR(1024),
                            endpoint VARCHAR(256),
                            command_text VARCHAR(1024),
                            args_json TEXT,
                            env_json TEXT,
                            enabled TINYINT NOT NULL DEFAULT 1,
                            created_at VARCHAR(64) NOT NULL,
                            updated_at VARCHAR(64) NOT NULL
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                        """);
                initialized = true;
            } catch (SQLException e) {
                throw new RuntimeException("MCP connection DB init failed", e);
            }
        }
    }

    public List<Map<String, Object>> listConnections() {
        initialize();
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM mcp_connections ORDER BY updated_at DESC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(rowToMap(rs));
            }
            return result;
        } catch (SQLException e) {
            throw new RuntimeException("list MCP connections failed", e);
        }
    }

    public Map<String, Object> saveConnection(String id, Map<String, Object> request) {
        initialize();
        String connectionId = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        String name = normalizeName(request.get("name"));
        String transport = normalizeTransport(request.get("transport"));
        String url = stringValue(request.get("url"));
        String endpoint = stringValue(request.get("endpoint"));
        String command = stringValue(request.get("command"));
        List<String> args = listValue(request.get("args"));
        Map<String, String> env = mapValue(request.get("env"));
        boolean enabled = !request.containsKey("enabled") || Boolean.parseBoolean(String.valueOf(request.get("enabled")));
        validate(new McpInput(name, transport, url, endpoint, command, args, env, enabled));
        String now = Instant.now().toString();

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO mcp_connections
                       (id, name, transport, url, endpoint, command_text, args_json, env_json, enabled, created_at, updated_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     ON DUPLICATE KEY UPDATE
                       name = VALUES(name),
                       transport = VALUES(transport),
                       url = VALUES(url),
                       endpoint = VALUES(endpoint),
                       command_text = VALUES(command_text),
                       args_json = VALUES(args_json),
                       env_json = VALUES(env_json),
                       enabled = VALUES(enabled),
                       updated_at = VALUES(updated_at)
                     """)) {
            ps.setString(1, connectionId);
            ps.setString(2, name);
            ps.setString(3, transport);
            ps.setString(4, url);
            ps.setString(5, endpoint);
            ps.setString(6, command);
            ps.setString(7, GSON.toJson(args));
            ps.setString(8, GSON.toJson(env));
            ps.setInt(9, enabled ? 1 : 0);
            ps.setString(10, now);
            ps.setString(11, now);
            ps.executeUpdate();
            return getConnectionById(connectionId);
        } catch (SQLException e) {
            throw new RuntimeException("save MCP connection failed", e);
        }
    }

    public boolean deleteConnection(String id) {
        initialize();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM mcp_connections WHERE id = ?")) {
            ps.setString(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("delete MCP connection failed", e);
        }
    }

    public Map<String, Object> validateConnection(String id) {
        Map<String, Object> connection = getConnectionById(id);
        if (connection == null) {
            return Map.of("valid", false, "message", "connection not found");
        }
        try {
            @SuppressWarnings("unchecked")
            List<String> args = (List<String>) connection.getOrDefault("args", List.of());
            @SuppressWarnings("unchecked")
            Map<String, String> env = (Map<String, String>) connection.getOrDefault("env", Map.of());
            validate(new McpInput(
                    connection.get("name").toString(),
                    connection.get("transport").toString(),
                    stringValue(connection.get("url")),
                    stringValue(connection.get("endpoint")),
                    stringValue(connection.get("command")),
                    args,
                    env,
                    Boolean.TRUE.equals(connection.get("enabled"))));
            return Map.of("valid", true, "message", "configuration shape is valid; restart app to activate");
        } catch (RuntimeException e) {
            return Map.of("valid", false, "message", e.getMessage());
        }
    }

    public String configSnippet() {
        List<Map<String, Object>> connections = listConnections().stream()
                .filter(item -> Boolean.TRUE.equals(item.get("enabled")))
                .toList();
        StringBuilder sb = new StringBuilder();
        sb.append("spring:\n  ai:\n    mcp:\n      client:\n");
        sb.append("        enabled: true\n");
        sb.append("        name: super-biz-agent\n");
        sb.append("        version: 1.0.0\n");
        sb.append("        request-timeout: 60s\n");
        sb.append("        type: ASYNC\n");

        appendHttpConnections(sb, connections, "sse");
        appendHttpConnections(sb, connections, "streamable-http");
        appendStdioConnections(sb, connections);
        return sb.toString();
    }

    private void appendHttpConnections(StringBuilder sb, List<Map<String, Object>> connections, String transport) {
        List<Map<String, Object>> filtered = connections.stream()
                .filter(item -> transport.equals(item.get("transport")))
                .toList();
        if (filtered.isEmpty()) {
            return;
        }
        sb.append("        ").append(transport).append(":\n");
        sb.append("          connections:\n");
        for (Map<String, Object> item : filtered) {
            sb.append("            ").append(item.get("name")).append(":\n");
            sb.append("              url: ").append(item.get("url")).append("\n");
            if (item.get("endpoint") != null && !item.get("endpoint").toString().isBlank()) {
                sb.append("              endpoint: ").append(item.get("endpoint")).append("\n");
            }
        }
    }

    private void appendStdioConnections(StringBuilder sb, List<Map<String, Object>> connections) {
        List<Map<String, Object>> filtered = connections.stream()
                .filter(item -> "stdio".equals(item.get("transport")))
                .toList();
        if (filtered.isEmpty()) {
            return;
        }
        sb.append("        stdio:\n");
        sb.append("          root-change-notification: false\n");
        sb.append("          connections:\n");
        for (Map<String, Object> item : filtered) {
            sb.append("            ").append(item.get("name")).append(":\n");
            sb.append("              command: ").append(item.get("command")).append("\n");
            @SuppressWarnings("unchecked")
            List<String> args = (List<String>) item.getOrDefault("args", List.of());
            if (!args.isEmpty()) {
                sb.append("              args:\n");
                for (String arg : args) {
                    sb.append("                - ").append(arg).append("\n");
                }
            }
            @SuppressWarnings("unchecked")
            Map<String, String> env = (Map<String, String>) item.getOrDefault("env", Map.of());
            if (!env.isEmpty()) {
                sb.append("              env:\n");
                for (Map.Entry<String, String> entry : env.entrySet()) {
                    sb.append("                ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                }
            }
        }
    }

    private Map<String, Object> getConnectionById(String id) {
        initialize();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM mcp_connections WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rowToMap(rs) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("get MCP connection failed", e);
        }
    }

    private void validate(McpInput input) {
        if (input.name().isBlank() || !input.name().matches("[a-zA-Z0-9_.-]{2,64}")) {
            throw new IllegalArgumentException("name must be 2-64 characters and only contain letters, numbers, _, - or .");
        }
        if ("sse".equals(input.transport()) || "streamable-http".equals(input.transport())) {
            if (input.url().isBlank()) {
                throw new IllegalArgumentException("url is required for " + input.transport());
            }
            URI uri = URI.create(input.url());
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("url must start with http:// or https://");
            }
        } else if ("stdio".equals(input.transport())) {
            if (input.command().isBlank()) {
                throw new IllegalArgumentException("command is required for stdio");
            }
        } else {
            throw new IllegalArgumentException("unsupported transport: " + input.transport());
        }
    }

    private String normalizeName(Object value) {
        return stringValue(value).trim();
    }

    private String normalizeTransport(Object value) {
        String transport = stringValue(value).trim().toLowerCase();
        return transport.isBlank() ? "sse" : transport;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private List<String> listValue(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).filter(item -> !item.isBlank()).toList();
        }
        String raw = stringValue(value);
        if (raw.isBlank()) {
            return List.of();
        }
        try {
            List<String> parsed = GSON.fromJson(raw, STRING_LIST_TYPE);
            return parsed == null ? List.of() : parsed;
        } catch (Exception e) {
            List<String> result = new ArrayList<>();
            for (String part : raw.split("\\s+")) {
                if (!part.isBlank()) {
                    result.add(part);
                }
            }
            return result;
        }
    }

    private Map<String, String> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, String> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            }
            return result;
        }
        String raw = stringValue(value);
        if (raw.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> parsed = GSON.fromJson(raw, STRING_MAP_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Map<String, Object> rowToMap(ResultSet rs) throws SQLException {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", rs.getString("id"));
        map.put("name", rs.getString("name"));
        map.put("transport", rs.getString("transport"));
        map.put("url", rs.getString("url"));
        map.put("endpoint", rs.getString("endpoint"));
        map.put("command", rs.getString("command_text"));
        map.put("args", parseList(rs.getString("args_json")));
        map.put("env", parseMap(rs.getString("env_json")));
        map.put("enabled", rs.getInt("enabled") == 1);
        map.put("created_at", rs.getString("created_at"));
        map.put("updated_at", rs.getString("updated_at"));
        return map;
    }

    private List<String> parseList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<String> parsed = GSON.fromJson(json, STRING_LIST_TYPE);
        return parsed == null ? List.of() : parsed;
    }

    private Map<String, String> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Map<String, String> parsed = GSON.fromJson(json, STRING_MAP_TYPE);
        return parsed == null ? Map.of() : parsed;
    }

    private record McpInput(String name, String transport, String url, String endpoint,
                            String command, List<String> args, Map<String, String> env, boolean enabled) {
    }
}
