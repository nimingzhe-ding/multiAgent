package org.example.controller;

import org.example.config.FileUploadConfig;
import org.example.dto.FileUploadRes;
import org.example.security.AuthenticatedUser;
import org.example.service.KnowledgeSourceService;
import org.example.service.VectorIndexService;
import org.example.service.WebExtractionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.multipart.MultipartFile;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@RestController
public class FileUploadController {

    private static final Logger logger = LoggerFactory.getLogger(FileUploadController.class);
    private static final Set<String> TEXT_INDEXABLE_EXTENSIONS =
            Set.of("txt", "md", "markdown", "doc", "docx", "pdf", "png", "jpg", "jpeg", "bmp", "gif");
    private static final Gson GSON = new Gson();
    private static final Type WEB_SOURCE_LIST_TYPE = new TypeToken<List<KnowledgeFile>>() {}.getType();
    private static final Path WEB_SOURCE_STORE = Paths.get("data", "knowledge-sources.json");
    private final Object webSourceLock = new Object();

    @Autowired
    private FileUploadConfig fileUploadConfig;

    @Autowired
    private VectorIndexService vectorIndexService;

    @Autowired
    private WebExtractionService webExtractionService;

    @Autowired
    private KnowledgeSourceService knowledgeSourceService;

    @GetMapping("/api/knowledge/files")
    public ResponseEntity<?> listKnowledgeFiles(@RequestParam(required = false) String visibility,
                                                @RequestParam(required = false) String category,
                                                @RequestParam(required = false) String tag,
                                                @RequestParam(required = false, name = "q") String query,
                                                @AuthenticationPrincipal AuthenticatedUser user) {
        try {
            Path uploadDir = Paths.get(fileUploadConfig.getPath()).toAbsolutePath().normalize();
            Files.createDirectories(uploadDir);
            syncLegacyKnowledgeSources(uploadDir);
            List<KnowledgeFile> combinedFiles = knowledgeSourceService
                    .listSources(user.userId(), visibility, category, tag, query)
                    .stream()
                    .map(this::toKnowledgeFile)
                    .toList();

            ApiResponse<List<KnowledgeFile>> response = new ApiResponse<>();
            response.setCode(200);
            response.setMessage("success");
            response.setData(combinedFiles);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            ApiResponse<String> errorResponse = new ApiResponse<>();
            errorResponse.setCode(500);
            errorResponse.setMessage("list knowledge files failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    @PostMapping(value = {"/api/upload", "/api/knowledge/files"}, consumes = "multipart/form-data")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file,
                                    @RequestParam(required = false) String visibility,
                                    @RequestParam(required = false) String category,
                                    @RequestParam(required = false) String tags,
                                    @AuthenticationPrincipal AuthenticatedUser user) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(errorResponse(400, "文件不能为空"));
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            return ResponseEntity.badRequest().body(errorResponse(400, "文件名不能为空"));
        }

        String safeFilename = sanitizeFilename(originalFilename);
        if (safeFilename.isEmpty()) {
            return ResponseEntity.badRequest().body(errorResponse(400, "文件名无效"));
        }

        String fileExtension = getFileExtension(safeFilename);
        if (!isAllowedExtension(fileExtension)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(errorResponse(400, "不支持的文件类型: " + fileExtension + "，允许的类型: " + fileUploadConfig.getAllowedExtensions()));
        }

        try {
            Path uploadDir = Paths.get(fileUploadConfig.getPath()).toAbsolutePath().normalize();
            Files.createDirectories(uploadDir);

            Path filePath = uploadDir.resolve(safeFilename).normalize();
            if (!filePath.startsWith(uploadDir)) {
                return ResponseEntity.badRequest().body("invalid file path");
            }

            Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);
            logger.info("File uploaded: {}", filePath);

            String sourceKey = normalizeSourceKey(filePath);
            String effectiveVisibility = KnowledgeSourceService.normalizeVisibility(visibility);
            String effectiveCategory = category == null || category.isBlank() ? "general" : category.trim();
            List<String> tagList = KnowledgeSourceService.normalizeTags(tags);
            knowledgeSourceService.upsertSource(new KnowledgeSourceService.SourceInput(
                    sourceKey,
                    safeFilename,
                    user.userId(),
                    effectiveVisibility,
                    effectiveCategory,
                    tagList,
                    "file",
                    fileExtension,
                    file.getSize(),
                    safeFilename,
                    KnowledgeSourceService.STATUS_PENDING,
                    "waiting for index",
                    0,
                    Files.getLastModifiedTime(filePath).toInstant().toString()));

            boolean indexed = false;
            String indexMessage = "indexed successfully";
            int chunkCount = 0;
            try {
                logger.info("Indexing uploaded file: {}", filePath);
                vectorIndexService.indexSingleFile(filePath.toString(), knowledgeMetadata(user.userId(), effectiveVisibility, effectiveCategory, tagList, "file"));
                indexed = true;
                chunkCount = 1;
                knowledgeSourceService.updateIndexStatus(sourceKey, KnowledgeSourceService.STATUS_INDEXED, indexMessage, chunkCount);
                logger.info("Index created for uploaded file: {}", filePath);
            } catch (Exception e) {
                indexMessage = "index failed: " + e.getMessage();
                knowledgeSourceService.updateIndexStatus(sourceKey, KnowledgeSourceService.STATUS_INDEX_FAILED, indexMessage, 0);
                logger.error("Vector indexing failed for file: {}, error: {}", filePath, e.getMessage(), e);
            }

            FileUploadRes response = new FileUploadRes(
                    safeFilename,
                    filePath.toString(),
                    file.getSize(),
                    indexed,
                    indexMessage
            );

            ApiResponse<FileUploadRes> apiResponse = new ApiResponse<>();
            apiResponse.setCode(200);
            apiResponse.setMessage("success");
            apiResponse.setData(response);

            return ResponseEntity.ok(apiResponse);
        } catch (IOException e) {
            ApiResponse<String> errorResponse = new ApiResponse<>();
            errorResponse.setCode(500);
            errorResponse.setMessage("file upload failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    @PostMapping("/api/knowledge/files/{fileName}/reindex")
    public ResponseEntity<?> reindexKnowledgeFile(@PathVariable String fileName,
                                                  @AuthenticationPrincipal AuthenticatedUser user) {
        String safeFilename = sanitizeFilename(fileName);
        if (safeFilename.isEmpty()) {
            return ResponseEntity.badRequest().body(errorResponse(400, "invalid file name"));
        }

        String extension = getFileExtension(safeFilename);
        if (!TEXT_INDEXABLE_EXTENSIONS.contains(extension)) {
            return ResponseEntity.badRequest().body(errorResponse(400, "file type cannot be indexed"));
        }

        try {
            Map<String, Object> source = knowledgeSourceService.findVisibleByNameOrSource(safeFilename, user.userId());
            if (source != null && !canModifySource(source, user.userId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorResponse(403, "permission denied"));
            }

            Path filePath = resolveUploadFile(safeFilename);
            if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse(404, "file not found"));
            }

            String sourceKey = normalizeSourceKey(filePath);
            String effectiveVisibility = source == null ? KnowledgeSourceService.VISIBILITY_SHARED : source.getOrDefault("visibility", KnowledgeSourceService.VISIBILITY_SHARED).toString();
            String effectiveCategory = source == null ? "general" : source.getOrDefault("category", "general").toString();
            @SuppressWarnings("unchecked")
            List<String> tagList = source == null ? List.of() : (List<String>) source.getOrDefault("tags", List.of());
            vectorIndexService.indexSingleFile(filePath.toString(), knowledgeMetadata(user.userId(), effectiveVisibility, effectiveCategory, tagList, "file"));
            knowledgeSourceService.updateIndexStatus(sourceKey, KnowledgeSourceService.STATUS_INDEXED, "indexed successfully", 1);
            ApiResponse<String> response = new ApiResponse<>();
            response.setCode(200);
            response.setMessage("success");
            response.setData(safeFilename);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Reindex knowledge file failed: {}", safeFilename, e);
            try {
                knowledgeSourceService.updateIndexStatus(normalizeSourceKey(resolveUploadFile(safeFilename)), KnowledgeSourceService.STATUS_INDEX_FAILED, "reindex failed: " + e.getMessage(), 0);
            } catch (Exception ignored) {
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(errorResponse(500, "reindex failed: " + e.getMessage()));
        }
    }

    @PostMapping("/api/knowledge/url")
    public ResponseEntity<?> ingestUrl(@RequestBody Map<String, String> request,
                                       @AuthenticationPrincipal AuthenticatedUser user) {
        String url = request.get("url");
        if (url == null || url.isBlank()) {
            return ResponseEntity.badRequest().body(errorResponse(400, "url cannot be empty"));
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ResponseEntity.badRequest().body(errorResponse(400, "url must start with http:// or https://"));
        }

        try {
            String cookie = request.get("cookie");
            String effectiveVisibility = KnowledgeSourceService.normalizeVisibility(request.get("visibility"));
            String effectiveCategory = request.get("category") == null || request.get("category").isBlank() ? "general" : request.get("category").trim();
            List<String> tagList = KnowledgeSourceService.normalizeTags(request.get("tags"));
            String[] result = webExtractionService.extractTextFromUrl(url, 30, cookie);
            String textContent = result[0];
            String pageTitle = result[1];
            String sourceName = request.getOrDefault("title", pageTitle != null ? pageTitle : url);

            knowledgeSourceService.upsertSource(new KnowledgeSourceService.SourceInput(
                    url,
                    sourceName,
                    user.userId(),
                    effectiveVisibility,
                    effectiveCategory,
                    tagList,
                    "url",
                    "url",
                    textContent == null ? 0 : textContent.length(),
                    url,
                    KnowledgeSourceService.STATUS_PENDING,
                    "waiting for index",
                    0,
                    Instant.now().toString()));

            if (textContent == null || textContent.isBlank()) {
                vectorIndexService.deleteFileIndex(url);
                removeWebSource(url);
                knowledgeSourceService.updateIndexStatus(url, KnowledgeSourceService.STATUS_INDEX_FAILED, "empty page content", 0);
                String tip = pageTitle != null && !pageTitle.isBlank()
                        ? "（页面标题: " + pageTitle + "）"
                        : "";
                String suggestion = "请尝试: 1) 提供登录Cookie; 2) 查看服务器日志确认是否启用浏览器渲染; 3) 确认目标网站是否可正常访问";
                return ResponseEntity.badRequest()
                        .body(errorResponse(400, "网页内容为空，无法索引。" + tip + suggestion));
            }

            int chunkCount = vectorIndexService.indexText(url, textContent, sourceName,
                    knowledgeMetadata(user.userId(), effectiveVisibility, effectiveCategory, tagList, "url"));
            knowledgeSourceService.updateIndexStatus(url, KnowledgeSourceService.STATUS_INDEXED, "indexed successfully", chunkCount);

            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("url", url);
            data.put("title", sourceName);
            data.put("chunk_count", chunkCount);

            saveWebSource(url, sourceName, textContent.length(), chunkCount);

            ApiResponse<Map<String, Object>> response = new ApiResponse<>();
            response.setCode(200);
            response.setMessage("success");
            response.setData(data);
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            logger.error("URL ingest failed: {}", url, e);
            try {
                knowledgeSourceService.updateIndexStatus(url, KnowledgeSourceService.STATUS_INDEX_FAILED, e.getMessage(), 0);
            } catch (Exception ignored) {
            }
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("Chrome") || msg.contains("Edge") || msg.contains("executable not found")
                    || msg.contains("browser")) {
                return ResponseEntity.status(500)
                        .body(errorResponse(500, "浏览器环境缺失: " + msg));
            }
            if (msg.contains("timeout") || msg.contains("Timeout") || msg.contains("timed out")) {
                return ResponseEntity.status(408).body(errorResponse(408, "网页请求超时: " + msg));
            }
            return ResponseEntity.status(500).body(errorResponse(500, "网页抓取失败: " + msg));
        } catch (Exception e) {
            logger.error("URL ingest failed: {}", url, e);
            try {
                knowledgeSourceService.updateIndexStatus(url, KnowledgeSourceService.STATUS_INDEX_FAILED, e.getMessage(), 0);
            } catch (Exception ignored) {
            }
            return ResponseEntity.status(500).body(errorResponse(500, "网页抓取失败: " + e.getMessage()));
        }
    }

