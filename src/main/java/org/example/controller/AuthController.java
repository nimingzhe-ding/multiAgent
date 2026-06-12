package org.example.controller;

import org.example.security.AuthenticatedUser;
import org.example.security.JwtService;
import org.example.service.UserDBService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    private final UserDBService userDBService;
    private final JwtService jwtService;

    public AuthController(UserDBService userDBService, JwtService jwtService) {
        this.userDBService = userDBService;
        this.jwtService = jwtService;
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody AuthRequest request) {
        try {
            Map<String, Object> user = userDBService.register(
                    request.username(),
                    request.password(),
                    request.displayName());
            String token = jwtService.generateToken((String) user.get("id"), (String) user.get("username"));
            return ResponseEntity.ok(authResponse(userDBService.publicUser(user), token));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
        } catch (Exception e) {
            logger.error("Register failed", e);
            return ResponseEntity.status(500).body(Map.of("code", 500, "message", e.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody AuthRequest request) {
        try {
            Map<String, Object> user = userDBService.authenticate(request.username(), request.password());
            String token = jwtService.generateToken((String) user.get("id"), (String) user.get("username"));
            return ResponseEntity.ok(authResponse(user, token));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(401).body(Map.of("code", 401, "message", e.getMessage()));
        } catch (Exception e) {
            logger.error("Login failed", e);
            return ResponseEntity.status(500).body(Map.of("code", 500, "message", e.getMessage()));
        }
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(@AuthenticationPrincipal AuthenticatedUser user) {
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("code", 401, "message", "Unauthorized"));
        }
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "success",
                "data", Map.of("id", user.userId(), "username", user.username())
        ));
    }

    private Map<String, Object> authResponse(Map<String, Object> user, String token) {
        return Map.of(
                "code", 200,
                "message", "success",
                "data", Map.of(
                        "token", token,
                        "tokenType", "Bearer",
                        "expiresIn", jwtService.getExpirationSeconds(),
                        "user", user
                )
        );
    }

    public record AuthRequest(String username, String password, String displayName) {
    }
}
