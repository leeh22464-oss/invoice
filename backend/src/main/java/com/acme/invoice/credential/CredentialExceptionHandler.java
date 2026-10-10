package com.acme.invoice.credential;

import com.acme.invoice.identity.ApiError;
import com.acme.invoice.ocr.PaddleServiceUnavailableException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Profile("!smoke")
public class CredentialExceptionHandler {

    @ExceptionHandler(CredentialException.class)
    ResponseEntity<ApiError> handleCredential(CredentialException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(new ApiError(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(PaddleServiceUnavailableException.class)
    ResponseEntity<ApiError> handlePaddleUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError(
                        "PADDLE_SERVICE_UNAVAILABLE",
                        "OCR 服务暂时不可用，请稍后再试"));
    }
}
