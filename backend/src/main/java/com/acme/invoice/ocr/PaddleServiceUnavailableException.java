package com.acme.invoice.ocr;

public class PaddleServiceUnavailableException extends RuntimeException {

    public PaddleServiceUnavailableException() {
        super("OCR credential validation service is unavailable");
    }
}
