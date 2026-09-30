package com.acme.invoice.credential;

import com.acme.invoice.ocr.PaddleTokenClient;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
@Profile("!smoke")
public class OcrCredentialService {

    private final OcrCredentialPersistence persistence;
    private final SecretCipher cipher;
    private final PaddleTokenClient tokenClient;
    private final Clock clock;

    public OcrCredentialService(
            OcrCredentialPersistence persistence,
            SecretCipher cipher,
            PaddleTokenClient tokenClient,
            Clock clock) {
        this.persistence = persistence;
        this.cipher = cipher;
        this.tokenClient = tokenClient;
        this.clock = clock;
    }

    public CredentialStatusResponse status(UUID userId) {
        return persistence.findByUserId(userId)
                .map(CredentialStatusResponse::from)
                .orElseGet(CredentialStatusResponse::absent);
    }

    public CredentialStatusResponse save(UUID userId, CredentialRequest request) {
        PaddleCredential credential =
                new PaddleCredential(request.apiKey().trim(), request.secretKey().trim());
        CredentialValidation validation =
                tokenClient.validate(credential.apiKey(), credential.secretKey());
        if (!validation.valid()) {
            throw new InvalidCredentialException();
        }

        EncryptedSecret encryptedApiKey = cipher.encrypt(credential.apiKey());
        EncryptedSecret encryptedSecretKey = cipher.encrypt(credential.secretKey());
        Instant now = clock.instant();
        return CredentialStatusResponse.from(persistence.upsert(
                userId,
                encryptedApiKey,
                encryptedSecretKey,
                mask(credential.apiKey()),
                now));
    }

    public CredentialStatusResponse test(UUID userId) {
        OcrCredential stored = persistence.findByUserId(userId)
                .orElseThrow(() -> new CredentialException(
                        HttpStatus.NOT_FOUND,
                        "OCR_CREDENTIALS_NOT_FOUND",
                        "OCR credentials are not configured"));
        String apiKey = cipher.decrypt(
                new EncryptedSecret(stored.getApiKeyIv(), stored.getApiKeyCiphertext()));
        String secretKey = cipher.decrypt(
                new EncryptedSecret(stored.getSecretKeyIv(), stored.getSecretKeyCiphertext()));
        CredentialValidation validation = tokenClient.validate(apiKey, secretKey);
        return CredentialStatusResponse.from(
                persistence.recordValidation(userId, validation.valid(), clock.instant()));
    }

    public void delete(UUID userId) {
        persistence.deleteByUserId(userId);
    }

    public static String mask(String apiKey) {
        if (apiKey.length() < 8) {
            return "****";
        }
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }
}
