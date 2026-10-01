package com.acme.invoice.invoice;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    Optional<Invoice> findByIdAndUserId(UUID id, UUID userId);

    Optional<Invoice> findByUserIdAndInvoiceFileId(UUID userId, UUID invoiceFileId);

    List<Invoice> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<Invoice> findByUserIdAndStatusOrderByCreatedAtDesc(UUID userId, InvoiceStatus status);

    @Query("""
            select i from Invoice i
            where i.userId = :userId
              and i.exactDuplicateKey = :exactDuplicateKey
              and i.status in (com.acme.invoice.invoice.InvoiceStatus.PASSED,
                               com.acme.invoice.invoice.InvoiceStatus.REVIEW_REQUIRED)
            """)
    Optional<Invoice> findActiveByUserIdAndExactDuplicateKey(
            @Param("userId") UUID userId,
            @Param("exactDuplicateKey") String exactDuplicateKey);

    List<Invoice> findByUserIdAndIdNot(UUID userId, UUID id);
}
