package com.acme.invoice.deduplication;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "duplicate_matches")
public class DuplicateMatchEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "invoice_id", nullable = false)
    private UUID invoiceId;

    @Column(name = "matched_invoice_id", nullable = false)
    private UUID matchedInvoiceId;

    @Column(name = "match_type", nullable = false, length = 32)
    private String matchType;

    @Column(precision = 10, scale = 4)
    private BigDecimal score;

    @Column(name = "matched_fields_json", nullable = false, columnDefinition = "text")
    private String matchedFieldsJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected DuplicateMatchEntity() {
    }

    public DuplicateMatchEntity(
            UUID id,
            UUID userId,
            UUID invoiceId,
            UUID matchedInvoiceId,
            String matchType,
            BigDecimal score,
            String matchedFieldsJson,
            Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.invoiceId = invoiceId;
        this.matchedInvoiceId = matchedInvoiceId;
        this.matchType = matchType;
        this.score = score;
        this.matchedFieldsJson = matchedFieldsJson;
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

    public UUID getMatchedInvoiceId() {
        return matchedInvoiceId;
    }

    public String getMatchType() {
        return matchType;
    }

    public BigDecimal getScore() {
        return score;
    }

    public String getMatchedFieldsJson() {
        return matchedFieldsJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