    @GetMapping("/api/knowledge/files/{fileName}")
    public ResponseEntity<?> getKnowledgeFile(@PathVariable String fileName,
                                              @AuthenticationPrincipal AuthenticatedUser user) {
        try {
            Map<String, Object> source = knowledgeSourceService.findVisibleByNameOrSource(fileName, user.userId());
            if (source == null) {
                return ResponseEntity.status(404).body(errorResponse(404, "File not found: " + fileName));
            }

            Path uploadDir = Paths.get(fileUploadConfig.getPath()).toAbsolutePath().normalize();
            Path filePath = uploadDir.resolve(fileName).normalize();
            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.putAll(source);
            if (filePath.startsWith(uploadDir) && Files.exists(filePath) && Files.isRegularFile(filePath)) {
                data.put("file_path", filePath.toString());
                data.put("preview", previewFile(filePath));
            }

            ApiResponse<Map<String, Object>> response = new ApiResponse<>();
            response.setCode(200);
            response.setMessage("success");
            response.setData(data);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Get knowledge file failed: {}", fileName, e);
            return ResponseEntity.status(500).body(errorResponse(500, e.getMessage()));
        }
    }

    @DeleteMapping("/api/knowledge/files/{fileName}")
    public ResponseEntity<?> deleteKnowledgeFile(@PathVariable String fileName,
                                                 @AuthenticationPrincipal AuthenticatedUser user) {
        String safeFilename = sanitizeFilename(fileName);
        if (safeFilename.isEmpty()) {
            return ResponseEntity.badRequest().body(errorResponse(400, "invalid file name"));
        }

        try {
            Map<String, Object> source = knowledgeSourceService.findVisibleByNameOrSource(safeFilename, user.userId());
            if (source == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse(404, "file not found"));
            }
            if (!canModifySource(source, user.userId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorResponse(403, "permission denied"));
            }

            String sourceKey = source.get("source_key").toString();
            vectorIndexService.deleteFileIndex(sourceKey);
            if ("file".equals(source.get("source_type"))) {
                Path filePath = resolveUploadFile(safeFilename);
                if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
                    Files.delete(filePath);
                }
            } else if ("url".equals(source.get("source_type"))) {
                removeWebSource(sourceKey);
            }
            knowledgeSourceService.deleteIfOwnedOrShared(safeFilename, user.userId());

