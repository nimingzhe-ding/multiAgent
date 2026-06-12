package org.example.controller;

import org.example.dto.*;
import org.example.security.AuthenticatedUser;
import org.example.service.SkillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SkillController {

    private static final Logger logger = LoggerFactory.getLogger(SkillController.class);

    @Autowired
    private SkillService skillService;

    @GetMapping("/skills")
    public ResponseEntity<Map<String, Object>> listSkills(
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "false") boolean enabledOnly,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        if (limit < 1 || offset < 0) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "limit must be >= 1 and offset must be >= 0"));
        }
        try {
            List<Map<String, Object>> skills = skillService.listSkills(category, enabledOnly, limit, offset);
            int total = skillService.countSkills(category, enabledOnly);
            return ResponseEntity.ok(Map.of(
                    "code", 200, "message", "success",
                    "data", Map.of("total", total, "skills", skills)
            ));
        } catch (Exception e) {
            logger.error("List skills failed", e);
            return ResponseEntity.status(500).body(Map.of("code", 500, "message", e.getMessage()));
        }
    }

    @GetMapping("/skills/{skillId}")
    public ResponseEntity<Map<String, Object>> getSkill(@PathVariable String skillId) {
        try {
            Map<String, Object> skill = skillService.getSkill(skillId);
            if (skill == null) {
                return ResponseEntity.status(404).body(Map.of("code", 404, "message", "Skill not found: " + skillId));
            }
            return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", skill));
        } catch (Exception e) {
            logger.error("Get skill failed", e);
            return ResponseEntity.status(500).body(Map.of("code", 500, "message", e.getMessage()));
        }
    }

    @PostMapping("/skills")
    public ResponseEntity<Map<String, Object>> createSkill(@RequestBody SkillDTO.SkillCreateRequest request) {
        try {
            Map<String, Object> skill = skillService.createSkill(
                    request.getName(),
                    request.getDescription(),
                    request.getCategory(),
                    request.getToolCode(),
                    request.getPromptTemplate(),
                    request.getToolChainDescription(),
                    request.getMcpServerConfig(),
                    request.getTags());
            return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", skill));
        } catch (Exception e) {
            logger.error("Create skill failed", e);
            return ResponseEntity.status(500).body(Map.of("code", 500, "message", e.getMessage()));
        }
    }

    @PutMapping("/skills/{skillId}")
    public ResponseEntity<Map<String, Object>> updateSkill(
            @PathVariable String skillId,
            @RequestBody SkillDTO.SkillUpdateRequest request) {
        try {
            Map<String, Object> skill = skillService.updateSkill(
                    skillId,
                    request.getName(),
                    request.getDescription(),
                    request.getCategory(),
                    request.getToolCode(),
                    request.getPromptTemplate(),
                    request.getToolChainDescription(),
                    request.getMcpServerConfig(),
                    request.getEnabled(),
                    request.getTags());
            if (skill == null) {
                return ResponseEntity.status(404).body(Map.of("code", 404, "message", "Skill not found: " + skillId));
            }
            return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", skill));
        } catch (Exception e) {
            logger.error("Update skill failed", e);
            return ResponseEntity.status(500).body(Map.of("code", 500, "message", e.getMessage()));
        }
    }

    @DeleteMapping("/skills/{skillId}")
    public ResponseEntity<Map<String, Object>> deleteSkill(@PathVariable String skillId) {
        try {
            boolean deleted = skillService.deleteSkill(skillId);
            if (!deleted) {
                return ResponseEntity.status(404).body(Map.of("code", 404, "message", "Skill not found: " + skillId));
            }
            return ResponseEntity.ok(Map.of("code", 200, "message", "success",
                    "data", Map.of("id", skillId, "deleted", true)));
        } catch (Exception e) {
            logger.error("Delete skill failed", e);
            return ResponseEntity.status(500).body(Map.of("code", 500, "message", e.getMessage()));
        }
    }

    @PatchMapping("/skills/{skillId}/toggle")
    public ResponseEntity<Map<String, Object>> toggleSkill(
            @PathVariable String skillId,
            @RequestParam(defaultValue = "true") boolean enabled) {
        try {
            Map<String, Object> skill = skillService.toggleSkill(skillId, enabled);
            if (skill == null) {
                return ResponseEntity.status(404).body(Map.of("code", 404, "message", "Skill not found: " + skillId));
            }
            return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", skill));
        } catch (Exception e) {
            logger.error("Toggle skill failed", e);
            return ResponseEntity.status(500).body(Map.of("code", 500, "message", e.getMessage()));
        }
    }

    @PostMapping("/skills/search")
    public ResponseEntity<Map<String, Object>> searchSkills(@RequestBody SkillDTO.SkillSearchRequest request) {
        try {
            List<Map<String, Object>> skills = skillService.searchSkills(
                    request.getQuery(), request.getCategory(), request.getTopK());
            return ResponseEntity.ok(Map.of(
                    "code", 200, "message", "success",
                    "data", Map.of("total", skills.size(), "skills", skills)
            ));
        } catch (Exception e) {
            logger.error("Search skills failed", e);
            return ResponseEntity.status(500).body(Map.of("code", 500, "message", e.getMessage()));
        }
    }

    @PostMapping("/skills/precipitate")
    public ResponseEntity<Map<String, Object>> precipitateSkill(@RequestBody SkillDTO.SkillPrecipitateRequest request,
                                                                @AuthenticationPrincipal AuthenticatedUser user) {
        try {
            Map<String, Object> skill = skillService.precipitateFromSession(request.getSessionId(), user.userId());
            return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", skill));
        } catch (RuntimeException e) {
            return ResponseEntity.status(400).body(Map.of("code", 400, "message", e.getMessage()));
        } catch (Exception e) {
            logger.error("Precipitate skill failed", e);
            return ResponseEntity.status(500).body(Map.of("code", 500, "message", e.getMessage()));
        }
    }

    @PostMapping("/skills/search-github")
    public ResponseEntity<Map<String, Object>> searchGithubSkills(@RequestBody SkillDTO.GithubSkillSearchRequest request) {
        try {
            List<Map<String, Object>> repos = skillService.searchGithubSkills(
                    request.getQuery(), request.getTopK());
            return ResponseEntity.ok(Map.of(
                    "code", 200, "message", "success",
                    "data", Map.of("total", repos.size(), "repos", repos)
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.status(400).body(Map.of("code", 400, "message", e.getMessage()));
        } catch (Exception e) {
            logger.error("GitHub skill search failed", e);
            return ResponseEntity.status(500).body(Map.of("code", 500, "message", e.getMessage()));
        }
    }

    @PostMapping("/skills/install-github")
    public ResponseEntity<Map<String, Object>> installGithubSkill(@RequestBody SkillDTO.GithubSkillInstallRequest request) {
        try {
            Map<String, Object> repoInfo = Map.of(
                    "name", request.getName() != null ? request.getName() : "",
                    "full_name", request.getFullName() != null ? request.getFullName() : "",
                    "html_url", request.getHtmlUrl() != null ? request.getHtmlUrl() : "",
                    "description", request.getDescription() != null ? request.getDescription() : "",
                    "topics", request.getTopics() != null ? request.getTopics() : List.of()
            );
            Map<String, Object> skill = skillService.installGithubSkill(repoInfo);
            return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", skill));
        } catch (Exception e) {
            logger.error("Install GitHub skill failed", e);
            return ResponseEntity.status(500).body(Map.of("code", 500, "message", e.getMessage()));
        }
    }
}
