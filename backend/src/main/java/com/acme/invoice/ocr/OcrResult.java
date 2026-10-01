package com.acme.invoice.ocr;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ocr_results")
public class OcrResult {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "invoice_file_id", nullable = false)
    private UUID invoiceFileId;

    @Column(name = "provider_request_id", length = 64)
    private String providerRequestId;

    @Column(name = "raw_json", nullable = false, columnDefinition = "text")
    private String rawJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected OcrResult() {
    }

    public OcrResult(
            UUID id,
            UUID userId,
            UUID invoiceFileId,
            String providerRequestId,
            String rawJson,
            Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.invoiceFileId = invoiceFileId;
        this.providerRequestId = providerRequestId;
        this.rawJson = rawJson;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getInvoiceFileId() {
        return invoiceFileId;
    }

    public String getProviderRequestId() {
        return providerRequestId;
    }

    public String getRawJson() {
        return rawJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void replace(String providerRequestId, String rawJson) {
        this.providerRequestId = providerRequestId;
        this.rawJson = rawJson;
    }
}
