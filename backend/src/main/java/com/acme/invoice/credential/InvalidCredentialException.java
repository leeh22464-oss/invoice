package com.acme.invoice.credential;

import org.springframework.http.HttpStatus;

public class InvalidCredentialException extends CredentialException {

    public InvalidCredentialException() {
        this("接口地址或访问令牌无效");
    }

    public InvalidCredentialException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_OCR_CREDENTIALS", message);
    }
}
