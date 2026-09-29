package com.acme.invoice.credential;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CredentialRequest(
        @NotBlank @Size(max = 512) String apiKey,
        @NotBlank @Size(max = 512) String secretKey) {

    public CredentialRequest {
        apiKey = apiKey == null ? null : apiKey.trim();
        secretKey = secretKey == null ? null : secretKey.trim();
    }
}
