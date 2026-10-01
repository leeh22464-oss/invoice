package com.acme.invoice.ingestion;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvoiceFileRepository extends JpaRepository<InvoiceFile, UUID> {

    Optional<InvoiceFile> findByIdAndBatchIdAndUserId(
            UUID id, UUID batchId, UUID userId);

    Optional<InvoiceFile> findByIdAndUserId(UUID id, UUID userId);

    List<InvoiceFile> findByBatchIdAndUserId(UUID batchId, UUID userId);
}
