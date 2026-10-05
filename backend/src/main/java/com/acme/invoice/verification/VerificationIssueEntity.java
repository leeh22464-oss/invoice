package com.acme.invoice.verification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "verification_issues")
public class VerificationIssueEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "invoice_id", nullable = false)
    private UUID invoiceId;

    @Column(nullable = false, length = 64)
    private String code;

    @Column(name = "field_name", length = 64)
    private String fieldName;

    @Column(nullable = false, length = 512)
    private String message;

    @Column(nullable = false, length = 16)
    private String severity;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected VerificationIssueEntity() {
    }

    public VerificationIssueEntity(
            UUID id,
            UUID userId,
            UUID invoiceId,
            String code,
            String fieldName,
            String message,
            String severity,
            Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.invoiceId = invoiceId;
        this.code = code;
        this.fieldName = fieldName;
        this.message = message;
        this.severity = severity;
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

    public String getCode() {
        return code;
    }

    public String getFieldName() {
        return fieldName;
    }

    public String getMessage() {
        return message;
    }

    public String getSeverity() {
        return severity;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
