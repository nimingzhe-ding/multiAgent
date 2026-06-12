package org.example.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.QueryResults;
import io.milvus.grpc.SearchResults;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.dml.QueryParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.QueryResultsWrapper;
import io.milvus.response.SearchResultsWrapper;
import lombok.Getter;
import lombok.Setter;
import org.example.constant.MilvusConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class VectorSearchService {

    private static final Logger logger = LoggerFactory.getLogger(VectorSearchService.class);

    private static final Pattern ASCII_TOKEN_PATTERN = Pattern.compile("[a-zA-Z0-9_./:-]{2,}");
    private static final Pattern CJK_PHRASE_PATTERN = Pattern.compile("[\\p{IsHan}]{2,}");

    @Autowired
    private MilvusServiceClient milvusClient;

    @Autowired
    private VectorEmbeddingService embeddingService;

    @Autowired
    private KnowledgeSourceService knowledgeSourceService;

    @Autowired
    private ElasticsearchKeywordService elasticsearchKeywordService;

    @Value("${rag.hybrid.enabled:true}")
    private boolean hybridEnabled;

    @Value("${rag.hybrid.vector-weight:0.65}")
    private double vectorWeight;

    @Value("${rag.hybrid.keyword-weight:0.35}")
    private double keywordWeight;

    @Value("${rag.hybrid.rrf-k:60}")
    private int rrfK;

    @Value("${rag.hybrid.vector-candidate-multiplier:4}")
    private int vectorCandidateMultiplier;

    @Value("${rag.hybrid.keyword-candidate-limit:1000}")
    private int keywordCandidateLimit;

    @Value("${rag.hybrid.keyword-page-size:500}")
    private int keywordPageSize;

    public List<SearchResult> searchSimilarDocuments(String query, int topK) {
        return searchSimilarDocuments(query, topK, null);
    }

    public List<SearchResult> searchSimilarDocuments(String query, int topK, String userId) {
        if (userId == null || userId.isBlank()) {
            if (hybridEnabled) {
                return searchHybridDocuments(query, topK);
            }
            return searchVectorOnlyDocuments(query, topK);
        }

        int candidateTopK = Math.max(topK, topK * 4);
        List<SearchResult> candidates = hybridEnabled
                ? searchHybridDocuments(query, candidateTopK)
                : searchVectorOnlyDocuments(query, candidateTopK);
        List<SearchResult> visible = candidates.stream()
                .filter(result -> knowledgeSourceService.isSourceVisible(extractSourceKey(result.getMetadata()), userId))
                .limit(topK)
                .toList();
        logger.info("Scoped retrieval finished, candidates={}, visible={}, user={}",
                candidates.size(), visible.size(), userId);
        return visible;
    }

    public List<SearchResult> searchSimilarDocumentsUnscoped(String query, int topK) {
        if (hybridEnabled) {
            return searchHybridDocuments(query, topK);
        }
        return searchVectorOnlyDocuments(query, topK);
    }

    private String extractSourceKey(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return "";
        }
        try {
            JsonObject object = JsonParser.parseString(metadata).getAsJsonObject();
            if (object.has("_source") && !object.get("_source").isJsonNull()) {
                return object.get("_source").getAsString();
            }
        } catch (Exception e) {
            logger.debug("Failed to parse vector metadata for source scoping: {}", e.getMessage());
        }
        return "";
    }

    public List<SearchResult> searchHybridDocuments(String query, int topK) {
        try {
            logger.info("Starting hybrid retrieval, query={}, topK={}", query, topK);

            int vectorTopK = Math.max(topK, topK * Math.max(1, vectorCandidateMultiplier));
            List<SearchResult> vectorResults = searchVectorOnlyDocuments(query, vectorTopK);
            List<SearchResult> keywordResults = searchKeywordDocuments(query, Math.max(topK * 2, topK));

            Map<String, SearchResult> merged = new LinkedHashMap<>();

            for (int i = 0; i < vectorResults.size(); i++) {
                SearchResult result = vectorResults.get(i);
                int rank = i + 1;
                float rrfScore = calculateRrfScore(rank);
                result.setVectorScore(rrfScore);
                result.setHybridScore((float) (vectorWeight * rrfScore));
                result.setRecallSource("vector");
                merged.put(result.getId(), result);
            }

            for (int i = 0; i < keywordResults.size(); i++) {
                SearchResult keywordResult = keywordResults.get(i);
                int rank = i + 1;
                float rrfScore = calculateRrfScore(rank);
                SearchResult existing = merged.get(keywordResult.getId());
                if (existing == null) {
                    keywordResult.setKeywordScore(rrfScore);
                    keywordResult.setHybridScore((float) (keywordWeight * rrfScore));
                    keywordResult.setRecallSource("keyword");
                    merged.put(keywordResult.getId(), keywordResult);
                } else {
                    existing.setKeywordScore(rrfScore);
                    existing.setHybridScore((float) (existing.getHybridScore() + keywordWeight * rrfScore));
                    existing.setRecallSource("hybrid");
                }
            }

            List<SearchResult> results = new ArrayList<>(merged.values());
            results.sort(Comparator.comparing(SearchResult::getHybridScore).reversed());
            if (results.size() > topK) {
                results = new ArrayList<>(results.subList(0, topK));
            }

            logger.info("Hybrid retrieval finished, vector={}, keyword={}, merged={}, returned={}",
                    vectorResults.size(), keywordResults.size(), merged.size(), results.size());
            return results;
        } catch (Exception e) {
            logger.error("Hybrid retrieval failed, falling back to vector retrieval", e);
            return searchVectorOnlyDocuments(query, topK);
        }
    }

    private List<SearchResult> searchVectorOnlyDocuments(String query, int topK) {
        try {
            logger.info("Starting vector retrieval, query={}, topK={}", query, topK);

            List<Float> queryVector = embeddingService.generateQueryVector(query);
            logger.debug("Query embedding generated, dimension={}", queryVector.size());

            SearchParam searchParam = SearchParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withVectorFieldName("vector")
                    .withVectors(Collections.singletonList(queryVector))
                    .withTopK(topK)
                    .withMetricType(MetricType.L2)
                    .withOutFields(List.of("id", "content", "metadata"))
                    .withParams("{\"nprobe\":10}")
                    .build();

            R<SearchResults> searchResponse = milvusClient.search(searchParam);
            if (searchResponse.getStatus() != 0) {
                throw new RuntimeException("Vector retrieval failed: " + searchResponse.getMessage());
            }

            SearchResultsWrapper wrapper = new SearchResultsWrapper(searchResponse.getData().getResults());
            List<SearchResult> results = new ArrayList<>();

            for (int i = 0; i < wrapper.getRowRecords(0).size(); i++) {
                SearchResult result = new SearchResult();
                result.setId((String) wrapper.getIDScore(0).get(i).get("id"));
                result.setContent((String) wrapper.getFieldData("content", 0).get(i));
                result.setScore(wrapper.getIDScore(0).get(i).getScore());
                result.setRecallSource("vector");

                Object metadataObj = wrapper.getFieldData("metadata", 0).get(i);
                if (metadataObj != null) {
                    result.setMetadata(metadataObj.toString());
                }

                results.add(result);
            }

            logger.info("Vector retrieval finished, results={}", results.size());
            return results;
        } catch (Exception e) {
            logger.error("Vector retrieval failed", e);
            throw new RuntimeException("Search failed: " + e.getMessage(), e);
        }
    }

    private float calculateRrfScore(int rank) {
        return (float) (1.0d / (Math.max(1, rrfK) + rank));
    }

    private List<SearchResult> searchKeywordDocuments(String query, int topK) {
        if (elasticsearchKeywordService.isEnabled()) {
            List<SearchResult> elasticsearchResults = elasticsearchKeywordService.search(query, topK);
            if (!elasticsearchResults.isEmpty()) {
                logger.info("Elasticsearch keyword retrieval finished, results={}", elasticsearchResults.size());
                return elasticsearchResults;
            }
            logger.info("Elasticsearch keyword retrieval returned no results, falling back to local keyword scorer");
        }

        List<String> tokens = buildKeywordTokens(query);
        if (tokens.isEmpty()) {
            return Collections.emptyList();
        }

        List<SearchResult> candidates = queryKeywordCandidates();
        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }

        for (SearchResult candidate : candidates) {
            float keywordScore = calculateKeywordScore(query, tokens, candidate.getContent(), candidate.getMetadata());
            candidate.setKeywordScore(keywordScore);
            candidate.setRecallSource("keyword");
        }

        return candidates.stream()
                .filter(result -> result.getKeywordScore() > 0.0f)
                .sorted(Comparator.comparing(SearchResult::getKeywordScore).reversed())
                .limit(topK)
                .toList();
    }

    private List<SearchResult> queryKeywordCandidates() {
        List<SearchResult> results = new ArrayList<>();
        int pageSize = Math.max(1, Math.min(keywordPageSize, keywordCandidateLimit));
        long offset = 0;

        while (results.size() < keywordCandidateLimit) {
            long limit = Math.min(pageSize, keywordCandidateLimit - results.size());
            QueryParam queryParam = QueryParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withExpr("id != \"\"")
                    .withOutFields(List.of("id", "content", "metadata"))
                    .withOffset(offset)
                    .withLimit(limit)
                    .build();

            R<QueryResults> response = milvusClient.query(queryParam);
            if (response.getStatus() != 0) {
                logger.warn("Keyword candidate query failed: {}", response.getMessage());
                break;
            }

            QueryResultsWrapper wrapper = new QueryResultsWrapper(response.getData());
            List<QueryResultsWrapper.RowRecord> records = wrapper.getRowRecords();
            if (records.isEmpty()) {
                break;
            }

            for (QueryResultsWrapper.RowRecord record : records) {
                SearchResult result = new SearchResult();
                Object id = record.get("id");
                Object content = record.get("content");
                Object metadata = record.get("metadata");
                result.setId(id == null ? "" : id.toString());
                result.setContent(content == null ? "" : content.toString());
                result.setMetadata(metadata == null ? "" : metadata.toString());
                results.add(result);
            }

            if (records.size() < limit) {
                break;
            }
            offset += records.size();
        }

        return results;
    }

    private List<String> buildKeywordTokens(String query) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }

        String normalized = normalizeText(query);
        Set<String> tokens = new LinkedHashSet<>();

        Matcher asciiMatcher = ASCII_TOKEN_PATTERN.matcher(normalized);
        while (asciiMatcher.find()) {
            String token = asciiMatcher.group();
            if (!isStopWord(token)) {
                tokens.add(token);
            }
        }

        Matcher cjkMatcher = CJK_PHRASE_PATTERN.matcher(normalized);
        while (cjkMatcher.find()) {
            String phrase = cjkMatcher.group();
            if (phrase.length() <= 32 && !isStopWord(phrase)) {
                tokens.add(phrase);
            }
            addNgrams(tokens, phrase, 2);
            addNgrams(tokens, phrase, 3);
        }

        for (String part : normalized.split("[\\s,，。.!！?？;；:：()（）\\[\\]【】\"'`]+")) {
            if (part.length() >= 2 && part.length() <= 64 && !isStopWord(part)) {
                tokens.add(part);
            }
        }

        return new ArrayList<>(tokens);
    }

    private void addNgrams(Set<String> tokens, String text, int n) {
        if (text.length() < n) {
            return;
        }
        for (int i = 0; i <= text.length() - n; i++) {
            String token = text.substring(i, i + n);
            if (!isStopWord(token)) {
                tokens.add(token);
            }
        }
    }

    private float calculateKeywordScore(String query, List<String> tokens, String content, String metadata) {
        String target = normalizeText((content == null ? "" : content) + " " + (metadata == null ? "" : metadata));
        if (target.isBlank()) {
            return 0.0f;
        }

        float score = 0.0f;
        String normalizedQuery = normalizeText(query);
        if (normalizedQuery.length() >= 2 && target.contains(normalizedQuery)) {
            score += 8.0f;
        }

        Set<String> matchedTokens = new HashSet<>();
        for (String token : tokens) {
            if (token.isBlank()) {
                continue;
            }
            int occurrences = countOccurrences(target, token);
            if (occurrences <= 0) {
                continue;
            }

            matchedTokens.add(token);
            float tokenWeight = token.length() >= 6 ? 2.4f : token.length() >= 3 ? 1.6f : 1.0f;
            score += tokenWeight * Math.min(occurrences, 5);
        }

        if (!tokens.isEmpty()) {
            score += 3.0f * matchedTokens.size() / tokens.size();
        }

        return score;
    }

    private int countOccurrences(String text, String token) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }

    private String normalizeText(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }

    private boolean isStopWord(String token) {
        return Set.of("the", "and", "for", "with", "this", "that", "from", "http", "https",
                "什么", "怎么", "如何", "一下", "这个", "那个", "内容", "帮我").contains(token);
    }

    @Setter
    @Getter
    public static class SearchResult {
        private String id;
        private String content;
        private float score;
        private String metadata;
        private String recallSource;
        private float vectorScore;
        private float keywordScore;
        private float hybridScore;
    }
}
