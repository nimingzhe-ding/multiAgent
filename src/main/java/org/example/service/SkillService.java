package org.example.service;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import org.example.controller.ChatController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SkillService {

    private static final Logger logger = LoggerFactory.getLogger(SkillService.class);
    private static final Gson gson = new Gson();

    @Autowired
    private SkillDBService skillDBService;

    @Autowired
    private SkillVectorService skillVectorService;

    @Autowired
    private ChatService chatService;

    @Autowired
    private SessionDBService sessionDBService;

    @Value("${skill.github-token:}")
    private String githubToken;

    @Value("${skill.search-top-k:5}")
    private int searchTopK;

    // ── CRUD ──────────────────────────────────────────────────────────────

    public Map<String, Object> createSkill(
            String name, String description, String category,
            String toolCode, String promptTemplate, String toolChainDescription,
            Map<String, Object> mcpServerConfig, List<String> tags) {

        String effectiveCategory = category != null ? category : "general";
        Map<String, Object> skill = skillDBService.createSkill(
                name, description, effectiveCategory, "manual",
                toolCode, promptTemplate, toolChainDescription,
                mcpServerConfig, tags);

        // Embed into Milvus
        try {
            Map<String, Object> meta = Map.of("skill_id", skill.get("id"), "category", effectiveCategory);
            skillVectorService.addSkillEmbedding(
                    (String) skill.get("id"), name, description, tags,
                    toolChainDescription, meta);
        } catch (Exception e) {
            logger.warn("Failed to embed skill {}: {}", skill.get("id"), e.getMessage());
        }

        logger.info("Created skill: id={}, name={}", skill.get("id"), name);
        return skill;
    }

    private Map<String, Object> createSkillWithSourceType(
            String name, String description, String category, String sourceType,
            String toolCode, String promptTemplate, String toolChainDescription,
            Map<String, Object> mcpServerConfig, List<String> tags) {

        String effectiveCategory = category != null ? category : "general";
        Map<String, Object> skill = skillDBService.createSkill(
                name, description, effectiveCategory, sourceType,
                toolCode, promptTemplate, toolChainDescription,
                mcpServerConfig, tags);

        try {
            Map<String, Object> meta = Map.of("skill_id", skill.get("id"), "category", effectiveCategory);
            skillVectorService.addSkillEmbedding(
                    (String) skill.get("id"), name, description, tags,
                    toolChainDescription, meta);
        } catch (Exception e) {
            logger.warn("Failed to embed skill {}: {}", skill.get("id"), e.getMessage());
        }

        logger.info("Created {} skill: id={}, name={}", sourceType, skill.get("id"), name);
        return skill;
    }

    public Map<String, Object> getSkill(String skillId) {
        return skillDBService.getSkill(skillId);
    }

    public List<Map<String, Object>> listSkills(String category, boolean enabledOnly, int limit, int offset) {
        return skillDBService.listSkills(category, enabledOnly, limit, offset);
    }

    public int countSkills(String category, boolean enabledOnly) {
        return skillDBService.countSkills(category, enabledOnly);
    }

    public Map<String, Object> updateSkill(String skillId,
            String name, String description, String category,
            String toolCode, String promptTemplate, String toolChainDescription,
            Map<String, Object> mcpServerConfig, Boolean enabled, List<String> tags) {

        Map<String, Object> skill = skillDBService.updateSkill(
                skillId, name, description, category,
                toolCode, promptTemplate, toolChainDescription,
                mcpServerConfig, enabled, tags);

        if (skill != null) {
            // Re-embed if content fields changed
            boolean needReembed = name != null || description != null ||
                    tags != null || toolChainDescription != null;
            if (needReembed) {
                try {
                    @SuppressWarnings("unchecked")
                    List<String> skillTags = (List<String>) skill.get("tags");
                    Map<String, Object> meta = Map.of(
                            "skill_id", skillId,
                            "category", skill.getOrDefault("category", "general"));
                    skillVectorService.updateSkillEmbedding(
                            skillId,
                            (String) skill.getOrDefault("name", ""),
                            (String) skill.getOrDefault("description", ""),
                            skillTags,
                            (String) skill.get("tool_chain_description"),
                            meta);
                } catch (Exception e) {
                    logger.warn("Failed to re-embed skill {}: {}", skillId, e.getMessage());
                }
            }
        }
        return skill;
    }

    public boolean deleteSkill(String skillId) {
        try {
            skillVectorService.deleteSkillEmbedding(skillId);
        } catch (Exception e) {
            logger.warn("Failed to delete skill embedding {}: {}", skillId, e.getMessage());
        }
        return skillDBService.deleteSkill(skillId);
    }

    public Map<String, Object> toggleSkill(String skillId, boolean enabled) {
        return skillDBService.updateSkill(skillId, null, null, null,
                null, null, null, null, enabled, null);
    }

    // ── Semantic Search ───────────────────────────────────────────────────

    public List<Map<String, Object>> searchSkills(String query, String category, int topK) {
        List<Object[]> matches = skillVectorService.searchSimilarSkills(query, topK * 2);

        List<Map<String, Object>> results = new ArrayList<>();
        for (Object[] match : matches) {
            String skillId = (String) match[0];
            double score = (double) match[1];

            Map<String, Object> skill = skillDBService.getSkill(skillId);
            if (skill == null) continue;
            if (!Boolean.TRUE.equals(skill.get("enabled"))) continue;
            if (category != null && !category.equals(skill.get("category"))) continue;

            skill.put("_similarity_score", Math.round(score * 10000.0) / 10000.0);
            results.add(skill);
            if (results.size() >= topK) break;
        }
        return results;
    }

    public List<Map<String, Object>> getRelevantSkillsForQuery(String query, int topK) {
        return searchSkills(query, null, topK);
    }

    public String getSkillPromptContext(List<Map<String, Object>> skills) {
        if (skills == null || skills.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < skills.size(); i++) {
            Map<String, Object> skill = skills.get(i);
            sb.append("### 技能 ").append(i + 1).append(": ")
                    .append(skill.getOrDefault("name", "未命名")).append("\n");
            sb.append("- 分类: ").append(skill.getOrDefault("category", "general")).append("\n");
            sb.append("- 描述: ").append(skill.getOrDefault("description", "")).append("\n");
            if (skill.get("tool_chain_description") != null) {
                sb.append("- 工具链: ").append(skill.get("tool_chain_description")).append("\n");
            }
            if (skill.get("prompt_template") != null) {
                sb.append("- 提示模板:\n").append(skill.get("prompt_template")).append("\n");
            }
            @SuppressWarnings("unchecked")
            List<String> tags = (List<String>) skill.get("tags");
            if (tags != null && !tags.isEmpty()) {
                sb.append("- 标签: ").append(String.join(", ", tags)).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    // ── Skill Precipitation ───────────────────────────────────────────────

    public Map<String, Object> precipitateFromSession(String sessionId) {
        List<Map<String, String>> messages = sessionDBService.listSessionMessages(sessionId, 40);
        if (messages.isEmpty()) {
            throw new RuntimeException("No chat messages found for session: " + sessionId);
        }
        String transcript = buildSessionTranscript(messages);

        DashScopeApi dashScopeApi = chatService.createDashScopeApi();
        DashScopeChatModel chatModel = DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel(DashScopeChatModel.DEFAULT_MODEL_NAME)
                        .withTemperature(0.3)
                        .withMaxToken(2000)
                        .withTopP(0.9)
                        .build())
                .build();

        // Build extraction prompt (sessions are in ChatController's memory)
        String extractionPrompt = """
            分析以下对话，提取可复用的技能模式。请以JSON格式返回（只返回JSON，不要其他文字）：
            {
                "name": "技能名称（简短描述，10字以内）",
                "description": "详细描述这个技能解决什么问题",
                "category": "类别（从以下选择：troubleshooting/monitoring/deployment/networking/security/database/general）",
                "tool_chain_description": "描述使用了哪些工具，按什么顺序，每步做了什么",
                "prompt_template": "一个可复用的提示模板，包含{problem}占位符",
                "tags": ["标签1", "标签2"]
            }

            请从成功解决用户问题的对话模式中提取。

            对话内容：
            %s
            """;

        try {
            String content = chatModel.call(extractionPrompt.formatted(transcript));

            // Extract JSON from response
            if (content.contains("```json")) {
                content = content.split("```json")[1].split("```")[0].trim();
            } else if (content.contains("```")) {
                content = content.split("```")[1].split("```")[0].trim();
            }

            JsonObject extracted = gson.fromJson(content, JsonObject.class);

            Map<String, Object> skill = createSkillWithSourceType(
                    extracted.has("name") ? extracted.get("name").getAsString() : "未命名技能",
                    extracted.has("description") ? extracted.get("description").getAsString() : "",
                    extracted.has("category") ? extracted.get("category").getAsString() : "general",
                    "precipitated",
                    null,
                    extracted.has("prompt_template") ? extracted.get("prompt_template").getAsString() : null,
                    extracted.has("tool_chain_description") ? extracted.get("tool_chain_description").getAsString() : null,
                    null,
                    extracted.has("tags") ?
                            gson.fromJson(extracted.get("tags"), new TypeToken<List<String>>(){}.getType()) :
                            List.of()
            );

            logger.info("Precipitated skill from session {}: {}", sessionId, skill.get("name"));
            return skill;

        } catch (Exception e) {
            logger.error("Skill precipitation failed: {}", e.getMessage(), e);
            throw new RuntimeException("Skill precipitation failed: " + e.getMessage(), e);
        }
    }

    // ── GitHub Search ─────────────────────────────────────────────────────

    public List<Map<String, Object>> searchGithubSkills(String query, int topK) {
        List<Map<String, Object>> results = new ArrayList<>();
        try {
            String searchQuery = query + " mcp server";
            String apiUrl = "https://api.github.com/search/repositories?q=" +
                    java.net.URLEncoder.encode(searchQuery, StandardCharsets.UTF_8) +
                    "&sort=stars&order=desc&per_page=" + Math.min(topK, 30);

            HttpURLConnection conn = (HttpURLConnection) URI.create(apiUrl).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            if (githubToken != null && !githubToken.isEmpty()) {
                conn.setRequestProperty("Authorization", "token " + githubToken);
            }
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);

            if (conn.getResponseCode() != 200) {
                logger.error("GitHub API error: {}", conn.getResponseCode());
                throw new RuntimeException("GitHub API returned " + conn.getResponseCode());
            }

            try (InputStream is = conn.getInputStream();
                 InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                JsonObject data = gson.fromJson(reader, JsonObject.class);
                if (data.has("items")) {
                    data.getAsJsonArray("items").forEach(item -> {
                        JsonObject repo = item.getAsJsonObject();
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("name", getJsonString(repo, "name"));
                        result.put("full_name", getJsonString(repo, "full_name"));
                        result.put("description", getJsonString(repo, "description"));
                        result.put("html_url", getJsonString(repo, "html_url"));
                        result.put("stargazers_count", getJsonInt(repo, "stargazers_count"));
                        result.put("language", getJsonString(repo, "language"));
                        result.put("updated_at", getJsonString(repo, "updated_at"));
                        result.put("topics", gson.fromJson(repo.getAsJsonArray("topics"),
                                new TypeToken<List<String>>(){}.getType()));
                        results.add(result);
                    });
                }
            }
            logger.info("GitHub search for '{}': found {} repos", query, results.size());
        } catch (Exception e) {
            logger.error("GitHub search failed: {}", e.getMessage(), e);
            throw new RuntimeException("GitHub search failed: " + e.getMessage(), e);
        }
        return results;
    }

    public Map<String, Object> installGithubSkill(Map<String, Object> repoInfo) {
        String name = (String) repoInfo.getOrDefault("name", "");
        String fullName = (String) repoInfo.getOrDefault("full_name", "");
        String description = (String) repoInfo.getOrDefault("description", "");
        String htmlUrl = (String) repoInfo.getOrDefault("html_url", "");

        @SuppressWarnings("unchecked")
        List<String> topics = (List<String>) repoInfo.getOrDefault("topics", List.of());

        String fullDescription = "[GitHub] " + (description != null ? description : "") +
                "\n\n仓库: " + fullName + "\n链接: " + htmlUrl;

        Map<String, Object> mcpConfig = Map.of(
                "repo", fullName,
                "url", htmlUrl,
                "transport", "sse"
        );

        Map<String, Object> skill = createSkillWithSourceType(
                name, fullDescription, "general", "searched", null, null, null, mcpConfig, topics);

        logger.info("Installed GitHub skill: {}", fullName);
        return skill;
    }

    private String getJsonString(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "";
    }

    private int getJsonInt(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : 0;
    }

    private String buildSessionTranscript(List<Map<String, String>> messages) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, String> message : messages) {
            String role = "assistant".equals(message.get("role")) ? "助手" : "用户";
            String content = message.getOrDefault("content", "");
            sb.append(role).append(": ").append(content).append("\n\n");
        }
        return sb.toString();
    }
}
