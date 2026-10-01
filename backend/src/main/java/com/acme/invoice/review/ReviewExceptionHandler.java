package com.acme.invoice.review;

import com.acme.invoice.identity.ApiError;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Profile("!smoke")
public class ReviewExceptionHandler {

    @ExceptionHandler(ReviewException.class)
    ResponseEntity<ApiError> handle(ReviewException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(new ApiError(exception.getCode(), exception.getMessage()));
    }
}
