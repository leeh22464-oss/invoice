package com.acme.invoice.invoice;

import com.acme.invoice.ocr.InvoiceType;
import com.acme.invoice.ocr.NormalizedField;
import com.acme.invoice.ocr.NormalizedInvoice;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "invoices")
public class Invoice {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "invoice_file_id")
    private UUID invoiceFileId;

    @Column(name = "normalized_invoice_id")
    private UUID normalizedInvoiceId;

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private InvoiceStatus status;

    @Column(name = "exact_duplicate_key", length = 256)
    private String exactDuplicateKey;

    @Column(name = "fields_json", nullable = false, columnDefinition = "text")
    private String fieldsJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    private Map<String, NormalizedField> fields = Map.of();

    protected Invoice() {
    }

    private Invoice(
            UUID id,
            UUID userId,
            InvoiceType invoiceType,
            String invoiceCode,
            String invoiceNumber,
            LocalDate invoiceDate,
            BigDecimal amount,
            BigDecimal tax,
            BigDecimal total,
            InvoiceStatus status,
            String exactDuplicateKey,
            String fieldsJson,
            Map<String, NormalizedField> fields,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.userId = userId;
        this.invoiceType = invoiceType;
        this.invoiceCode = invoiceCode;
        this.invoiceNumber = invoiceNumber;
        this.invoiceDate = invoiceDate;
        this.amount = amount;
        this.tax = tax;
        this.total = total;
        this.status = status;
        this.exactDuplicateKey = exactDuplicateKey;
        this.fieldsJson = fieldsJson;
        this.fields = fields == null ? Map.of() : Map.copyOf(fields);
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Invoice fromNormalized(
            UUID id,
            UUID userId,
            NormalizedInvoice normalized,
            Instant now) {
        return new Invoice(
                id,
                userId,
                normalized.type(),
                normalized.invoiceCode(),
                normalized.invoiceNumber(),
                normalized.invoiceDate(),
                normalized.amount(),
                normalized.tax(),
                normalized.total(),
                InvoiceStatus.REVIEW_REQUIRED,
                null,
                "{}",
                normalized.fields(),
                now,
                now);
    }

    public static Invoice rehydrate(
            UUID id,
            UUID userId,
            InvoiceType type,
            String code,
            String number,
            LocalDate date,
            BigDecimal amount,
            BigDecimal tax,
            BigDecimal total,
            InvoiceStatus status,
            String exactDuplicateKey,
            Map<String, NormalizedField> fields,
            Instant createdAt,
            Instant updatedAt) {
        return new Invoice(
                id,
                userId,
                type,
                code,
                number,
                date,
                amount,
                tax,
                total,
                status,
                exactDuplicateKey,
                "{}",
                fields,
                createdAt,
                updatedAt);
    }

    public NormalizedInvoice toNormalized() {
        return new NormalizedInvoice(
                invoiceType,
                invoiceCode,
                invoiceNumber,
                invoiceDate,
                amount,
                tax,
                total,
                fields);
    }

    public void markStatus(InvoiceStatus status, Instant now) {
        this.status = status;
        this.updatedAt = now;
    }

    public void assignExactDuplicateKey(String key) {
        this.exactDuplicateKey = key;
    }

    public void clearExactDuplicateKey() {
        this.exactDuplicateKey = null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
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

    public InvoiceStatus getStatus() {
        return status;
    }

    public String getExactDuplicateKey() {
        return exactDuplicateKey;
    }

    public Map<String, NormalizedField> getFields() {
        return fields;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getFieldsJson() {
        return fieldsJson;
    }
}
