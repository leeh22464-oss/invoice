package com.acme.invoice.credential;

import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

public interface OcrCredentialRepository extends Repository<OcrCredential, UUID> {

    Optional<OcrCredential> findByUserId(UUID userId);

    OcrCredential save(OcrCredential credential);

    void deleteByUserId(UUID userId);
}
