package org.example.controller;

import org.example.service.MemoryMonitoringService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class MonitoringController {

    private final MemoryMonitoringService memoryMonitoringService;

    public MonitoringController(MemoryMonitoringService memoryMonitoringService) {
        this.memoryMonitoringService = memoryMonitoringService;
    }

    @GetMapping("/monitoring/memory/status")
    public ResponseEntity<Map<String, Object>> memoryStatus() {
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "success",
                "data", memoryMonitoringService.status()
        ));
    }

    @GetMapping("/monitoring/memory/config")
    public ResponseEntity<Map<String, Object>> memoryConfig() {
        return memoryStatus();
    }

    @PutMapping("/monitoring/memory/config")
    public ResponseEntity<Map<String, Object>> updateMemoryConfig(@RequestBody Map<String, Object> request) {
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "success",
                "data", memoryMonitoringService.updateConfig(request)
        ));
    }

    @GetMapping("/alerts/memory")
    public ResponseEntity<Map<String, Object>> memoryAlerts() {
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "success",
                "data", memoryMonitoringService.listEvents()
        ));
    }
}
