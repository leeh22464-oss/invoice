package com.acme.invoice.review;

import com.acme.invoice.invoice.Invoice;
import com.acme.invoice.invoice.InvoiceStatus;
import com.acme.invoice.ocr.InvoiceType;
import com.acme.invoice.ocr.NormalizedField;
import com.acme.invoice.verification.VerificationIssue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public record InvoiceView(
        UUID id,
        InvoiceType invoiceType,
        String invoiceCode,
        String invoiceNumber,
        LocalDate invoiceDate,
        BigDecimal amount,
        BigDecimal tax,
        BigDecimal total,
        InvoiceStatus status,
        Map<String, String> fields,
        Map<String, BigDecimal> fieldConfidence,
        List<VerificationIssue> issues,
        DuplicateEvidence duplicateEvidence,
        Instant createdAt,
        Instant updatedAt) {

    public static InvoiceView from(Invoice invoice) {
        return from(invoice, List.of(), null);
    }

    public static InvoiceView from(
            Invoice invoice,
            List<VerificationIssue> issues,
            DuplicateEvidence duplicateEvidence) {
        Map<String, NormalizedField> stored = invoice.getFields() == null ? Map.of() : invoice.getFields();
        Map<String, String> fields = stored.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().sourceText() == null ? "" : entry.getValue().sourceText(),
                        (left, right) -> right,
                        LinkedHashMap::new));
        Map<String, BigDecimal> confidence = new LinkedHashMap<>();
        stored.forEach((name, field) -> {
            if (field.confidence() != null) {
                confidence.put(name, field.confidence());
            }
        });
        return new InvoiceView(
                invoice.getId(),
                invoice.getInvoiceType(),
                invoice.getInvoiceCode(),
                invoice.getInvoiceNumber(),
                invoice.getInvoiceDate(),
                invoice.getAmount(),
                invoice.getTax(),
                invoice.getTotal(),
                invoice.getStatus(),
                fields,
                Map.copyOf(confidence),
                issues == null ? List.of() : List.copyOf(issues),
                duplicateEvidence,
                invoice.getCreatedAt(),
                invoice.getUpdatedAt());
    }
}
