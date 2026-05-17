package org.example.controller;

import org.example.service.SessionDBService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SessionController {

    private static final Logger logger = LoggerFactory.getLogger(SessionController.class);

    @Autowired
    private SessionDBService sessionDBService;

    @GetMapping("/sessions")
    public ResponseEntity<Map<String, Object>> listSessions(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        if (limit < 1 || offset < 0) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "limit must be >= 1 and offset must be >= 0"));
        }
        try {
            List<Map<String, Object>> sessions = sessionDBService.listSessions(limit, offset);
            int total = sessionDBService.countSessions();
            return ResponseEntity.ok(Map.of(
                    "code", 200, "message", "success",
                    "data", Map.of("total", total, "sessions", sessions)
            ));
        } catch (Exception e) {
            logger.error("List sessions failed", e);
            return ResponseEntity.status(500).body(Map.of("code", 500, "message", e.getMessage()));
        }
    }

    @PostMapping("/sessions")
    public ResponseEntity<Map<String, Object>> createSession(@RequestBody Map<String, Object> request) {
        try {
            String id = (String) request.get("id");
            String title = (String) request.getOrDefault("title", "新对话");
            Map<String, Object> session = sessionDBService.createSession(id, title);
            return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", session));
        } catch (Exception e) {
            logger.error("Create session failed", e);
            return ResponseEntity.status(500).body(Map.of("code", 500, "message", e.getMessage()));
        }
    }

    @PutMapping("/sessions/{sessionId}")
    public ResponseEntity<Map<String, Object>> updateSession(
            @PathVariable String sessionId,
            @RequestBody Map<String, Object> request) {
        try {
            String title = (String) request.get("title");
            Integer messageCount = null;
            if (request.get("message_count") instanceof Number n) {
                messageCount = n.intValue();
            }
            Map<String, Object> session = sessionDBService.updateSession(sessionId, title, messageCount);
            if (session == null) {
                return ResponseEntity.status(404).body(Map.of("code", 404, "message", "Session not found"));
            }
            return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", session));
        } catch (Exception e) {
            logger.error("Update session failed", e);
            return ResponseEntity.status(500).body(Map.of("code", 500, "message", e.getMessage()));
        }
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Map<String, Object>> deleteSession(@PathVariable String sessionId) {
        try {
            boolean deleted = sessionDBService.deleteSession(sessionId);
            if (!deleted) {
                return ResponseEntity.status(404).body(Map.of("code", 404, "message", "Session not found"));
            }
            return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", null));
        } catch (Exception e) {
            logger.error("Delete session failed", e);
            return ResponseEntity.status(500).body(Map.of("code", 500, "message", e.getMessage()));
        }
    }

    @PostMapping("/sessions/sync")
    public ResponseEntity<Map<String, Object>> syncSessions(@RequestBody Map<String, Object> request) {
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sessions = (List<Map<String, Object>>) request.get("sessions");
            List<Map<String, Object>> merged = sessionDBService.syncSessions(sessions);
            return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", merged));
        } catch (Exception e) {
            logger.error("Sync sessions failed", e);
            return ResponseEntity.status(500).body(Map.of("code", 500, "message", e.getMessage()));
        }
    }
}
