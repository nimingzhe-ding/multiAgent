package org.example.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
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
public class MemoryMonitoringService {

    private static final Logger logger = LoggerFactory.getLogger(MemoryMonitoringService.class);

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final FeishuNotificationService feishuNotificationService;
    private final AlertAnalysisOrchestratorService alertAnalysisOrchestratorService;
    private final Object initLock = new Object();
    private volatile boolean initialized;
    private int consecutiveHighCount;
    private boolean alertActive;
    private Instant lastAlertAt;

    public MemoryMonitoringService(
            @Value("${spring.datasource.url}") String jdbcUrl,
            @Value("${spring.datasource.username:}") String username,
            @Value("${spring.datasource.password:}") String password,
            FeishuNotificationService feishuNotificationService,
            AlertAnalysisOrchestratorService alertAnalysisOrchestratorService) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.feishuNotificationService = feishuNotificationService;
        this.alertAnalysisOrchestratorService = alertAnalysisOrchestratorService;
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
                        CREATE TABLE IF NOT EXISTS memory_monitoring_config (
                            id INT PRIMARY KEY,
                            enabled TINYINT NOT NULL DEFAULT 1,
                            warning_threshold DOUBLE NOT NULL DEFAULT 85,
                            recovery_threshold DOUBLE NOT NULL DEFAULT 75,
                            consecutive_limit INT NOT NULL DEFAULT 3,
                            cooldown_minutes INT NOT NULL DEFAULT 30,
                            updated_at VARCHAR(64) NOT NULL
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                        """);
                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS memory_alert_events (
                            id VARCHAR(64) PRIMARY KEY,
                            event_type VARCHAR(32) NOT NULL,
                            usage_percent DOUBLE NOT NULL,
                            used_memory BIGINT NOT NULL,
                            total_memory BIGINT NOT NULL,
                            threshold_percent DOUBLE NOT NULL,
                            message TEXT,
                            feishu_success TINYINT NOT NULL DEFAULT 0,
                            feishu_response TEXT,
                            created_at VARCHAR(64) NOT NULL
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                        """);
                createIndexIfNeeded(stmt, "CREATE INDEX idx_memory_alert_created_at ON memory_alert_events(created_at)");
                ensureDefaultConfig(conn);
                initialized = true;
            } catch (SQLException e) {
                throw new RuntimeException("Memory monitoring DB init failed", e);
            }
        }
    }

    private void createIndexIfNeeded(Statement stmt, String sql) {
        try {
            stmt.execute(sql);
        } catch (SQLException ignored) {
        }
    }

    private void ensureDefaultConfig(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM memory_monitoring_config WHERE id = 1");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return;
            }
        }
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO memory_monitoring_config
                  (id, enabled, warning_threshold, recovery_threshold, consecutive_limit, cooldown_minutes, updated_at)
                VALUES (1, 1, 85, 75, 3, 30, ?)
                """)) {
            ps.setString(1, Instant.now().toString());
            ps.executeUpdate();
        }
    }

    @Scheduled(fixedDelayString = "${monitoring.memory.check-interval-ms:60000}")
    public void scheduledCheck() {
        try {
            initialize();
            MemoryConfig config = getConfigRecord();
            if (!config.enabled()) {
                return;
            }
            MemorySnapshot snapshot = currentSnapshot();
            if (snapshot.usagePercent() >= config.warningThreshold()) {
                consecutiveHighCount++;
            } else {
                consecutiveHighCount = 0;
            }

            if (snapshot.usagePercent() >= config.warningThreshold()
                    && consecutiveHighCount >= config.consecutiveLimit()
                    && !alertActive
                    && cooldownElapsed(config.cooldownMinutes())) {
                Map<String, Object> event = recordEvent("alert", snapshot, config.warningThreshold(),
                        "Memory usage exceeded threshold");
                alertActive = true;
                lastAlertAt = Instant.now();
                sendFeishu(event);
                alertAnalysisOrchestratorService.submitMemoryAlert(event);
            }

            if (alertActive && snapshot.usagePercent() <= config.recoveryThreshold()) {
                Map<String, Object> event = recordEvent("recovered", snapshot, config.warningThreshold(),
                        "Memory usage recovered");
                alertActive = false;
                consecutiveHighCount = 0;
                sendFeishu(event);
            }
        } catch (Exception e) {
            logger.warn("Memory monitor check failed: {}", e.getMessage());
        }
    }

    public Map<String, Object> status() {
        initialize();
        MemoryConfig config = getConfigRecord();
        MemorySnapshot snapshot = currentSnapshot();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", config.enabled());
        result.put("warningThreshold", config.warningThreshold());
        result.put("recoveryThreshold", config.recoveryThreshold());
        result.put("consecutiveLimit", config.consecutiveLimit());
        result.put("cooldownMinutes", config.cooldownMinutes());
        result.put("usagePercent", snapshot.usagePercent());
        result.put("usedMemory", snapshot.usedMemory());
        result.put("totalMemory", snapshot.totalMemory());
        result.put("freeMemory", snapshot.freeMemory());
        result.put("alertActive", alertActive);
        result.put("consecutiveHighCount", consecutiveHighCount);
        return result;
    }

    public Map<String, Object> updateConfig(Map<String, Object> request) {
        initialize();
        MemoryConfig current = getConfigRecord();
        boolean enabled = request.containsKey("enabled") ? Boolean.parseBoolean(String.valueOf(request.get("enabled"))) : current.enabled();
        double warning = doubleValue(request.get("warningThreshold"), current.warningThreshold());
        double recovery = doubleValue(request.get("recoveryThreshold"), current.recoveryThreshold());
        int consecutive = intValue(request.get("consecutiveLimit"), current.consecutiveLimit());
        int cooldown = intValue(request.get("cooldownMinutes"), current.cooldownMinutes());
        warning = Math.max(1, Math.min(99, warning));
        recovery = Math.max(1, Math.min(warning - 1, recovery));
        consecutive = Math.max(1, Math.min(20, consecutive));
        cooldown = Math.max(1, Math.min(1440, cooldown));

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE memory_monitoring_config
                     SET enabled = ?, warning_threshold = ?, recovery_threshold = ?,
                         consecutive_limit = ?, cooldown_minutes = ?, updated_at = ?
                     WHERE id = 1
                     """)) {
            ps.setInt(1, enabled ? 1 : 0);
            ps.setDouble(2, warning);
            ps.setDouble(3, recovery);
            ps.setInt(4, consecutive);
            ps.setInt(5, cooldown);
            ps.setString(6, Instant.now().toString());
            ps.executeUpdate();
            return status();
        } catch (SQLException e) {
            throw new RuntimeException("update memory monitor config failed", e);
        }
    }

    public List<Map<String, Object>> listEvents() {
        initialize();
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT * FROM memory_alert_events
                     ORDER BY created_at DESC
                     LIMIT 100
                     """);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(eventRow(rs));
            }
            return result;
        } catch (SQLException e) {
            throw new RuntimeException("list memory alert events failed", e);
        }
    }

    private boolean cooldownElapsed(int cooldownMinutes) {
        return lastAlertAt == null || Instant.now().isAfter(lastAlertAt.plusSeconds(cooldownMinutes * 60L));
    }

    private void sendFeishu(Map<String, Object> event) {
        FeishuNotificationService.SendResult result = feishuNotificationService.sendMemoryAlert(event);
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE memory_alert_events
                     SET feishu_success = ?, feishu_response = ?
                     WHERE id = ?
                     """)) {
            ps.setInt(1, result.success() ? 1 : 0);
            ps.setString(2, result.message());
            ps.setString(3, event.get("id").toString());
            ps.executeUpdate();
        } catch (Exception e) {
            logger.warn("Failed to update Feishu result for memory alert: {}", e.getMessage());
        }
    }

    private Map<String, Object> recordEvent(String eventType, MemorySnapshot snapshot, double threshold, String message) {
        String id = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO memory_alert_events
                       (id, event_type, usage_percent, used_memory, total_memory, threshold_percent, message, created_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            ps.setString(1, id);
            ps.setString(2, eventType);
            ps.setDouble(3, snapshot.usagePercent());
            ps.setLong(4, snapshot.usedMemory());
            ps.setLong(5, snapshot.totalMemory());
            ps.setDouble(6, threshold);
            ps.setString(7, message);
            ps.setString(8, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("record memory alert event failed", e);
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", id);
        event.put("event_type", eventType);
        event.put("usage_percent", snapshot.usagePercent());
        event.put("used_memory", snapshot.usedMemory());
        event.put("total_memory", snapshot.totalMemory());
        event.put("threshold_percent", threshold);
        event.put("message", message);
        event.put("created_at", now);
        return event;
    }

    private MemoryConfig getConfigRecord() {
        initialize();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM memory_monitoring_config WHERE id = 1");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                throw new IllegalStateException("memory monitor config is missing");
            }
            return new MemoryConfig(
                    rs.getInt("enabled") == 1,
                    rs.getDouble("warning_threshold"),
                    rs.getDouble("recovery_threshold"),
                    rs.getInt("consecutive_limit"),
                    rs.getInt("cooldown_minutes"));
        } catch (SQLException e) {
            throw new RuntimeException("load memory monitor config failed", e);
        }
    }

    private MemorySnapshot currentSnapshot() {
        com.sun.management.OperatingSystemMXBean osBean =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        long total = osBean.getTotalMemorySize();
        long free = osBean.getFreeMemorySize();
        long used = Math.max(0, total - free);
        double usage = total <= 0 ? 0.0d : used * 100.0d / total;
        return new MemorySnapshot(total, free, used, Math.round(usage * 100.0d) / 100.0d);
    }

    private Map<String, Object> eventRow(ResultSet rs) throws SQLException {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", rs.getString("id"));
        map.put("event_type", rs.getString("event_type"));
        map.put("usage_percent", rs.getDouble("usage_percent"));
        map.put("used_memory", rs.getLong("used_memory"));
        map.put("total_memory", rs.getLong("total_memory"));
        map.put("threshold_percent", rs.getDouble("threshold_percent"));
        map.put("message", rs.getString("message"));
        map.put("feishu_success", rs.getInt("feishu_success") == 1);
        map.put("feishu_response", rs.getString("feishu_response"));
        map.put("created_at", rs.getString("created_at"));
        return map;
    }

    private double doubleValue(Object value, double fallback) {
        try {
            return value == null ? fallback : Double.parseDouble(String.valueOf(value));
        } catch (Exception e) {
            return fallback;
        }
    }

    private int intValue(Object value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return fallback;
        }
    }

    public record MemoryConfig(boolean enabled, double warningThreshold, double recoveryThreshold,
                               int consecutiveLimit, int cooldownMinutes) {
    }

    public record MemorySnapshot(long totalMemory, long freeMemory, long usedMemory, double usagePercent) {
    }
}
