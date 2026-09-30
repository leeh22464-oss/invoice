package com.acme.invoice.ingestion;

import org.springframework.http.HttpStatus;

public class UploadException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public UploadException(String code, String message) {
        this(code, message, HttpStatus.BAD_REQUEST);
    }

    public UploadException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public UploadException(String code, String message, HttpStatus status, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
