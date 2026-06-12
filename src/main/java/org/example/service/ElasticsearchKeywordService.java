package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ElasticsearchKeywordService {

    private static final Logger logger = LoggerFactory.getLogger(ElasticsearchKeywordService.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OkHttpClient client;
    private volatile boolean indexChecked;

    @Value("${rag.keyword.elasticsearch.enabled:true}")
    private boolean enabled;

    @Value("${rag.keyword.elasticsearch.url:http://localhost:9200}")
    private String baseUrl;

    @Value("${rag.keyword.elasticsearch.index:super_biz_knowledge}")
    private String indexName;

    @Value("${rag.keyword.elasticsearch.username:}")
    private String username;

    @Value("${rag.keyword.elasticsearch.password:}")
    private String password;

    public ElasticsearchKeywordService(
            @Value("${rag.keyword.elasticsearch.connect-timeout-seconds:3}") long connectTimeoutSeconds,
            @Value("${rag.keyword.elasticsearch.read-timeout-seconds:5}") long readTimeoutSeconds) {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .readTimeout(Duration.ofSeconds(readTimeoutSeconds))
                .build();
    }

    public boolean isEnabled() {
        return enabled && baseUrl != null && !baseUrl.isBlank();
    }

    public void indexChunk(String id, String content, Map<String, Object> metadata, int chunkIndex) {
        if (!isEnabled()) {
            return;
        }
        try {
            ensureIndex();
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("content", content == null ? "" : content);
            doc.put("source", stringValue(metadata.get("_source")));
            doc.put("sourceName", stringValue(metadata.get("_file_name")));
            doc.put("title", stringValue(metadata.get("title")));
            doc.put("extension", stringValue(metadata.get("_extension")));
            doc.put("category", stringValue(metadata.get("category")));
            doc.put("visibility", stringValue(metadata.get("visibility")));
            doc.put("ownerUserId", stringValue(metadata.get("owner_user_id")));
            doc.put("sourceType", stringValue(metadata.get("source_type")));
            doc.put("tags", metadata.getOrDefault("tags", List.of()));
            doc.put("chunkIndex", chunkIndex);
            doc.put("metadata", metadata);
            doc.put("metadataText", MAPPER.writeValueAsString(metadata));
            doc.put("indexedAt", Instant.now().toString());

            Request request = authorized(new Request.Builder()
                    .url(endpoint("/" + indexName + "/_doc/" + urlEncode(id)))
                    .put(RequestBody.create(MAPPER.writeValueAsBytes(doc), JSON)))
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    logger.warn("Elasticsearch keyword index failed, id={}, status={}, body={}",
                            id, response.code(), bodyString(response));
                }
            }
        } catch (Exception e) {
            logger.warn("Elasticsearch keyword index skipped, id={}, reason={}", id, e.getMessage());
        }
    }

    public void deleteBySource(String source) {
        if (!isEnabled() || source == null || source.isBlank()) {
            return;
        }
        try {
            ensureIndex();
            Map<String, Object> body = Map.of(
                    "query", Map.of("term", Map.of("source", source))
            );
            Request request = authorized(new Request.Builder()
                    .url(endpoint("/" + indexName + "/_delete_by_query?refresh=true&conflicts=proceed"))
                    .post(RequestBody.create(MAPPER.writeValueAsBytes(body), JSON)))
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() && response.code() != 404) {
                    logger.warn("Elasticsearch keyword delete failed, source={}, status={}, body={}",
                            source, response.code(), bodyString(response));
                }
            }
        } catch (Exception e) {
            logger.warn("Elasticsearch keyword delete skipped, source={}, reason={}", source, e.getMessage());
        }
    }

    public List<VectorSearchService.SearchResult> search(String query, int topK) {
        if (!isEnabled() || query == null || query.isBlank()) {
            return List.of();
        }
        try {
            ensureIndex();
            Map<String, Object> body = Map.of(
                    "size", Math.max(1, topK),
                    "query", Map.of(
                            "multi_match", Map.of(
                                    "query", query,
                                    "fields", List.of(
                                            "sourceName^5",
                                            "title^4",
                                            "source^4",
                                            "tags^3",
                                            "category^2",
                                            "metadataText^2",
                                            "content^1.5",
                                            "content.ngram",
                                            "sourceName.ngram^2"
                                    ),
                                    "type", "best_fields",
                                    "operator", "or"
                            )
                    )
            );

            Request request = authorized(new Request.Builder()
                    .url(endpoint("/" + indexName + "/_search"))
                    .post(RequestBody.create(MAPPER.writeValueAsBytes(body), JSON)))
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    logger.warn("Elasticsearch keyword search failed, status={}, body={}",
                            response.code(), bodyString(response));
                    return List.of();
                }
                String raw = bodyString(response);
                JsonNode hits = MAPPER.readTree(raw).path("hits").path("hits");
                List<VectorSearchService.SearchResult> results = new ArrayList<>();
                for (JsonNode hit : hits) {
                    JsonNode sourceNode = hit.path("_source");
                    VectorSearchService.SearchResult result = new VectorSearchService.SearchResult();
                    result.setId(hit.path("_id").asText(""));
                    result.setScore((float) hit.path("_score").asDouble(0));
                    result.setKeywordScore(result.getScore());
                    result.setRecallSource("elasticsearch_keyword");
                    result.setContent(sourceNode.path("content").asText(""));
                    JsonNode metadataNode = sourceNode.path("metadata");
                    if (!metadataNode.isMissingNode() && !metadataNode.isNull()) {
                        result.setMetadata(MAPPER.writeValueAsString(metadataNode));
                    } else {
                        result.setMetadata(sourceNode.path("metadataText").asText(""));
                    }
                    results.add(result);
                }
                return results;
            }
        } catch (Exception e) {
            logger.warn("Elasticsearch keyword search skipped, reason={}", e.getMessage());
            return List.of();
        }
    }

    private void ensureIndex() throws Exception {
        if (indexChecked) {
            return;
        }
        synchronized (this) {
            if (indexChecked) {
                return;
            }
            Request head = authorized(new Request.Builder().url(endpoint("/" + indexName)).head()).build();
            try (Response response = client.newCall(head).execute()) {
                if (response.isSuccessful()) {
                    indexChecked = true;
                    return;
                }
                if (response.code() != 404) {
                    throw new IllegalStateException("Elasticsearch index check failed: " + response.code());
                }
            }

            Map<String, Object> mapping = new LinkedHashMap<>();
            mapping.put("settings", Map.of(
                    "index.max_ngram_diff", 18,
                    "analysis", Map.of(
                            "tokenizer", Map.of(
                                    "mixed_ngram_tokenizer", Map.of(
                                            "type", "ngram",
                                            "min_gram", 2,
                                            "max_gram", 20,
                                            "token_chars", List.of("letter", "digit", "punctuation", "symbol")
                                    )
                            ),
                            "analyzer", Map.of(
                                    "mixed_ngram", Map.of(
                                            "type", "custom",
                                            "tokenizer", "mixed_ngram_tokenizer",
                                            "filter", List.of("lowercase")
                                    )
                            )
                    )
            ));
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("content", textWithNgram());
            properties.put("sourceName", textWithNgram());
            properties.put("title", Map.of("type", "text"));
            properties.put("metadataText", Map.of("type", "text"));
            properties.put("source", Map.of("type", "keyword"));
            properties.put("category", Map.of("type", "keyword"));
            properties.put("visibility", Map.of("type", "keyword"));
            properties.put("ownerUserId", Map.of("type", "keyword"));
            properties.put("sourceType", Map.of("type", "keyword"));
            properties.put("extension", Map.of("type", "keyword"));
            properties.put("tags", Map.of("type", "keyword"));
            properties.put("chunkIndex", Map.of("type", "integer"));
            properties.put("indexedAt", Map.of("type", "date"));
            properties.put("metadata", Map.of("type", "object", "enabled", false));
            mapping.put("mappings", Map.of("properties", properties));

            Request create = authorized(new Request.Builder()
                    .url(endpoint("/" + indexName))
                    .put(RequestBody.create(MAPPER.writeValueAsBytes(mapping), JSON)))
                    .build();
            try (Response response = client.newCall(create).execute()) {
                if (!response.isSuccessful() && response.code() != 400) {
                    throw new IllegalStateException("Elasticsearch index create failed: "
                            + response.code() + " " + bodyString(response));
                }
                logger.info("Elasticsearch keyword index ready: {}", indexName);
                indexChecked = true;
            }
        }
    }

    private Map<String, Object> textWithNgram() {
        return Map.of(
                "type", "text",
                "fields", Map.of(
                        "ngram", Map.of("type", "text", "analyzer", "mixed_ngram")
                )
        );
    }

    private Request.Builder authorized(Request.Builder builder) {
        if (username != null && !username.isBlank()) {
            builder.header("Authorization", Credentials.basic(username, password == null ? "" : password));
        }
        return builder.header("Content-Type", "application/json");
    }

    private String endpoint(String path) {
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return normalizedBase + path;
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String bodyString(Response response) {
        try {
            return response.body() == null ? "" : response.body().string();
        } catch (Exception e) {
            return "";
        }
    }
}
