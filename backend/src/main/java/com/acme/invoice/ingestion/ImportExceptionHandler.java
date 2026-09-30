package com.acme.invoice.ingestion;

import com.acme.invoice.identity.ApiError;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
@Profile("!smoke")
public class ImportExceptionHandler {

    @ExceptionHandler(UploadException.class)
    ResponseEntity<ApiError> handleUpload(UploadException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(new ApiError(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> handleMaximumUploadSize() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ApiError(
                        "FILE_TOO_LARGE", "Each file must be at most 20 MiB"));
    }
}
