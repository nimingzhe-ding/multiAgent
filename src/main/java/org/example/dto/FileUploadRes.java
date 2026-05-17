package org.example.dto;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class FileUploadRes {

    private String fileName;
    private String filePath;
    private Long fileSize;
    private boolean indexed;
    private String indexMessage;

    public FileUploadRes() {
    }

    public FileUploadRes(String fileName, String filePath, Long fileSize) {
        this.fileName = fileName;
        this.filePath = filePath;
        this.fileSize = fileSize;
    }

    public FileUploadRes(String fileName, String filePath, Long fileSize, boolean indexed, String indexMessage) {
        this.fileName = fileName;
        this.filePath = filePath;
        this.fileSize = fileSize;
        this.indexed = indexed;
        this.indexMessage = indexMessage;
    }

}
