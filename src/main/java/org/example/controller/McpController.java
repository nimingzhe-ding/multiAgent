package org.example.controller;

import org.example.service.McpConnectionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/mcp")
public class McpController {

    private final McpConnectionService mcpConnectionService;

    public McpController(McpConnectionService mcpConnectionService) {
        this.mcpConnectionService = mcpConnectionService;
    }

    @GetMapping("/connections")
    public ResponseEntity<Map<String, Object>> listConnections() {
        return ok(mcpConnectionService.listConnections());
    }

    @PostMapping("/connections")
    public ResponseEntity<Map<String, Object>> createConnection(@RequestBody Map<String, Object> request) {
        return ok(mcpConnectionService.saveConnection(null, request));
    }

    @PutMapping("/connections/{id}")
    public ResponseEntity<Map<String, Object>> updateConnection(@PathVariable String id,
                                                                @RequestBody Map<String, Object> request) {
        return ok(mcpConnectionService.saveConnection(id, request));
    }

    @DeleteMapping("/connections/{id}")
    public ResponseEntity<Map<String, Object>> deleteConnection(@PathVariable String id) {
        boolean deleted = mcpConnectionService.deleteConnection(id);
        if (!deleted) {
            return ResponseEntity.status(404).body(Map.of("code", 404, "message", "connection not found"));
        }
        return ok(Map.of("deleted", true));
    }

    @PostMapping("/connections/{id}/validate")
    public ResponseEntity<Map<String, Object>> validateConnection(@PathVariable String id) {
        return ok(mcpConnectionService.validateConnection(id));
    }

    @GetMapping("/config-snippet")
    public ResponseEntity<Map<String, Object>> configSnippet() {
        return ok(Map.of("yaml", mcpConnectionService.configSnippet()));
    }

    private ResponseEntity<Map<String, Object>> ok(Object data) {
        return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", data));
    }
}
