package com.acme.invoice.ocr;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface NormalizedInvoiceRepository extends JpaRepository<NormalizedInvoiceEntity, UUID> {

    Optional<NormalizedInvoiceEntity> findByIdAndUserId(UUID id, UUID userId);

    Optional<NormalizedInvoiceEntity> findByInvoiceFileIdAndUserId(UUID invoiceFileId, UUID userId);
}
