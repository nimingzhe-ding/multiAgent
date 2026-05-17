package org.example.service;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import lombok.Getter;
import lombok.Setter;
import org.example.constant.MilvusConstants;
import org.example.dto.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 向量索引服务
 * 负责读取文件、生成向量、存储到 Milvus
 */
@Service
public class VectorIndexService {

    private static final Logger logger = LoggerFactory.getLogger(VectorIndexService.class);

    @Autowired
    private MilvusServiceClient milvusClient;

    @Autowired
    private VectorEmbeddingService embeddingService;

    @Autowired
    private DocumentChunkService chunkService;

    @Autowired
    private DocumentProcessingService documentProcessingService;

    @Value("${file.upload.path}")
    private String uploadPath;

    /**
     * 索引指定目录下的所有文件
     * 
     * @param directoryPath 目录路径（可选，默认使用配置的上传目录）
     * @return 索引结果  这里可以优化：定时重建目录下所有文件的索引
     */
    public IndexingResult indexDirectory(String directoryPath) {
        IndexingResult result = new IndexingResult();
        //这是创建一个对象
        result.setStartTime(LocalDateTime.now());
//设置时间，确保工程什么时候开始和结束，方便后续统计索引耗时等信息
        try {
            // 使用指定目录或默认上传目录
            String targetPath = (directoryPath != null && !directoryPath.trim().isEmpty()) 
                    ? directoryPath : uploadPath;
            //如果调用者指定目录（即不为空）就用指定目录，否则就使用系统录入目录（即上传目录）
            //参数优先级别：调用者指定 > 系统配置
            //我们的这个方法既能够支持用户动态指定索引目录，也能够支持默认索引上传目录，非常灵活。是一种参数优先配置回退的设计模式，增强了方法的通用性。
            //如果只是使用uploadPath作为索引目录，那么用户就无法动态指定其他目录进行索引了，这样就不够灵活了。也就是变成了强耦合，只能够索引固定目录，不利于扩展。
                Path dirPath = Paths.get(targetPath).normalize();
            //normalize()方法会将路径进行规范化处理，去除多余的路径分隔符和相对路径标识（如 . 和 ..），确保路径格式统一，避免后续处理中的路径解析错误。

            File directory = dirPath.toFile();
            
            if (!directory.exists() || !directory.isDirectory()) {
                throw new IllegalArgumentException("目录不存在或不是有效目录: " + targetPath);
            }

            result.setDirectoryPath(directory.getAbsolutePath());

            // 获取所有支持的文件
            File[] files = directory.listFiles((dir, name) -> 
                name.toLowerCase(Locale.ROOT).endsWith(".txt")
                        || name.toLowerCase(Locale.ROOT).endsWith(".md")
                        || name.toLowerCase(Locale.ROOT).endsWith(".markdown")
                        || name.toLowerCase(Locale.ROOT).endsWith(".doc")
                        || name.toLowerCase(Locale.ROOT).endsWith(".docx")
                        || name.toLowerCase(Locale.ROOT).endsWith(".pdf")
                        || name.toLowerCase(Locale.ROOT).endsWith(".png")
            );

            if (files == null || files.length == 0) {
                logger.warn("目录中没有找到支持的文件: {}", targetPath);
                result.setTotalFiles(0);
                result.setSuccess(true);
                result.setEndTime(LocalDateTime.now());
                return result;
            }

            result.setTotalFiles(files.length);
            logger.info("开始索引目录: {}, 找到 {} 个文件", targetPath, files.length);

            // 遍历并索引每个文件
            for (File file : files) {
                try {
                    indexSingleFile(file.getAbsolutePath());
                    result.incrementSuccessCount();
                    logger.info("✓ 文件索引成功: {}", file.getName());
                } catch (Exception e) {
                    result.incrementFailCount();
                    result.addFailedFile(file.getAbsolutePath(), e.getMessage());
                    logger.error("✗ 文件索引失败: {}", file.getName(), e);
                }
            }

            result.setSuccess(result.getFailCount() == 0);
            result.setEndTime(LocalDateTime.now());

            logger.info("目录索引完成: 总数={}, 成功={}, 失败={}", 
                result.getTotalFiles(), result.getSuccessCount(), result.getFailCount());

            return result;

        } catch (Exception e) {
            logger.error("索引目录失败", e);
            result.setSuccess(false);
            result.setErrorMessage(e.getMessage());
            result.setEndTime(LocalDateTime.now());
            return result;
        }
    }

