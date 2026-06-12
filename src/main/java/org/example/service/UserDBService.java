package org.example.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class UserDBService {

    private static final Logger logger = LoggerFactory.getLogger(UserDBService.class);

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final PasswordEncoder passwordEncoder;
    private final boolean defaultUserEnabled;
    private final String defaultUsername;
    private final String defaultPassword;
    private final String defaultDisplayName;
    private final Object initLock = new Object();
    private volatile boolean initialized;

    public UserDBService(
            @Value("${spring.datasource.url}") String jdbcUrl,
            @Value("${spring.datasource.username:}") String username,
            @Value("${spring.datasource.password:}") String password,
            PasswordEncoder passwordEncoder,
            @Value("${auth.default-user.enabled:true}") boolean defaultUserEnabled,
            @Value("${auth.default-user.username:admin}") String defaultUsername,
            @Value("${auth.default-user.password:admin123}") String defaultPassword,
            @Value("${auth.default-user.display-name:Admin}") String defaultDisplayName) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.passwordEncoder = passwordEncoder;
        this.defaultUserEnabled = defaultUserEnabled;
        this.defaultUsername = defaultUsername;
        this.defaultPassword = defaultPassword;
        this.defaultDisplayName = defaultDisplayName;
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
                        CREATE TABLE IF NOT EXISTS app_users (
                            id VARCHAR(64) PRIMARY KEY,
                            username VARCHAR(64) NOT NULL UNIQUE,
                            password_hash VARCHAR(255) NOT NULL,
                            display_name VARCHAR(128) NOT NULL,
                            created_at VARCHAR(64) NOT NULL,
                            updated_at VARCHAR(64) NOT NULL
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                        """);
                initialized = true;
                logger.info("User MySQL store initialized: {}", jdbcUrl);
            } catch (SQLException e) {
                logger.error("Failed to initialize user MySQL store", e);
                throw new RuntimeException("User DB init failed", e);
            }
        }

        if (defaultUserEnabled) {
            ensureDefaultUser();
        }
    }

    public Map<String, Object> register(String requestedUsername, String rawPassword, String displayName) {
        initialize();
        String normalizedUsername = normalizeUsername(requestedUsername);
        validatePassword(rawPassword);
        if (findByUsername(normalizedUsername) != null) {
            throw new IllegalArgumentException("Username already exists");
        }

        String now = Instant.now().toString();
        String userId = UUID.randomUUID().toString();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO app_users (id, username, password_hash, display_name, created_at, updated_at)
                     VALUES (?, ?, ?, ?, ?, ?)
                     """)) {
            ps.setString(1, userId);
            ps.setString(2, normalizedUsername);
            ps.setString(3, passwordEncoder.encode(rawPassword));
            ps.setString(4, displayName == null || displayName.isBlank() ? normalizedUsername : displayName.trim());
            ps.setString(5, now);
            ps.setString(6, now);
            ps.executeUpdate();
            return findByUsername(normalizedUsername);
        } catch (SQLException e) {
            logger.error("Failed to register user {}", normalizedUsername, e);
            throw new RuntimeException("register user failed", e);
        }
    }

    public Map<String, Object> authenticate(String requestedUsername, String rawPassword) {
        initialize();
        String normalizedUsername = normalizeUsername(requestedUsername);
        Map<String, Object> user = findByUsername(normalizedUsername);
        if (user == null) {
            throw new IllegalArgumentException("Invalid username or password");
        }
        String passwordHash = (String) user.get("password_hash");
        if (!passwordEncoder.matches(rawPassword == null ? "" : rawPassword, passwordHash)) {
            throw new IllegalArgumentException("Invalid username or password");
        }
        return publicUser(user);
    }

    public Map<String, Object> findByUsername(String requestedUsername) {
        initialize();
        String normalizedUsername = normalizeUsername(requestedUsername);
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT id, username, password_hash, display_name, created_at, updated_at
                     FROM app_users WHERE username = ?
                     """)) {
            ps.setString(1, normalizedUsername);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rowToMap(rs) : null;
            }
        } catch (SQLException e) {
            logger.error("Failed to find user {}", normalizedUsername, e);
            throw new RuntimeException("find user failed", e);
        }
    }

    public Map<String, Object> publicUser(Map<String, Object> user) {
        if (user == null) {
            return null;
        }
        Map<String, Object> result = new HashMap<>();
        result.put("id", user.get("id"));
        result.put("username", user.get("username"));
        result.put("displayName", user.get("display_name"));
        result.put("createdAt", user.get("created_at"));
        return result;
    }

    private void ensureDefaultUser() {
        try {
            if (findByUsername(defaultUsername) == null) {
                register(defaultUsername, defaultPassword, defaultDisplayName);
                logger.warn("Created default local user '{}'. Change AUTH_DEFAULT_PASSWORD before sharing this app.", defaultUsername);
            }
        } catch (Exception e) {
            logger.error("Failed to ensure default user", e);
        }
    }

    private String normalizeUsername(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Username cannot be empty");
        }
        String normalized = value.trim().toLowerCase();
        if (!normalized.matches("[a-z0-9_\\-.]{3,64}")) {
            throw new IllegalArgumentException("Username must be 3-64 characters and only contain letters, numbers, _, - or .");
        }
        return normalized;
    }

    private void validatePassword(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < 6) {
            throw new IllegalArgumentException("Password must be at least 6 characters");
        }
        if (rawPassword.length() > 128) {
            throw new IllegalArgumentException("Password is too long");
        }
    }

    private Map<String, Object> rowToMap(ResultSet rs) throws SQLException {
        Map<String, Object> map = new HashMap<>();
        map.put("id", rs.getString("id"));
        map.put("username", rs.getString("username"));
        map.put("password_hash", rs.getString("password_hash"));
        map.put("display_name", rs.getString("display_name"));
        map.put("created_at", rs.getString("created_at"));
        map.put("updated_at", rs.getString("updated_at"));
        return map;
    }
}
