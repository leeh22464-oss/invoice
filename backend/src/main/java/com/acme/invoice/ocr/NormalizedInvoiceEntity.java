package com.acme.invoice.ocr;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "normalized_invoices")
public class NormalizedInvoiceEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "invoice_file_id", nullable = false)
    private UUID invoiceFileId;

    @Enumerated(EnumType.STRING)
    @Column(name = "invoice_type", nullable = false, length = 32)
    private InvoiceType invoiceType;

    @Column(name = "invoice_code", length = 64)
    private String invoiceCode;

    @Column(name = "invoice_number", length = 64)
    private String invoiceNumber;

    @Column(name = "invoice_date")
    private LocalDate invoiceDate;

    @Column(precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(precision = 19, scale = 2)
    private BigDecimal tax;

    @Column(precision = 19, scale = 2)
    private BigDecimal total;

    @Column(name = "fields_json", nullable = false, columnDefinition = "text")
    private String fieldsJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected NormalizedInvoiceEntity() {
    }

    public NormalizedInvoiceEntity(
            UUID id,
            UUID userId,
            UUID invoiceFileId,
            InvoiceType invoiceType,
            String invoiceCode,
            String invoiceNumber,
            LocalDate invoiceDate,
            BigDecimal amount,
            BigDecimal tax,
            BigDecimal total,
            String fieldsJson,
            Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.invoiceFileId = invoiceFileId;
        this.invoiceType = invoiceType;
        this.invoiceCode = invoiceCode;
        this.invoiceNumber = invoiceNumber;
        this.invoiceDate = invoiceDate;
        this.amount = amount;
        this.tax = tax;
        this.total = total;
        this.fieldsJson = fieldsJson;
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

    public InvoiceType getInvoiceType() {
        return invoiceType;
    }

    public String getInvoiceCode() {
        return invoiceCode;
    }

    public String getInvoiceNumber() {
        return invoiceNumber;
    }

    public LocalDate getInvoiceDate() {
        return invoiceDate;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getTax() {
        return tax;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public String getFieldsJson() {
        return fieldsJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void replace(
            InvoiceType invoiceType,
            String invoiceCode,
            String invoiceNumber,
            LocalDate invoiceDate,
            BigDecimal amount,
            BigDecimal tax,
            BigDecimal total,
            String fieldsJson) {
        this.invoiceType = invoiceType;
        this.invoiceCode = invoiceCode;
        this.invoiceNumber = invoiceNumber;
        this.invoiceDate = invoiceDate;
        this.amount = amount;
        this.tax = tax;
        this.total = total;
        this.fieldsJson = fieldsJson;
    }
}