            ApiResponse<String> response = new ApiResponse<>();
            response.setCode(200);
            response.setMessage("success");
            response.setData(safeFilename);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Delete knowledge file failed: {}", safeFilename, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(errorResponse(500, "delete failed: " + e.getMessage()));
        }
    }

    public static class ApiResponse<T> {
        private int code;
        private String message;
        private T data;

        public int getCode() {
            return code;
        }

        public void setCode(int code) {
            this.code = code;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public T getData() {
            return data;
        }

        public void setData(T data) {
            this.data = data;
        }
    }

    private String getFileExtension(String filename) {
        int lastIndexOf = filename.lastIndexOf(".");
        if (lastIndexOf == -1) {
            return "";
        }
        return filename.substring(lastIndexOf + 1).toLowerCase(Locale.ROOT);
    }

    private String sanitizeFilename(String filename) {
        String normalizedName = filename.replace('\\', '/');
        Path fileName = Paths.get(normalizedName).getFileName();
        if (fileName == null) {
            return "";
        }
        String safeName = fileName.toString().trim();
        if (safeName.equals(".") || safeName.equals("..") || safeName.contains("/") || safeName.contains("\\")) {
            return "";
        }
        return safeName;
    }

    private boolean isAllowedExtension(String extension) {
        String allowedExtensions = fileUploadConfig.getAllowedExtensions();
        if (allowedExtensions == null || allowedExtensions.isEmpty()) {
            return false;
        }
        List<String> allowedList = Arrays.stream(allowedExtensions.split(","))
                .map(String::trim)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .toList();
        return allowedList.contains(extension.toLowerCase(Locale.ROOT));
    }

    private Path resolveUploadFile(String safeFilename) throws IOException {
        Path uploadDir = Paths.get(fileUploadConfig.getPath()).toAbsolutePath().normalize();
        Files.createDirectories(uploadDir);
        Path filePath = uploadDir.resolve(safeFilename).normalize();
        if (!filePath.startsWith(uploadDir)) {
            throw new IllegalArgumentException("invalid file path");
        }
        return filePath;
    }

    private ApiResponse<String> errorResponse(int code, String message) {
        ApiResponse<String> response = new ApiResponse<>();
        response.setCode(code);
        response.setMessage(message);
        return response;
    }

    private String normalizeSourceKey(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private Map<String, Object> knowledgeMetadata(String userId,
                                                  String visibility,
                                                  String category,
                                                  List<String> tags,
                                                  String sourceType) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("owner_user_id", userId);
        metadata.put("visibility", KnowledgeSourceService.normalizeVisibility(visibility));
        metadata.put("category", category == null || category.isBlank() ? "general" : category);
        metadata.put("tags", tags == null ? List.of() : tags);
        metadata.put("source_type", sourceType);
        return metadata;
    }

    private boolean canModifySource(Map<String, Object> source, String userId) {
        Object owner = source.get("owner_user_id");
        return owner == null || owner.toString().isBlank() || owner.equals(userId);
    }

    private String previewFile(Path filePath) {
        try {
            String extension = getFileExtension(filePath.getFileName().toString());
            if (!Set.of("txt", "md", "markdown").contains(extension)) {
                return "";
            }
            String content = Files.readString(filePath);
            return content.length() > 8000 ? content.substring(0, 8000) : content;
        } catch (Exception e) {
            return "";
        }
    }

    private void syncLegacyKnowledgeSources(Path uploadDir) {
        try (Stream<Path> stream = Files.list(uploadDir)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                try {
                    String name = path.getFileName().toString();
                    String sourceKey = normalizeSourceKey(path);
                    if (knowledgeSourceService.findBySourceKey(sourceKey) == null) {
                        knowledgeSourceService.upsertSource(new KnowledgeSourceService.SourceInput(
                                sourceKey,
                                name,
                                null,
                                KnowledgeSourceService.VISIBILITY_SHARED,
                                "general",
                                List.of(),
                                "file",
                                getFileExtension(name),
                                Files.size(path),
                                uploadDir.relativize(path).toString().replace('\\', '/'),
                                KnowledgeSourceService.STATUS_INDEXED,
                                "legacy source",
                                0,
                                Files.getLastModifiedTime(path).toInstant().toString()));
                    }
                } catch (Exception e) {
                    logger.warn("Failed to sync legacy knowledge file {}: {}", path, e.getMessage());
                }
            });

            for (KnowledgeFile source : loadWebSources()) {
                if (knowledgeSourceService.findBySourceKey(source.getPath()) == null) {
                    knowledgeSourceService.upsertSource(new KnowledgeSourceService.SourceInput(
                            source.getPath(),
                            source.getName(),
                            null,
                            KnowledgeSourceService.VISIBILITY_SHARED,
                            "general",
                            List.of(),
                            "url",
                            "url",
                            source.getSize(),
                            source.getRelativePath(),
                            KnowledgeSourceService.STATUS_INDEXED,
                            "legacy web source",
                            source.getChunkCount(),
                            source.getLastModified()));
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to sync legacy knowledge sources: {}", e.getMessage());
        }
    }

    private KnowledgeFile toKnowledgeFile(Map<String, Object> row) {
        KnowledgeFile file = new KnowledgeFile();
        file.setName((String) row.getOrDefault("name", ""));
        file.setPath((String) row.getOrDefault("source_key", ""));
        file.setRelativePath((String) row.getOrDefault("relative_path", ""));
        file.setExtension((String) row.getOrDefault("extension", ""));
        file.setSize(((Number) row.getOrDefault("size", 0L)).longValue());
        file.setLastModified((String) row.getOrDefault("last_modified", row.getOrDefault("updated_at", "")));
        file.setIndexable(TEXT_INDEXABLE_EXTENSIONS.contains(file.getExtension()) || "url".equals(row.get("source_type")));
        file.setSourceType((String) row.getOrDefault("source_type", "file"));
        file.setChunkCount(((Number) row.getOrDefault("chunk_count", 0)).intValue());
        file.setVisibility((String) row.getOrDefault("visibility", KnowledgeSourceService.VISIBILITY_SHARED));
        file.setCategory((String) row.getOrDefault("category", "general"));
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) row.getOrDefault("tags", List.of());
        file.setTags(tags);
        file.setIndexStatus((String) row.getOrDefault("index_status", KnowledgeSourceService.STATUS_PENDING));
        file.setIndexMessage((String) row.getOrDefault("index_message", ""));
        file.setOwnerUserId((String) row.get("owner_user_id"));
        return file;
    }

    private KnowledgeFile toKnowledgeFile(Path uploadDir, Path path) {
        try {
            String name = path.getFileName().toString();
            String extension = getFileExtension(name);
            KnowledgeFile file = new KnowledgeFile();
            file.setName(name);
            file.setPath(path.toString());
            file.setRelativePath(uploadDir.relativize(path).toString().replace('\\', '/'));
            file.setExtension(extension);
            file.setSize(Files.size(path));
            file.setLastModified(Files.getLastModifiedTime(path).toInstant().toString());
            file.setIndexable(TEXT_INDEXABLE_EXTENSIONS.contains(extension));
            file.setSourceType("file");
            return file;
        } catch (IOException e) {
            throw new RuntimeException("failed to read file metadata: " + path, e);
        }
    }

    private List<KnowledgeFile> loadWebSources() {
        synchronized (webSourceLock) {
            try {
                if (!Files.exists(WEB_SOURCE_STORE)) {
                    return List.of();
                }
                String json = Files.readString(WEB_SOURCE_STORE);
                if (json == null || json.isBlank()) {
                    return List.of();
                }
                List<KnowledgeFile> sources = GSON.fromJson(json, WEB_SOURCE_LIST_TYPE);
                return sources != null ? sources : List.of();
            } catch (Exception e) {
                logger.warn("Failed to load web knowledge sources: {}", e.getMessage());
                return List.of();
            }
        }
    }

    private void saveWebSource(String url, String title, long textSize, int chunkCount) {
        synchronized (webSourceLock) {
            try {
                Files.createDirectories(WEB_SOURCE_STORE.getParent());
                List<KnowledgeFile> sources = new ArrayList<>(loadWebSources());
                sources.removeIf(source -> url.equals(source.getPath()));

                KnowledgeFile source = new KnowledgeFile();
                source.setName((title != null && !title.isBlank()) ? title : url);
                source.setPath(url);
                source.setRelativePath(url);
                source.setExtension("url");
                source.setSize(textSize);
                source.setLastModified(Instant.now().toString());
                source.setIndexable(true);
                source.setSourceType("url");
                source.setChunkCount(chunkCount);

                sources.add(source);
                Files.writeString(WEB_SOURCE_STORE, GSON.toJson(sources));
            } catch (Exception e) {
                logger.warn("Failed to save web knowledge source {}: {}", url, e.getMessage());
            }
        }
    }

    private void removeWebSource(String url) {
        synchronized (webSourceLock) {
            try {
                if (!Files.exists(WEB_SOURCE_STORE)) {
                    return;
                }
                List<KnowledgeFile> sources = new ArrayList<>(loadWebSources());
                if (sources.removeIf(source -> url.equals(source.getPath()))) {
                    Files.writeString(WEB_SOURCE_STORE, GSON.toJson(sources));
                }
            } catch (Exception e) {
                logger.warn("Failed to remove web knowledge source {}: {}", url, e.getMessage());
            }
        }
    }

    public static class KnowledgeFile {
        private String name;
        private String path;
        private String relativePath;
        private String extension;
        private long size;
        private String lastModified;
        private boolean indexable;
        private String sourceType;
        private int chunkCount;
        private String visibility;
        private String category;
        private List<String> tags = List.of();
        private String indexStatus;
        private String indexMessage;
        private String ownerUserId;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getRelativePath() {
            return relativePath;
        }

        public void setRelativePath(String relativePath) {
            this.relativePath = relativePath;
        }

        public String getExtension() {
            return extension;
        }

        public void setExtension(String extension) {
            this.extension = extension;
        }

        public long getSize() {
            return size;
        }

        public void setSize(long size) {
            this.size = size;
        }

        public String getLastModified() {
            return lastModified;
        }

        public void setLastModified(String lastModified) {
            this.lastModified = lastModified;
        }

        public boolean isIndexable() {
            return indexable;
        }

        public void setIndexable(boolean indexable) {
            this.indexable = indexable;
        }

        public String getSourceType() {
            return sourceType;
        }

        public void setSourceType(String sourceType) {
            this.sourceType = sourceType;
        }

        public int getChunkCount() {
            return chunkCount;
        }

        public void setChunkCount(int chunkCount) {
            this.chunkCount = chunkCount;
        }

        public String getVisibility() {
            return visibility;
        }

        public void setVisibility(String visibility) {
            this.visibility = visibility;
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        public List<String> getTags() {
            return tags;
        }

        public void setTags(List<String> tags) {
            this.tags = tags == null ? List.of() : tags;
        }

        public String getIndexStatus() {
            return indexStatus;
        }

        public void setIndexStatus(String indexStatus) {
            this.indexStatus = indexStatus;
        }

        public String getIndexMessage() {
            return indexMessage;
        }

        public void setIndexMessage(String indexMessage) {
            this.indexMessage = indexMessage;
        }

        public String getOwnerUserId() {
            return ownerUserId;
        }

        public void setOwnerUserId(String ownerUserId) {
            this.ownerUserId = ownerUserId;
        }
    }
}
