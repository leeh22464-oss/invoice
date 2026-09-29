package com.acme.invoice.credential;

import com.acme.invoice.ocr.PaddleTokenClient;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@Profile("!smoke")
public class OcrCredentialService {

    private final OcrCredentialRepository repository;
    private final SecretCipher cipher;
    private final PaddleTokenClient tokenClient;
    private final Clock clock;

    public OcrCredentialService(
            OcrCredentialRepository repository,
            SecretCipher cipher,
            PaddleTokenClient tokenClient,
            Clock clock) {
        this.repository = repository;
        this.cipher = cipher;
        this.tokenClient = tokenClient;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CredentialStatusResponse status(UUID userId) {
        return repository.findByUserId(userId)
                .map(CredentialStatusResponse::from)
                .orElseGet(CredentialStatusResponse::absent);
    }

    @Transactional
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
        Optional<OcrCredential> existing = repository.findByUserId(userId);
        OcrCredential stored;
        if (existing.isPresent()) {
            stored = existing.get();
            stored.replace(encryptedApiKey, encryptedSecretKey, mask(credential.apiKey()), now);
        } else {
            stored = new OcrCredential(
                    UUID.randomUUID(),
                    userId,
                    encryptedApiKey.iv(),
                    encryptedApiKey.ciphertext(),
                    encryptedSecretKey.iv(),
                    encryptedSecretKey.ciphertext(),
                    mask(credential.apiKey()),
                    "VALID",
                    now,
                    now,
                    now);
        }
        return CredentialStatusResponse.from(repository.save(stored));
    }

    @Transactional
    public CredentialStatusResponse test(UUID userId) {
        OcrCredential stored = repository.findByUserId(userId)
                .orElseThrow(() -> new CredentialException(
                        HttpStatus.NOT_FOUND,
                        "OCR_CREDENTIALS_NOT_FOUND",
                        "OCR credentials are not configured"));
        String apiKey = cipher.decrypt(
                new EncryptedSecret(stored.getApiKeyIv(), stored.getApiKeyCiphertext()));
        String secretKey = cipher.decrypt(
                new EncryptedSecret(stored.getSecretKeyIv(), stored.getSecretKeyCiphertext()));
        CredentialValidation validation = tokenClient.validate(apiKey, secretKey);
        stored.recordValidation(validation.valid(), clock.instant());
        return CredentialStatusResponse.from(repository.save(stored));
    }

    @Transactional
    public void delete(UUID userId) {
        repository.deleteByUserId(userId);
    }

    public static String mask(String apiKey) {
        if (apiKey.length() < 8) {
            return "****";
        }
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }
}
