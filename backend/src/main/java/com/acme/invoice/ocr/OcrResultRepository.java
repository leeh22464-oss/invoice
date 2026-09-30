package com.acme.invoice.ocr;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OcrResultRepository extends JpaRepository<OcrResult, UUID> {

    Optional<OcrResult> findByIdAndUserId(UUID id, UUID userId);

    Optional<OcrResult> findByInvoiceFileIdAndUserId(UUID invoiceFileId, UUID userId);
}
