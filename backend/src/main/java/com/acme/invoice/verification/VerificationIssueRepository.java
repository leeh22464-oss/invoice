package com.acme.invoice.verification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface VerificationIssueRepository extends JpaRepository<VerificationIssueEntity, UUID> {

    List<VerificationIssueEntity> findByUserIdAndInvoiceId(UUID userId, UUID invoiceId);

    List<VerificationIssueEntity> findByUserIdAndInvoiceIdIn(UUID userId, Collection<UUID> invoiceIds);

    void deleteByUserIdAndInvoiceId(UUID userId, UUID invoiceId);
}
