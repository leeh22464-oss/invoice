package com.acme.invoice.credential;

import org.springframework.http.HttpStatus;

public class InvalidCredentialException extends CredentialException {

    public InvalidCredentialException() {
        super(HttpStatus.UNPROCESSABLE_ENTITY,
                "INVALID_OCR_CREDENTIALS",
                "接口地址或访问令牌无效");
    }
}