    /**
     * 索引单个文件
     * 
     * @param filePath 文件路径
     * @throws Exception 索引失败时抛出异常
     */
    public void indexSingleFile(String filePath) throws Exception {
        Path path = Paths.get(filePath).normalize();
        File file = path.toFile();
        
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("文件不存在: " + filePath);
        }

        logger.info("开始索引文件: {}", path);

        // 1. 读取文件内容
        String content = documentProcessingService.readFullText(path.toString());
        logger.info("读取文件: {}, 内容长度: {} 字符", path, content.length());
        if (content.isBlank()) {
            logger.warn("文件没有可提取文本，跳过向量索引: {}", path);
            return;
        }

        // 2. 删除该文件的旧数据（如果存在）
        deleteExistingData(path.toString());

        // 3. 文档分片
        List<DocumentChunk> chunks = chunkService.chunkDocument(content, path.toString());
        logger.info("文档分片完成: {} -> {} 个分片", filePath, chunks.size());

        // 4. 为每个分片生成向量并插入 Milvus
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = chunks.get(i);
            
            try {
                // 生成向量
                List<Float> vector = embeddingService.generateEmbedding(chunk.getContent());

                // 构建元数据（包含文件信息）
                Map<String, Object> metadata = buildMetadata(path.toString(), chunk, chunks.size());

                // 插入到 Milvus
                insertToMilvus(chunk.getContent(), vector, metadata, chunk.getChunkIndex());
                
                logger.info("✓ 分片 {}/{} 索引成功", i + 1, chunks.size());

            } catch (Exception e) {
                logger.error("✗ 分片 {}/{} 索引失败", i + 1, chunks.size(), e);
                throw new RuntimeException("分片索引失败: " + e.getMessage(), e);
            }
        }

        logger.info("文件索引完成: {}, 共 {} 个分片", filePath, chunks.size());
    }

    public void deleteFileIndex(String filePath) {
        deleteExistingData(filePath);
    }

    /**
     * 删除文件的旧数据（根据 metadata._source）
     */
    private void deleteExistingData(String filePath) {
        try {
            // 使用统一的路径分隔符（正斜杠）用于Milvus存储，避免表达式解析错误
            // 将系统路径转换为统一格式
            String normalizedPath = normalizeSourceId(filePath);
            
            // 构建删除表达式：metadata["_source"] == "xxx"
            String expr = String.format("metadata[\"_source\"] == \"%s\"", escapeMilvusStringLiteral(normalizedPath));
            
            logger.info("准备删除旧数据，路径: {}, 表达式: {}", normalizedPath, expr);

            // 确保 collection 已加载（删除操作需要集合已加载）
            R<RpcStatus> loadResponse = milvusClient.loadCollection(
                LoadCollectionParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .build()
            );

            // 状态码 65535 表示集合已经加载，这不是错误
            if (loadResponse.getStatus() != 0 && loadResponse.getStatus() != 65535) {
                logger.warn("加载 collection 失败: {}", loadResponse.getMessage());
                return;
            }

            DeleteParam deleteParam = DeleteParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withExpr(expr)
                    .build();

            R<MutationResult> response = milvusClient.delete(deleteParam);

            if (response.getStatus() != 0) {
                logger.warn("删除旧数据时出现警告: {}", response.getMessage());
            } else {
                long deletedCount = response.getData().getDeleteCnt();
                logger.info("✓ 已删除文件的旧数据: {}, 删除记录数: {}", normalizedPath, deletedCount);
            }

        } catch (Exception e) {
            logger.warn("删除旧数据失败（可能是首次索引）: {}", e.getMessage());
        }
    }

    /**
     * 构建元数据（包含文件信息）
     */
    private Map<String, Object> buildMetadata(String filePath, DocumentChunk chunk, int totalChunks) {
        Map<String, Object> metadata = new HashMap<>();

        if (isHttpUrl(filePath)) {
            String normalizedPath = normalizeSourceId(filePath);
            String fileNameStr = extractSourceName(filePath);
            metadata.put("_source", normalizedPath);
            metadata.put("_extension", extractExtension(fileNameStr));
            metadata.put("_file_name", fileNameStr);
            metadata.put("chunkIndex", chunk.getChunkIndex());
            metadata.put("totalChunks", totalChunks);
            if (chunk.getTitle() != null && !chunk.getTitle().isEmpty()) {
                metadata.put("title", chunk.getTitle());
            }
            return metadata;
        }
        
        // 标准化路径：使用统一的路径分隔符（正斜杠）用于存储，确保跨平台一致性
        Path path = Paths.get(filePath).normalize();
        String normalizedPath = normalizeSourceId(filePath);
        
        // 文件信息
        String fileNameStr = extractSourceName(filePath);
        String extension = "";
        extension = extractExtension(fileNameStr);
        
            metadata.put("_source", normalizedPath);
            metadata.put("_extension", extension);
            metadata.put("_file_name", fileNameStr);

        // 分片信息
        metadata.put("chunkIndex", chunk.getChunkIndex());
        metadata.put("totalChunks", totalChunks);
        
        // 标题信息
        if (chunk.getTitle() != null && !chunk.getTitle().isEmpty()) {
            metadata.put("title", chunk.getTitle());
        }
        
        return metadata;
    }

    /**
     * 插入向量到 Milvus
     */
    private void insertToMilvus(String content, List<Float> vector, 
                                Map<String, Object> metadata, int chunkIndex) throws Exception {
        try {
            // 确保 collection 已加载
            R<RpcStatus> loadResponse = milvusClient.loadCollection(
                LoadCollectionParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .build()
            );

            if (loadResponse.getStatus() != 0 && loadResponse.getStatus() != 65535) {
                throw new RuntimeException("加载 collection 失败: " + loadResponse.getMessage());
            }

            // 生成唯一 ID（使用 _source + 分片索引）
            String source = (String) metadata.get("_source");
            String id = UUID.nameUUIDFromBytes((source + "_" + chunkIndex).getBytes()).toString();

            // 构建字段数据
            List<InsertParam.Field> fields = new ArrayList<>();
            
            // ID 字段
            fields.add(new InsertParam.Field("id", Collections.singletonList(id)));
            
            // content 字段
            fields.add(new InsertParam.Field("content", Collections.singletonList(content)));
            
            // vector 字段
            fields.add(new InsertParam.Field("vector", Collections.singletonList(vector)));
            
            // metadata 字段（JSON 对象）
            com.google.gson.Gson gson = new com.google.gson.Gson();
            com.google.gson.JsonObject metadataJson = gson.toJsonTree(metadata).getAsJsonObject();
            fields.add(new InsertParam.Field("metadata", Collections.singletonList(metadataJson)));

            // 构建插入参数
            InsertParam insertParam = InsertParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withFields(fields)
                    .build();

            // 执行插入
            R<MutationResult> insertResponse = milvusClient.insert(insertParam);

            if (insertResponse.getStatus() != 0) {
                throw new RuntimeException("插入向量失败: " + insertResponse.getMessage());
            }

            logger.debug("向量插入成功: id={}, source={}, chunk={}", id, source, chunkIndex);

        } catch (Exception e) {
            logger.error("插入向量到 Milvus 失败", e);
            throw e;
        }
    }

    /**
     * Index raw text content into the vector store.
     * Core indexing function shared by file indexing and URL/web content indexing.
     *
     * @param sourceId   Unique identifier (e.g. file path or URL)
     * @param content    Full text content to index
     * @param sourceName Human-readable display name
     * @return Number of vector chunks created
     */
    public int indexText(String sourceId, String content, String sourceName) throws Exception {
        if (content == null || content.isBlank()) {
            logger.warn("Empty content for sourceId={}, skipping", sourceId);
            return 0;
        }

        // Normalize source path or URL
        String normalizedSource = normalizeSourceId(sourceId);

        // Remove old vectors for this source (idempotent re-index)
        deleteExistingData(normalizedSource);

        // Split into chunks
        List<DocumentChunk> chunks = chunkService.chunkDocument(content, normalizedSource);
        String name = (sourceName != null && !sourceName.isEmpty()) ? sourceName : normalizedSource;
        logger.info("Split source {} into {} chunks", sourceId, chunks.size());

        if (chunks.isEmpty()) {
            logger.warn("Source produced no chunks: {}", sourceId);
            return 0;
        }

        // Build metadata with display name override
        for (DocumentChunk chunk : chunks) {
            Map<String, Object> metadata = buildMetadata(normalizedSource, chunk, chunks.size());
            metadata.put("_file_name", name);

            List<Float> vector = embeddingService.generateEmbedding(chunk.getContent());
            insertToMilvusWithMeta(chunk.getContent(), vector, metadata, chunk.getChunkIndex());
        }

        logger.info("Indexed source {}, chunks={}", sourceId, chunks.size());
        return chunks.size();
    }

    private String normalizeSourceId(String sourceId) {
        if (sourceId == null) {
            return "";
        }
        if (isHttpUrl(sourceId)) {
            return sourceId.trim();
        }
        return Paths.get(sourceId).normalize().toString().replace(File.separator, "/");
    }

    private boolean isHttpUrl(String sourceId) {
        String lower = sourceId == null ? "" : sourceId.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private String extractSourceName(String sourceId) {
        if (sourceId == null || sourceId.isBlank()) {
            return "";
        }
        if (isHttpUrl(sourceId)) {
            try {
                URI uri = URI.create(sourceId);
                String path = uri.getPath();
                if (path != null && !path.isBlank()) {
                    int slash = path.lastIndexOf('/');
                    String name = slash >= 0 ? path.substring(slash + 1) : path;
                    if (!name.isBlank()) {
                        return name;
                    }
                }
                return uri.getHost() != null ? uri.getHost() : sourceId;
            } catch (Exception e) {
                return sourceId;
            }
        }

        Path path = Paths.get(sourceId).normalize();
        Path fileName = path.getFileName();
        return fileName != null ? fileName.toString() : "";
    }

    private String extractExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(dotIndex) : "";
    }

    private String escapeMilvusStringLiteral(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Insert to Milvus with pre-built metadata (reuses existing insert logic).
     */
    private void insertToMilvusWithMeta(String content, List<Float> vector,
                                         Map<String, Object> metadata, int chunkIndex) throws Exception {
        try {
            R<RpcStatus> loadResponse = milvusClient.loadCollection(
                LoadCollectionParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .build());

            if (loadResponse.getStatus() != 0 && loadResponse.getStatus() != 65535) {
                throw new RuntimeException("加载 collection 失败: " + loadResponse.getMessage());
            }

            String source = (String) metadata.get("_source");
            String id = UUID.nameUUIDFromBytes((source + "_" + chunkIndex).getBytes()).toString();

            List<InsertParam.Field> fields = new ArrayList<>();
            fields.add(new InsertParam.Field("id", Collections.singletonList(id)));
            fields.add(new InsertParam.Field("content", Collections.singletonList(content)));
            fields.add(new InsertParam.Field("vector", Collections.singletonList(vector)));

            com.google.gson.Gson gson = new com.google.gson.Gson();
            com.google.gson.JsonObject metadataJson = gson.toJsonTree(metadata).getAsJsonObject();
            fields.add(new InsertParam.Field("metadata", Collections.singletonList(metadataJson)));

            InsertParam insertParam = InsertParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withFields(fields)
                    .build();

            R<MutationResult> insertResponse = milvusClient.insert(insertParam);
            if (insertResponse.getStatus() != 0) {
                throw new RuntimeException("插入向量失败: " + insertResponse.getMessage());
            }

        } catch (Exception e) {
            logger.error("插入向量到 Milvus 失败", e);
            throw e;
        }
    }

    /**
     * 索引结果类
     */
    @Getter
    public static class IndexingResult {
        @Setter
        private boolean success;
        @Setter
        private String directoryPath;
        @Setter
        private int totalFiles;
        private int successCount;
        private int failCount;
        @Setter
        private LocalDateTime startTime;
        @Setter
        private LocalDateTime endTime;
        @Setter
        private String errorMessage;
        private Map<String, String> failedFiles = new HashMap<>();

        public void incrementSuccessCount() {
            this.successCount++;
        }

        public void incrementFailCount() {
            this.failCount++;
        }

        public long getDurationMs() {
            if (startTime != null && endTime != null) {
                return java.time.Duration.between(startTime, endTime).toMillis();
            }
            return 0;
        }

        public void addFailedFile(String filePath, String error) {
            this.failedFiles.put(filePath, error);
        }
    }
}
