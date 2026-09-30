package com.acme.invoice.ocr;

import com.acme.invoice.credential.CredentialValidation;

public interface PaddleTokenClient {

    CredentialValidation validate(String apiKey, String secretKey);

    String obtainAccessToken(String apiKey, String secretKey);
}
