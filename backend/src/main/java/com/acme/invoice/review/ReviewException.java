package com.acme.invoice.review;

import org.springframework.http.HttpStatus;

public class ReviewException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public ReviewException(String code, String message, HttpStatus status) {
        super(message);
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
