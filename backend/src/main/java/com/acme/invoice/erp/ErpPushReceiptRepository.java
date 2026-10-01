package com.acme.invoice.erp;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ErpPushReceiptRepository extends JpaRepository<ErpPushReceipt, UUID> {

    Optional<ErpPushReceipt> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);
}
