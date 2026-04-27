package org.example.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.service.DocumentProcessingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ReadDocumentTools {

    private static final Logger logger = LoggerFactory.getLogger(ReadDocumentTools.class);

    private final DocumentProcessingService documentProcessingService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public ReadDocumentTools(DocumentProcessingService documentProcessingService) {
        this.documentProcessingService = documentProcessingService;
    }

    @Tool(description = "Read a local document page by page. Supports txt, md, markdown, and pdf files. " +
            "PDF files are validated and extracted through the system pdftotext command. " +
            "Use this when the user asks to inspect a specific uploaded/internal document by path.")
    public String read_document(
            @ToolParam(description = "Document path. Relative paths must be under allowed roots such as uploads or aiops-docs.")
            String path,
            @ToolParam(description = "1-based page number. Defaults to 1 when omitted or invalid.")
            Integer page,
            @ToolParam(description = "Maximum characters to return for this page. Defaults to configured document.reader.page-size.")
            Integer pageSize) {
        try {
            DocumentProcessingService.DocumentPage result = documentProcessingService.readPage(path, page, pageSize);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        } catch (Exception e) {
            logger.error("read_document failed, path={}, page={}", path, page, e);
            return buildErrorResponse(e.getMessage());
        }
    }

    private String buildErrorResponse(String message) {
        try {
            return objectMapper.writeValueAsString(new ErrorResponse(false, message));
        } catch (Exception e) {
            return "{\"success\":false,\"message\":\"" + message + "\"}";
        }
    }

    private record ErrorResponse(boolean success, String message) {
    }
}
