package org.example.service;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.graph.OverAllState;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bridges alert sources and AI Ops analysis.
 *
 * <p>Manual AI Ops and automatic alert-triggered AI Ops share the same
 * AiOpsService. This orchestrator only owns background execution,
 * deduplication, and report delivery.</p>
 */
@Service
public class AlertAnalysisOrchestratorService {

    private static final Logger logger = LoggerFactory.getLogger(AlertAnalysisOrchestratorService.class);

    private final AiOpsService aiOpsService;
    private final ChatService chatService;
    private final FeishuNotificationService feishuNotificationService;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Set<String> runningAnalysisKeys = ConcurrentHashMap.newKeySet();

    public AlertAnalysisOrchestratorService(AiOpsService aiOpsService,
                                            ChatService chatService,
                                            FeishuNotificationService feishuNotificationService) {
        this.aiOpsService = aiOpsService;
        this.chatService = chatService;
        this.feishuNotificationService = feishuNotificationService;
    }

    public boolean submitMemoryAlert(Map<String, Object> event) {
        if (event == null || event.isEmpty()) {
            return false;
        }
        String eventId = String.valueOf(event.getOrDefault("id", ""));
        String analysisKey = eventId.isBlank() ? "memory-alert-" + Instant.now() : "memory-alert-" + eventId;
        String context = buildMemoryAlertContext(event);
        return submitAnalysis(analysisKey, "内存告警 AI Ops 排查报告", context);
    }

    public boolean submitAnalysis(String analysisKey, String title, String alertContext) {
        String key = analysisKey == null || analysisKey.isBlank()
                ? "analysis-" + Instant.now()
                : analysisKey;
        if (!runningAnalysisKeys.add(key)) {
            logger.info("AI Ops analysis already running, key={}", key);
            return false;
        }

        executor.submit(() -> {
            try {
                logger.info("Starting background AI Ops analysis, key={}", key);
                String report = runAnalysis(alertContext);
                FeishuNotificationService.SendResult sendResult =
                        feishuNotificationService.sendAiOpsReport(title, report);
                logger.info("Background AI Ops analysis finished, key={}, feishuSuccess={}",
                        key, sendResult.success());
            } catch (Exception e) {
                logger.error("Background AI Ops analysis failed, key={}", key, e);
                feishuNotificationService.sendText("AI Ops 自动排查失败: " + e.getMessage());
            } finally {
                runningAnalysisKeys.remove(key);
            }
        });
        return true;
    }

    public String runAnalysis(String alertContext) throws Exception {
        DashScopeApi dashScopeApi = chatService.createDashScopeApi();
        DashScopeChatModel chatModel = DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel(DashScopeChatModel.DEFAULT_MODEL_NAME)
                        .withTemperature(0.3)
                        .withMaxToken(8000)
                        .withTopP(0.9)
                        .build())
                .build();

        ToolCallback[] toolCallbacks = chatService.getToolCallbacks();
        Optional<OverAllState> stateOptional = aiOpsService.executeAiOpsAnalysis(chatModel, toolCallbacks, alertContext);
        if (stateOptional.isEmpty()) {
            throw new IllegalStateException("AI Ops analysis returned empty state");
        }
        return aiOpsService.extractFinalReport(stateOptional.get())
                .orElse("AI Ops 流程已完成，但未能提取到最终报告。");
    }

    private String buildMemoryAlertContext(Map<String, Object> event) {
        return """
                告警来源: SuperBizAgent 本机内存监控
                告警类型: memory_high_usage
                事件ID: %s
                事件状态: %s
                当前内存使用率: %s%%
                告警阈值: %s%%
                已用内存: %s
                总内存: %s
                触发时间: %s
                原始消息: %s

                请排查本机内存使用率过高的可能原因，优先查询可用监控、日志和知识库文档；如果工具无法提供本机进程级证据，请在报告中明确说明。
                """.formatted(
                event.getOrDefault("id", ""),
                event.getOrDefault("event_type", "alert"),
                event.getOrDefault("usage_percent", ""),
                event.getOrDefault("threshold_percent", ""),
                event.getOrDefault("used_memory", ""),
                event.getOrDefault("total_memory", ""),
                event.getOrDefault("created_at", ""),
                event.getOrDefault("message", "")
        );
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}
