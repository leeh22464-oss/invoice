package com.acme.invoice.deduplication;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface DuplicateMatchRepository extends JpaRepository<DuplicateMatchEntity, UUID> {

    List<DuplicateMatchEntity> findByUserIdAndInvoiceId(UUID userId, UUID invoiceId);

    List<DuplicateMatchEntity> findByUserIdAndInvoiceIdIn(UUID userId, Collection<UUID> invoiceIds);

    void deleteByUserIdAndInvoiceId(UUID userId, UUID invoiceId);
}
