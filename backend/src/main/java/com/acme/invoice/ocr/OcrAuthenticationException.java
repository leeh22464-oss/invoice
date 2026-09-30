package com.acme.invoice.ocr;

public class OcrAuthenticationException extends RuntimeException {

    public OcrAuthenticationException() {
        super("OCR credentials were rejected by the provider");
    }
}
