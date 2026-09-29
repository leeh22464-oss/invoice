package com.acme.invoice.credential;

import java.time.Instant;

public record CredentialStatusResponse(
        boolean configured,
        String apiKeyMask,
        String validationStatus,
        Instant lastValidatedAt,
        Instant updatedAt) {

    static CredentialStatusResponse absent() {
        return new CredentialStatusResponse(false, null, null, null, null);
    }

    static CredentialStatusResponse from(OcrCredential credential) {
        return new CredentialStatusResponse(
                true,
                credential.getApiKeyMask(),
                credential.getValidationStatus(),
                credential.getLastValidatedAt(),
                credential.getUpdatedAt());
    }
}
