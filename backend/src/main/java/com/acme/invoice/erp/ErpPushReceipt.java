package com.acme.invoice.erp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "erp_push_receipts")
public class ErpPushReceipt {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "invoice_id", nullable = false)
    private UUID invoiceId;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "response_json", nullable = false, columnDefinition = "text")
    private String responseJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ErpPushReceipt() {
    }

    public ErpPushReceipt(
            UUID id,
            UUID userId,
            UUID invoiceId,
            String idempotencyKey,
            String responseJson,
            Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.invoiceId = invoiceId;
        this.idempotencyKey = idempotencyKey;
        this.responseJson = responseJson;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getInvoiceId() {
        return invoiceId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getResponseJson() {
        return responseJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
