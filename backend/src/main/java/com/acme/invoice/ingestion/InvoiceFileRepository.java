package com.acme.invoice.ingestion;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface InvoiceFileRepository extends JpaRepository<InvoiceFile, UUID> {

    Optional<InvoiceFile> findByIdAndBatchIdAndUserId(
            UUID id, UUID batchId, UUID userId);
}
