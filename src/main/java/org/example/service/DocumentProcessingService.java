package org.example.service;

import lombok.Getter;
import lombok.Setter;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class DocumentProcessingService {

    private static final byte[] PNG_SIGNATURE = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    @Value("${document.reader.allowed-roots:./uploads,./aiops-docs}")
    private String allowedRoots;

    @Value("${document.reader.page-size:4000}")
    private int defaultPageSize;

    @Value("${document.reader.max-page-size:12000}")
    private int maxPageSize;

    @Value("${document.reader.max-file-size-mb:50}")
    private long maxFileSizeMb;

    @Value("${document.reader.pdftotext-path:pdftotext}")
    private String pdftotextPath;

    @Value("${document.reader.pdftotext-timeout-seconds:30}")
    private long pdftotextTimeoutSeconds;

    public DocumentPage readPage(String documentPath, Integer page, Integer pageSize) throws IOException {
        Path path = resolveAllowedPath(documentPath);
        List<String> pages = extractPages(path);
        int requestedPage = page == null || page < 1 ? 1 : page;
        int effectivePageSize = normalizePageSize(pageSize);

        if (pages.isEmpty()) {
            return DocumentPage.empty(path, requestedPage, effectivePageSize);
        }

        if (requestedPage > pages.size()) {
            throw new IllegalArgumentException("page out of range: " + requestedPage + ", total pages: " + pages.size());
        }

        String content = pages.get(requestedPage - 1);
        boolean truncated = content.length() > effectivePageSize;
        if (truncated) {
            content = content.substring(0, effectivePageSize);
        }

        DocumentPage result = new DocumentPage();
        result.setSuccess(true);
        result.setPath(path.toString());
        result.setFileType(getExtension(path));
        result.setPage(requestedPage);
        result.setTotalPages(pages.size());
        result.setPageSize(effectivePageSize);
        result.setTruncated(truncated);
        result.setContent(content);
        return result;
    }

    public String readFullText(String documentPath) throws IOException {
        Path path = resolveAllowedPath(documentPath);
        return String.join("\n\n", extractPages(path));
    }

    public List<String> extractPages(Path path) throws IOException {
        validateFile(path);
        String extension = getExtension(path);

        return switch (extension) {
            case "pdf" -> extractPdfPages(path);
            case "docx" -> paginateText(extractDocxText(path), defaultPageSize);
            case "doc" -> paginateText(extractDocText(path), defaultPageSize);
            case "png" -> List.of();
            case "txt", "md", "markdown" -> paginateText(Files.readString(path, StandardCharsets.UTF_8), defaultPageSize);
            default -> throw new IllegalArgumentException("unsupported document type: " + extension);
        };
    }

    private Path resolveAllowedPath(String documentPath) {
        if (documentPath == null || documentPath.isBlank()) {
            throw new IllegalArgumentException("document path cannot be empty");
        }

        Path rawPath = Paths.get(documentPath);
        Path baseDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path candidate = rawPath.isAbsolute() ? rawPath.normalize() : baseDir.resolve(rawPath).normalize();

        List<Path> roots = parseAllowedRoots(baseDir);
        boolean allowed = roots.stream().anyMatch(candidate::startsWith);
        if (!allowed) {
            throw new IllegalArgumentException("document path is outside allowed roots");
        }
        return candidate;
    }

    private List<Path> parseAllowedRoots(Path baseDir) {
        List<Path> roots = new ArrayList<>();
        for (String root : allowedRoots.split(",")) {
            String trimmed = root.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Path rootPath = Paths.get(trimmed);
            roots.add(rootPath.isAbsolute() ? rootPath.normalize() : baseDir.resolve(rootPath).normalize());
        }
        return roots;
    }

    private void validateFile(Path path) throws IOException {
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("document does not exist or is not a file: " + path);
        }

        long maxBytes = maxFileSizeMb * 1024L * 1024L;
        long size = Files.size(path);
        if (size > maxBytes) {
            throw new IllegalArgumentException("document exceeds max size: " + maxFileSizeMb + "MB");
        }

        if ("png".equals(getExtension(path))) {
            validatePng(path);
        }
    }

    private void validatePng(Path path) throws IOException {
        byte[] header = new byte[PNG_SIGNATURE.length];
        try (InputStream inputStream = Files.newInputStream(path)) {
            int read = inputStream.read(header);
            if (read != PNG_SIGNATURE.length) {
                throw new IllegalArgumentException("invalid PNG header");
            }
            for (int i = 0; i < PNG_SIGNATURE.length; i++) {
                if (header[i] != PNG_SIGNATURE[i]) {
                    throw new IllegalArgumentException("invalid PNG header");
                }
            }
        }
    }

    private String extractDocxText(Path path) throws IOException {
        try (InputStream inputStream = Files.newInputStream(path);
             XWPFDocument document = new XWPFDocument(inputStream);
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }

    private String extractDocText(Path path) throws IOException {
        try (InputStream inputStream = Files.newInputStream(path);
             HWPFDocument document = new HWPFDocument(inputStream);
             WordExtractor extractor = new WordExtractor(document)) {
            return extractor.getText();
        }
    }

    private List<String> extractPdfPages(Path path) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(
                pdftotextPath,
                "-layout",
                "-enc",
                "UTF-8",
                path.toString(),
                "-"
        );

        Process process = builder.start();
        CompletableFuture<String> stdout = readAsync(process.getInputStream());
        CompletableFuture<String> stderr = readAsync(process.getErrorStream());

        boolean finished;
        try {
            finished = process.waitFor(pdftotextTimeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("pdftotext interrupted", e);
        }

        if (!finished) {
            process.destroyForcibly();
            throw new IOException("pdftotext timed out after " + Duration.ofSeconds(pdftotextTimeoutSeconds));
        }

        String output = stdout.join();
        String errorOutput = stderr.join();
        if (process.exitValue() != 0) {
            throw new IOException("pdftotext failed: " + errorOutput);
        }

        return splitPdfPages(output);
    }

    private CompletableFuture<String> readAsync(InputStream inputStream) {
        return CompletableFuture.supplyAsync(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private List<String> splitPdfPages(String text) {
        List<String> pages = new ArrayList<>();
        for (String page : text.split("\\f", -1)) {
            String trimmed = page.strip();
            if (!trimmed.isEmpty()) {
                pages.add(trimmed);
            }
        }
        return pages;
    }

    private List<String> paginateText(String text, int pageSize) {
        int effectivePageSize = normalizePageSize(pageSize);
        List<String> pages = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return pages;
        }

        for (int start = 0; start < text.length(); start += effectivePageSize) {
            int end = Math.min(start + effectivePageSize, text.length());
            pages.add(text.substring(start, end));
        }
        return pages;
    }

    private int normalizePageSize(Integer pageSize) {
        int effective = pageSize == null || pageSize <= 0 ? defaultPageSize : pageSize;
        return Math.min(effective, maxPageSize);
    }

    private String getExtension(Path path) {
        String fileName = path.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    @Getter
    @Setter
    public static class DocumentPage {
        private boolean success;
        private String path;
        private String fileType;
        private int page;
        private int totalPages;
        private int pageSize;
        private boolean truncated;
        private String content;

        static DocumentPage empty(Path path, int page, int pageSize) {
            DocumentPage result = new DocumentPage();
            result.setSuccess(true);
            result.setPath(path.toString());
            result.setPage(page);
            result.setTotalPages(0);
            result.setPageSize(pageSize);
            result.setTruncated(false);
            result.setContent("");
            return result;
        }
    }
}
