package org.example.service;

import com.google.gson.Gson;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResults;
import io.milvus.param.*;
import io.milvus.param.collection.*;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.SearchResultsWrapper;
import org.example.constant.SkillConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;

/**
 * Milvus skill vector service.
 * Manages a separate Milvus collection 'skills' for skill embeddings.
 */
@Service
public class SkillVectorService {

    private static final Logger logger = LoggerFactory.getLogger(SkillVectorService.class);
    private static final Gson gson = new Gson();

    @Autowired
    private MilvusServiceClient milvusClient;

    @Autowired
    private VectorEmbeddingService embeddingService;

    private final Object initLock = new Object();
    private volatile boolean initialized;

    @PostConstruct
    public void initialize() {
        synchronized (initLock) {
            if (initialized) return;
            try {
                if (!collectionExists()) {
                    createCollection();
                }
                loadCollection();
                initialized = true;
                logger.info("Skill vector service initialized, collection='{}'", SkillConstants.SKILLS_COLLECTION_NAME);
            } catch (Exception e) {
                logger.warn("Skill vector service init failed (will retry lazily): {}", e.getMessage());
            }
        }
    }

    private boolean collectionExists() {
        R<Boolean> response = milvusClient.hasCollection(
                HasCollectionParam.newBuilder()
                        .withCollectionName(SkillConstants.SKILLS_COLLECTION_NAME)
                        .build());
        return response.getStatus() == 0 && Boolean.TRUE.equals(response.getData());
    }

    private void createCollection() {
        logger.info("Creating skills collection '{}'...", SkillConstants.SKILLS_COLLECTION_NAME);

        FieldType idField = FieldType.newBuilder()
                .withName("id")
                .withDataType(DataType.VarChar)
                .withMaxLength(SkillConstants.ID_MAX_LENGTH)
                .withPrimaryKey(true)
                .build();

        FieldType vectorField = FieldType.newBuilder()
                .withName("vector")
                .withDataType(DataType.FloatVector)
                .withDimension(SkillConstants.VECTOR_DIM)
                .build();

        FieldType contentField = FieldType.newBuilder()
                .withName("content")
                .withDataType(DataType.VarChar)
                .withMaxLength(SkillConstants.CONTENT_MAX_LENGTH)
                .build();

        FieldType metadataField = FieldType.newBuilder()
                .withName("metadata")
                .withDataType(DataType.JSON)
                .build();

        CollectionSchemaParam schema = CollectionSchemaParam.newBuilder()
                .withEnableDynamicField(false)
                .addFieldType(idField)
                .addFieldType(vectorField)
                .addFieldType(contentField)
                .addFieldType(metadataField)
                .build();

        CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                .withCollectionName(SkillConstants.SKILLS_COLLECTION_NAME)
                .withDescription("Agent skills embedding collection")
                .withSchema(schema)
                .withShardsNum(2)
                .build();

        R<RpcStatus> response = milvusClient.createCollection(createParam);
        if (response.getStatus() != 0) {
            throw new RuntimeException("Failed to create skills collection: " + response.getMessage());
        }

        // Create index
        CreateIndexParam indexParam = CreateIndexParam.newBuilder()
                .withCollectionName(SkillConstants.SKILLS_COLLECTION_NAME)
                .withFieldName("vector")
                .withIndexType(IndexType.IVF_FLAT)
                .withMetricType(MetricType.L2)
                .withExtraParam("{\"nlist\":128}")
                .withSyncMode(Boolean.FALSE)
                .build();

        R<RpcStatus> indexResponse = milvusClient.createIndex(indexParam);
        if (indexResponse.getStatus() != 0) {
            throw new RuntimeException("Failed to create skills index: " + indexResponse.getMessage());
        }
        logger.info("Created skills collection '{}' with index", SkillConstants.SKILLS_COLLECTION_NAME);
    }

    private void loadCollection() {
        R<RpcStatus> response = milvusClient.loadCollection(
                LoadCollectionParam.newBuilder()
                        .withCollectionName(SkillConstants.SKILLS_COLLECTION_NAME)
                        .build());
        if (response.getStatus() != 0 && response.getStatus() != 65535) {
            logger.warn("Failed to load skills collection: {}", response.getMessage());
        }
    }

    private void ensureInitialized() {
        if (!initialized) initialize();
    }

    private String buildSkillContent(String name, String description, List<String> tags, String toolChainDescription) {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append(" ").append(description);
        if (tags != null && !tags.isEmpty()) {
            sb.append(" ").append(String.join(" ", tags));
        }
        if (toolChainDescription != null && !toolChainDescription.isEmpty()) {
            sb.append(" ").append(toolChainDescription);
        }
        return sb.toString();
    }

