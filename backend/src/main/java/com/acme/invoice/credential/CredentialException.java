package com.acme.invoice.credential;

import org.springframework.http.HttpStatus;

public class CredentialException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    CredentialException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
