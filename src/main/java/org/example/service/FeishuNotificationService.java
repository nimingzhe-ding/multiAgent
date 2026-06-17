package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class FeishuNotificationService {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final int MAX_TEXT_MESSAGE_LENGTH = 3500;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient httpClient;
    private final String webhookUrl;
    private final String webhookSecret;

    public FeishuNotificationService(
            @Value("${feishu.webhook-url:${FEISHU_WEBHOOK_URL:}}") String webhookUrl,
            @Value("${feishu.webhook-secret:${FEISHU_WEBHOOK_SECRET:}}") String webhookSecret,
            @Value("${feishu.timeout-seconds:10}") int timeoutSeconds) {
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
        this.webhookSecret = webhookSecret == null ? "" : webhookSecret.trim();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .readTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configured", isConfigured());
        result.put("signed", !webhookSecret.isBlank());
        result.put("webhookHost", maskWebhook());
        return result;
    }

    public boolean isConfigured() {
        return !webhookUrl.isBlank();
    }

    public SendResult sendTestMessage(String username) {
        return sendText("SuperBizAgent 飞书连接测试成功。触发用户: " + (username == null ? "unknown" : username));
    }

    public SendResult sendMemoryAlert(Map<String, Object> alert) {
        SendResult cardResult = sendInteractiveCard(buildMemoryCard(alert));
        if (cardResult.success()) {
            return cardResult;
        }
        return sendText(buildMemoryText(alert));
    }

    public SendResult sendAiOpsReport(String title, String report) {
        String safeTitle = title == null || title.isBlank() ? "AI Ops 自动排查报告" : title;
        String safeReport = report == null || report.isBlank() ? "未生成可用报告。" : report;
        String content = safeTitle + "\n\n" + safeReport;
        if (content.length() <= MAX_TEXT_MESSAGE_LENGTH) {
            return sendText(content);
        }

        SendResult firstResult = null;
        int totalParts = (int) Math.ceil(content.length() / (double) MAX_TEXT_MESSAGE_LENGTH);
        for (int i = 0; i < content.length(); i += MAX_TEXT_MESSAGE_LENGTH) {
            int end = Math.min(i + MAX_TEXT_MESSAGE_LENGTH, content.length());
            int part = i / MAX_TEXT_MESSAGE_LENGTH + 1;
            SendResult result = sendText("[" + part + "/" + totalParts + "] " + content.substring(i, end));
            if (firstResult == null) {
                firstResult = result;
            }
            if (!result.success()) {
                return result;
            }
        }
        return firstResult == null ? new SendResult(false, 0, "empty report") : firstResult;
    }

    public SendResult sendInteractiveCard(Map<String, Object> card) {
        if (!isConfigured()) {
            return new SendResult(false, 0, "Feishu webhook is not configured");
        }
        Map<String, Object> payload = signedPayload();
        payload.put("msg_type", "interactive");
        payload.put("card", card);
        return post(payload);
    }

    public SendResult sendText(String text) {
        if (!isConfigured()) {
            return new SendResult(false, 0, "Feishu webhook is not configured");
        }
        Map<String, Object> payload = signedPayload();
        payload.put("msg_type", "text");
        payload.put("content", Map.of("text", text));
        return post(payload);
    }

    private Map<String, Object> signedPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (!webhookSecret.isBlank()) {
            long timestamp = Instant.now().getEpochSecond();
            payload.put("timestamp", String.valueOf(timestamp));
            payload.put("sign", sign(timestamp));
        }
        return payload;
    }

    private String sign(long timestamp) {
        try {
            String stringToSign = timestamp + "\n" + webhookSecret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(stringToSign.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(new byte[]{}));
        } catch (Exception e) {
            throw new RuntimeException("Feishu webhook sign failed", e);
        }
    }

    private SendResult post(Map<String, Object> payload) {
        try {
            String body = objectMapper.writeValueAsString(payload);
            Request request = new Request.Builder()
                    .url(webhookUrl)
                    .post(RequestBody.create(body, JSON))
                    .build();
            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() == null ? "" : response.body().string();
                return new SendResult(response.isSuccessful(), response.code(), responseBody);
            }
        } catch (Exception e) {
            return new SendResult(false, 0, e.getMessage());
        }
    }

    private Map<String, Object> buildMemoryCard(Map<String, Object> alert) {
        String type = String.valueOf(alert.getOrDefault("event_type", "alert"));
        boolean recovered = "recovered".equalsIgnoreCase(type);
        String title = recovered ? "内存告警恢复" : "本机内存告警";
        String template = recovered ? "green" : "red";
        String content = String.format("""
                **状态**: %s
                **内存使用率**: %.2f%%
                **已用/总量**: %s / %s
                **阈值**: %.2f%%
                **时间**: %s
                """,
                recovered ? "已恢复" : "告警中",
                asDouble(alert.get("usage_percent")),
                alert.getOrDefault("used_memory", "-"),
                alert.getOrDefault("total_memory", "-"),
                asDouble(alert.get("threshold_percent")),
                alert.getOrDefault("created_at", Instant.now().toString()));

        return Map.of(
                "config", Map.of("wide_screen_mode", true),
                "header", Map.of(
                        "template", template,
                        "title", Map.of("tag", "plain_text", "content", title)
                ),
                "elements", List.of(
                        Map.of(
                                "tag", "div",
                                "text", Map.of("tag", "lark_md", "content", content)
                        )
                )
        );
    }

    private String buildMemoryText(Map<String, Object> alert) {
        return String.format("SuperBizAgent 内存事件: %s, 使用率 %.2f%%, 阈值 %.2f%%",
                alert.getOrDefault("event_type", "alert"),
                asDouble(alert.get("usage_percent")),
                asDouble(alert.get("threshold_percent")));
    }

    private double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception e) {
            return 0.0d;
        }
    }

    private String maskWebhook() {
        if (webhookUrl.isBlank()) {
            return "";
        }
        int slash = webhookUrl.indexOf("/open-apis/");
        if (slash > 0) {
            return webhookUrl.substring(0, slash) + "/open-apis/bot/v2/hook/***";
        }
        return "***";
    }

    public record SendResult(boolean success, int statusCode, String message) {
    }
}