    public String addSkillEmbedding(String skillId, String name, String description,
                                     List<String> tags, String toolChainDescription,
                                     Map<String, Object> metadata) {
        ensureInitialized();
        loadCollection();

        String content = buildSkillContent(name, description, tags, toolChainDescription);
        List<Float> embedding = embeddingService.generateEmbedding(content);

        String vectorId = "skill_" + skillId;
        Map<String, Object> meta = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
        meta.put("skill_id", skillId);

        try {
            List<InsertParam.Field> fields = new ArrayList<>();
            fields.add(new InsertParam.Field("id", List.of(vectorId)));
            fields.add(new InsertParam.Field("vector", List.of(embedding)));
            fields.add(new InsertParam.Field("content", List.of(content)));
            fields.add(new InsertParam.Field("metadata", List.of(gson.toJsonTree(meta))));

            InsertParam insertParam = InsertParam.newBuilder()
                    .withCollectionName(SkillConstants.SKILLS_COLLECTION_NAME)
                    .withFields(fields)
                    .build();

            R<MutationResult> response = milvusClient.insert(insertParam);
            if (response.getStatus() != 0) {
                logger.warn("Insert skill embedding failed: {}", response.getMessage());
            }
            milvusClient.flush(FlushParam.newBuilder()
                    .addCollectionName(SkillConstants.SKILLS_COLLECTION_NAME)
                    .build());
            logger.info("Added skill embedding: skill_id={}, vector_id={}", skillId, vectorId);
        } catch (Exception e) {
            logger.error("Failed to add skill embedding: skill_id={}", skillId, e);
        }
        return vectorId;
    }

    public String updateSkillEmbedding(String skillId, String name, String description,
                                        List<String> tags, String toolChainDescription,
                                        Map<String, Object> metadata) {
        deleteSkillEmbedding(skillId);
        return addSkillEmbedding(skillId, name, description, tags, toolChainDescription, metadata);
    }

    public int deleteSkillEmbedding(String skillId) {
        ensureInitialized();
        loadCollection();
        try {
            String expr = String.format("id == \"skill_%s\"", skillId);
            DeleteParam deleteParam = DeleteParam.newBuilder()
                    .withCollectionName(SkillConstants.SKILLS_COLLECTION_NAME)
                    .withExpr(expr)
                    .build();
            R<MutationResult> response = milvusClient.delete(deleteParam);
            int deleted = response.getStatus() == 0 ? (int) response.getData().getDeleteCnt() : 0;
            logger.info("Deleted skill embedding: skill_id={}, count={}", skillId, deleted);
            return deleted;
        } catch (Exception e) {
            logger.warn("Delete skill embedding failed for {}: {}", skillId, e.getMessage());
            return 0;
        }
    }

    /**
     * Search for similar skills by semantic similarity.
     * Returns list of [skillId, score, metadata] arrays.
     */
    public List<Object[]> searchSimilarSkills(String query, int topK) {
        ensureInitialized();
        loadCollection();
        List<Object[]> results = new ArrayList<>();

        try {
            List<Float> queryEmbedding = embeddingService.generateEmbedding(query);

            SearchParam searchParam = SearchParam.newBuilder()
                    .withCollectionName(SkillConstants.SKILLS_COLLECTION_NAME)
                    .withMetricType(MetricType.L2)
                    .withOutFields(List.of("metadata"))
                    .withTopK(topK)
                    .withVectors(List.of(queryEmbedding))
                    .withVectorFieldName("vector")
                    .withParams("{\"nprobe\": 16}")
                    .build();

            R<SearchResults> response = milvusClient.search(searchParam);
            if (response.getStatus() != 0) {
                logger.warn("Skill search failed: {}", response.getMessage());
                return results;
            }

            SearchResultsWrapper wrapper = new SearchResultsWrapper(response.getData().getResults());
            for (int i = 0; i < wrapper.getRowRecords(0).size(); i++) {
                Map<String, Object> meta = (Map<String, Object>) wrapper.getFieldData("metadata", 0).get(i);
                String skillId = meta != null ? (String) meta.get("skill_id") : "";
                float distance = wrapper.getIDScore(0).get(i).getScore();
                double score = 1.0 / (1.0 + distance); // L2 to similarity
                results.add(new Object[]{skillId, score, meta});
            }
            logger.debug("Skill search: query={}, found={}", query, results.size());
        } catch (Exception e) {
            logger.error("Skill vector search failed: {}", e.getMessage());
        }
        return results;
    }
}
