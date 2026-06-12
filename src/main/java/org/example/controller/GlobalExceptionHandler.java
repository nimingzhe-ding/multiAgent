package org.example.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        logger.warn("Upload size exceeded: {}", e.getMessage());
        FileUploadController.ApiResponse<String> resp = new FileUploadController.ApiResponse<>();
        resp.setCode(413);
        resp.setMessage("文件大小超过限制，最大支持 50MB");
        return ResponseEntity.status(413).body(resp);
    }
}
