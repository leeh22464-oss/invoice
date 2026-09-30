package com.acme.invoice.verification;

public record VerificationIssue(
        String code,
        String field,
        String message,
        VerificationSeverity severity) {
}
