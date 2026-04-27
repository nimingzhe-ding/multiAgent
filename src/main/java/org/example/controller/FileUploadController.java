package org.example.controller;

import org.example.config.FileUploadConfig;
import org.example.dto.FileUploadRes;
import org.example.service.VectorIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

@RestController
public class FileUploadController {

    private static final Logger logger = LoggerFactory.getLogger(FileUploadController.class);
    private static final Set<String> TEXT_INDEXABLE_EXTENSIONS = Set.of("txt", "md", "markdown", "doc", "docx", "pdf");

    @Autowired
    private FileUploadConfig fileUploadConfig;

    @Autowired
    private VectorIndexService vectorIndexService;

    @GetMapping("/api/knowledge/files")
    public ResponseEntity<?> listKnowledgeFiles() {
        try {
            Path uploadDir = Paths.get(fileUploadConfig.getPath()).toAbsolutePath().normalize();
            Files.createDirectories(uploadDir);

            List<KnowledgeFile> files;
            try (Stream<Path> stream = Files.list(uploadDir)) {
                files = stream
                        .filter(Files::isRegularFile)
                        .map(path -> toKnowledgeFile(uploadDir, path))
                        .sorted(Comparator.comparing(KnowledgeFile::getLastModified).reversed())
                        .toList();
            }

            ApiResponse<List<KnowledgeFile>> response = new ApiResponse<>();
            response.setCode(200);
            response.setMessage("success");
            response.setData(files);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            ApiResponse<String> errorResponse = new ApiResponse<>();
            errorResponse.setCode(500);
            errorResponse.setMessage("list knowledge files failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    @PostMapping(value = {"/api/upload", "/api/knowledge/files"}, consumes = "multipart/form-data")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("file cannot be empty");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            return ResponseEntity.badRequest().body("file name cannot be empty");
        }

        String safeFilename = sanitizeFilename(originalFilename);
        if (safeFilename.isEmpty()) {
            return ResponseEntity.badRequest().body("invalid file name");
        }

        String fileExtension = getFileExtension(safeFilename);
        if (!isAllowedExtension(fileExtension)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("unsupported file extension, allowed: " + fileUploadConfig.getAllowedExtensions());
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

            try {
                logger.info("Indexing uploaded file: {}", filePath);
                vectorIndexService.indexSingleFile(filePath.toString());
                logger.info("Index created for uploaded file: {}", filePath);
            } catch (Exception e) {
                logger.error("Vector indexing failed for file: {}, error: {}", filePath, e.getMessage(), e);
            }

            FileUploadRes response = new FileUploadRes(
                    safeFilename,
                    filePath.toString(),
                    file.getSize()
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
    public ResponseEntity<?> reindexKnowledgeFile(@PathVariable String fileName) {
        String safeFilename = sanitizeFilename(fileName);
        if (safeFilename.isEmpty()) {
            return ResponseEntity.badRequest().body(errorResponse(400, "invalid file name"));
        }

        String extension = getFileExtension(safeFilename);
        if (!TEXT_INDEXABLE_EXTENSIONS.contains(extension)) {
            return ResponseEntity.badRequest().body(errorResponse(400, "file type cannot be indexed"));
        }

        try {
            Path filePath = resolveUploadFile(safeFilename);
            if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse(404, "file not found"));
            }

            vectorIndexService.indexSingleFile(filePath.toString());
            ApiResponse<String> response = new ApiResponse<>();
            response.setCode(200);
            response.setMessage("success");
            response.setData(safeFilename);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Reindex knowledge file failed: {}", safeFilename, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(errorResponse(500, "reindex failed: " + e.getMessage()));
        }
    }

    @DeleteMapping("/api/knowledge/files/{fileName}")
    public ResponseEntity<?> deleteKnowledgeFile(@PathVariable String fileName) {
        String safeFilename = sanitizeFilename(fileName);
        if (safeFilename.isEmpty()) {
            return ResponseEntity.badRequest().body(errorResponse(400, "invalid file name"));
        }

        try {
            Path filePath = resolveUploadFile(safeFilename);
            if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse(404, "file not found"));
            }

            vectorIndexService.deleteFileIndex(filePath.toString());
            Files.delete(filePath);

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
            return file;
        } catch (IOException e) {
            throw new RuntimeException("failed to read file metadata: " + path, e);
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
    }
}
