package org.example.agent.tool;

import org.example.service.SkillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Agent tool for searching skills from the skill library.
 * Skills are reusable patterns extracted from successful diagnoses.
 */
@Component
public class SkillTools {

    private static final Logger logger = LoggerFactory.getLogger(SkillTools.class);

    @Autowired
    private SkillService skillService;

    /**
     * Search the skill library for relevant skills.
     *
     * @param query Search query describing the problem or needed capability
     * @return Formatted skill information text
     */
    @Tool(description = "搜索技能库，查找相关的可复用技能。当用户的问题可能有历史解决方案、或需要查找运维操作模式时使用此工具。")
    public String searchSkills(
            @ToolParam(description = "搜索查询，描述问题或需要的技能") String query) {

        logger.info("技能搜索工具被调用: query='{}'", query);

        try {
            List<Map<String, Object>> skills = skillService.searchSkills(query, null, 3);

            if (skills.isEmpty()) {
                return "没有找到相关技能。";
            }

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < skills.size(); i++) {
                Map<String, Object> skill = skills.get(i);
                sb.append("【技能 ").append(i + 1).append(": ")
                        .append(skill.getOrDefault("name", "未命名")).append("】\n");
                sb.append("分类: ").append(skill.getOrDefault("category", "general")).append("\n");
                sb.append("描述: ").append(skill.getOrDefault("description", "")).append("\n");
                if (skill.get("tool_chain_description") != null) {
                    sb.append("工具链: ").append(skill.get("tool_chain_description")).append("\n");
                }
                if (skill.get("prompt_template") != null) {
                    sb.append("提示模板: ").append(skill.get("prompt_template")).append("\n");
                }
                sb.append("\n");
            }

            logger.info("找到 {} 个相关技能", skills.size());
            return sb.toString();

        } catch (Exception e) {
            logger.error("技能搜索工具调用失败: {}", e.getMessage());
            return "搜索技能时发生错误: " + e.getMessage();
        }
    }
}
