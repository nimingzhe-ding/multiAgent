package org.example.controller;

import org.example.security.AuthenticatedUser;
import org.example.service.SessionDBService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SessionController {

    private static final Logger logger = LoggerFactory.getLogger(SessionController.class);

    private final SessionDBService sessionDBService;

    public SessionController(SessionDBService sessionDBService) {
        this.sessionDBService = sessionDBService;
    }

    @GetMapping("/sessions")
    public ResponseEntity<Map<String, Object>> listSessions(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @AuthenticationPrincipal AuthenticatedUser user) {
        if (limit < 1 || offset < 0) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "limit must be >= 1 and offset must be >= 0"));
        }
        try {
            List<Map<String, Object>> sessions = sessionDBService.listSessions(limit, offset, user.userId());
            int total = sessionDBService.countSessions(user.userId());
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
    public ResponseEntity<Map<String, Object>> createSession(@RequestBody Map<String, Object> request,
                                                             @AuthenticationPrincipal AuthenticatedUser user) {
        try {
            String id = (String) request.get("id");
            String title = (String) request.getOrDefault("title", "新对话");
            Map<String, Object> session = sessionDBService.createSession(id, title, user.userId());
            return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", session));
        } catch (Exception e) {
            logger.error("Create session failed", e);
            return ResponseEntity.status(500).body(Map.of("code", 500, "message", e.getMessage()));
        }
    }

    @PutMapping("/sessions/{sessionId}")
    public ResponseEntity<Map<String, Object>> updateSession(
            @PathVariable String sessionId,
            @RequestBody Map<String, Object> request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        try {
            String title = (String) request.get("title");
            Integer messageCount = null;
            if (request.get("message_count") instanceof Number n) {
                messageCount = n.intValue();
            }
            Map<String, Object> session = sessionDBService.updateSession(sessionId, title, messageCount, user.userId());
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
    public ResponseEntity<Map<String, Object>> deleteSession(@PathVariable String sessionId,
                                                             @AuthenticationPrincipal AuthenticatedUser user) {
        try {
            boolean deleted = sessionDBService.deleteSession(sessionId, user.userId());
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
    public ResponseEntity<Map<String, Object>> syncSessions(@RequestBody Map<String, Object> request,
                                                            @AuthenticationPrincipal AuthenticatedUser user) {
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sessions = (List<Map<String, Object>>) request.get("sessions");
            List<Map<String, Object>> merged = sessionDBService.syncSessions(sessions, user.userId());
            return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", merged));
        } catch (Exception e) {
            logger.error("Sync sessions failed", e);
            return ResponseEntity.status(500).body(Map.of("code", 500, "message", e.getMessage()));
        }
    }
}
