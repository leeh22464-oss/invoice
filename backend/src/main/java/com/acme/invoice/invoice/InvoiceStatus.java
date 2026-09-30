package com.acme.invoice.invoice;

public enum InvoiceStatus {
    QUEUED,
    OCR_PROCESSING,
    REVIEW_REQUIRED,
    PASSED,
    BLOCKED_DUPLICATE,
    FAILED
}
