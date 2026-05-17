package org.example.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

public class SkillDTO {

    @Data
    public static class SkillCreateRequest {
        private String name;
        private String description = "";
        private String category = "general";
        private String toolCode;
        private String promptTemplate;
        private String toolChainDescription;
        private Map<String, Object> mcpServerConfig;
        private List<String> tags = List.of();
    }

    @Data
    public static class SkillUpdateRequest {
        private String name;
        private String description;
        private String category;
        private String toolCode;
        private String promptTemplate;
        private String toolChainDescription;
        private Map<String, Object> mcpServerConfig;
        private Boolean enabled;
        private List<String> tags;
    }

    @Data
    public static class SkillResponse {
        private String id;
        private String name;
        private String description = "";
        private String category = "general";
        private String sourceType = "manual";
        private String toolCode;
        private String promptTemplate;
        private String toolChainDescription;
        private Map<String, Object> mcpServerConfig;
        private boolean enabled = true;
        private int usageCount;
        private int successCount;
        private double successRate;
        private List<String> tags = List.of();
        private String createdAt;
        private String updatedAt;
    }

    @Data
    public static class SkillSearchRequest {
        private String query;
        private String category;
        private int topK = 5;
    }

    @Data
    public static class SkillPrecipitateRequest {
        private String sessionId;
    }

    @Data
    public static class GithubSkillSearchRequest {
        private String query;
        private int topK = 10;
    }

    @Data
    public static class GithubSkillInstallRequest {
        private String name;
        private String fullName;
        private String htmlUrl;
        private String description;
        private List<String> topics;
    }
}
