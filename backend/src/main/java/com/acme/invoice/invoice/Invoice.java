package com.acme.invoice.invoice;

import com.acme.invoice.ocr.InvoiceType;
import com.acme.invoice.ocr.NormalizedField;
import com.acme.invoice.ocr.NormalizedInvoice;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.SignStyle;
import java.time.temporal.ChronoField;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "invoices")
public class Invoice {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Map<String, Object>>> FIELDS_JSON_TYPE =
            new TypeReference<>() {};
    private static final DateTimeFormatter CN_DATE = new DateTimeFormatterBuilder()
            .appendValue(ChronoField.YEAR, 4)
            .appendLiteral('年')
            .appendValue(ChronoField.MONTH_OF_YEAR, 1, 2, SignStyle.NOT_NEGATIVE)
            .appendLiteral('月')
            .appendValue(ChronoField.DAY_OF_MONTH, 1, 2, SignStyle.NOT_NEGATIVE)
            .appendLiteral('日')
            .toFormatter(Locale.ROOT);

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
        Map<String, NormalizedField> fields =
                normalized.fields() == null ? Map.of() : Map.copyOf(normalized.fields());
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
                serializeFields(fields),
                fields,
                now,
                now);
    }

    public void linkSources(UUID invoiceFileId, UUID normalizedInvoiceId) {
        this.invoiceFileId = invoiceFileId;
        this.normalizedInvoiceId = normalizedInvoiceId;
    }

    public void replaceFromNormalized(NormalizedInvoice normalized, Instant now) {
        Map<String, NormalizedField> nextFields =
                normalized.fields() == null ? Map.of() : Map.copyOf(normalized.fields());
        this.invoiceType = normalized.type();
        this.invoiceCode = normalized.invoiceCode();
        this.invoiceNumber = normalized.invoiceNumber();
        this.invoiceDate = normalized.invoiceDate();
        this.amount = normalized.amount();
        this.tax = normalized.tax();
        this.total = normalized.total();
        this.fields = nextFields;
        this.fieldsJson = serializeFields(nextFields);
        this.exactDuplicateKey = null;
        this.updatedAt = now;
    }

    public String readFieldValue(String fieldName) {
        if (fields != null) {
            NormalizedField field = fields.get(fieldName);
            if (field != null && field.sourceText() != null) {
                return field.sourceText();
            }
        }
        String canonical = canonicalField(fieldName);
        if (canonical == null) {
            return null;
        }
        return readCanonical(canonical);
    }

    public void applyFieldCorrection(String fieldName, String newValue, Instant now) {
        String canonical = canonicalField(fieldName);
        if (canonical != null) {
            applyCanonical(canonical, newValue);
        }
        Map<String, NormalizedField> mutable = new LinkedHashMap<>(fields == null ? Map.of() : fields);
        if (blankToNull(newValue) == null) {
            mutable.remove(fieldName);
        } else {
            mutable.put(fieldName, new NormalizedField(newValue, BigDecimal.ONE));
        }
        this.fields = Map.copyOf(mutable);
        this.fieldsJson = serializeFields(this.fields);
        this.updatedAt = now;
    }

    private String canonicalField(String fieldName) {
        if (fieldName == null) {
            return null;
        }
        String typed = typeSpecificCanonical(fieldName);
        if (typed != null) {
            return typed;
        }
        return switch (fieldName) {
            case "invoiceCode", "InvoiceCode", "invoice_code" -> "invoiceCode";
            case "invoiceNumber", "InvoiceNum", "invoice_number", "invoice_num", "ticket_num" -> "invoiceNumber";
            case "invoiceDate", "InvoiceDate", "invoice_date", "Date", "date", "start_date" -> "invoiceDate";
            case "amount", "TotalAmount" -> "amount";
            case "tax", "TotalTax" -> "tax";
            case "total", "AmountInFiguers" -> "total";
            default -> null;
        };
    }

    private String typeSpecificCanonical(String fieldName) {
        if (invoiceType == null) {
            return null;
        }
        return switch (invoiceType) {
            case RAILWAY -> switch (fieldName) {
                case "fare" -> "amount";
                case "tax" -> "tax";
                case "ticket_rates" -> "total";
                default -> null;
            };
            case FLIGHT -> switch (fieldName) {
                case "fare" -> "amount";
                case "commodity_tax" -> "tax";
                case "ticket_rates" -> "total";
                default -> null;
            };
            case TAXI -> switch (fieldName) {
                case "Fare" -> "amount";
                case "TotalFare" -> "total";
                default -> null;
            };
            case FIXED_AMOUNT -> switch (fieldName) {
                case "invoice_rate_in_figure", "invoice_rate_lowercase" -> "total";
                default -> null;
            };
            default -> null;
        };
    }

    private void applyCanonical(String canonical, String newValue) {
        switch (canonical) {
            case "invoiceCode" -> this.invoiceCode = blankToNull(newValue);
            case "invoiceNumber" -> this.invoiceNumber = blankToNull(newValue);
            case "invoiceDate" -> this.invoiceDate = parseDate(newValue);
            case "amount" -> this.amount = parseDecimal(newValue);
            case "tax" -> this.tax = parseDecimal(newValue);
            case "total" -> this.total = parseDecimal(newValue);
            default -> {
            }
        }
    }

    private String readCanonical(String canonical) {
        return switch (canonical) {
            case "invoiceCode" -> invoiceCode;
            case "invoiceNumber" -> invoiceNumber;
            case "invoiceDate" -> invoiceDate == null ? null : invoiceDate.toString();
            case "amount" -> amount == null ? null : amount.toPlainString();
            case "tax" -> tax == null ? null : tax.toPlainString();
            case "total" -> total == null ? null : total.toPlainString();
            default -> null;
        };
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
        Map<String, NormalizedField> safeFields = fields == null ? Map.of() : Map.copyOf(fields);
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
                serializeFields(safeFields),
                safeFields,
                createdAt,
                updatedAt);
    }

    public NormalizedInvoice toNormalized() {
        Map<String, NormalizedField> restored = parseFields(fieldsJson);
        if (restored.isEmpty() && fields != null && !fields.isEmpty()) {
            restored = fields;
        }
        return new NormalizedInvoice(
                invoiceType,
                invoiceCode,
                invoiceNumber,
                invoiceDate,
                amount,
                tax,
                total,
                restored);
    }

    @PostLoad
    void hydrateFieldsFromJson() {
        this.fields = parseFields(fieldsJson);
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

    public UUID getInvoiceFileId() {
        return invoiceFileId;
    }

    public UUID getNormalizedInvoiceId() {
        return normalizedInvoiceId;
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

    private static String serializeFields(Map<String, NormalizedField> fields) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            fields.forEach((name, field) -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("sourceText", field.sourceText());
                entry.put("confidence", field.confidence());
                payload.put(name, entry);
            });
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize invoice fields", exception);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static BigDecimal parseDecimal(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return null;
        }
        String cleaned = normalized.replace(",", "").replace("￥", "").replace("¥", "").trim();
        return new BigDecimal(cleaned);
    }

    private static LocalDate parseDate(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return null;
        }
        String text = normalized.trim();
        try {
            if (text.contains("年")) {
                return LocalDate.parse(text, CN_DATE);
            }
            if (text.length() == 8 && text.chars().allMatch(Character::isDigit)) {
                return LocalDate.parse(text, DateTimeFormatter.BASIC_ISO_DATE);
            }
            return LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Unparseable invoice date");
        }
    }

    private static Map<String, NormalizedField> parseFields(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            return Map.of();
        }
        try {
            Map<String, Map<String, Object>> raw = MAPPER.readValue(json, FIELDS_JSON_TYPE);
            Map<String, NormalizedField> parsed = new LinkedHashMap<>();
            raw.forEach((name, entry) -> {
                Object text = entry == null ? null : entry.get("sourceText");
                Object confidence = entry == null ? null : entry.get("confidence");
                parsed.put(
                        name,
                        new NormalizedField(
                                text == null ? null : String.valueOf(text),
                                confidence == null ? null : new BigDecimal(String.valueOf(confidence))));
            });
            return Map.copyOf(parsed);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to parse invoice fields_json", exception);
        }
    }
}
