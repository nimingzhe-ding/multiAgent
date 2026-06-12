package org.example.controller;

import org.example.security.AuthenticatedUser;
import org.example.service.FeishuNotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/integrations")
public class IntegrationController {

    private final FeishuNotificationService feishuNotificationService;

    public IntegrationController(FeishuNotificationService feishuNotificationService) {
        this.feishuNotificationService = feishuNotificationService;
    }

    @GetMapping("/feishu/status")
    public ResponseEntity<Map<String, Object>> feishuStatus() {
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "success",
                "data", feishuNotificationService.status()
        ));
    }

    @PostMapping("/feishu/test")
    public ResponseEntity<Map<String, Object>> testFeishu(@AuthenticationPrincipal AuthenticatedUser user) {
        FeishuNotificationService.SendResult result = feishuNotificationService.sendTestMessage(user == null ? null : user.username());
        int code = result.success() ? 200 : 500;
        return ResponseEntity.status(result.success() ? 200 : 500).body(Map.of(
                "code", code,
                "message", result.success() ? "success" : "failed",
                "data", Map.of(
                        "success", result.success(),
                        "statusCode", result.statusCode(),
                        "message", result.message()
                )
        ));
    }
}
