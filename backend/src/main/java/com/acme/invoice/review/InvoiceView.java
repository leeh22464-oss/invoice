package com.acme.invoice.review;

import com.acme.invoice.invoice.Invoice;
import com.acme.invoice.invoice.InvoiceStatus;
import com.acme.invoice.ocr.InvoiceType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
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
        Instant createdAt,
        Instant updatedAt) {

    public static InvoiceView from(Invoice invoice) {
        Map<String, String> fields = invoice.getFields() == null
                ? Map.of()
                : invoice.getFields().entrySet().stream()
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                entry -> entry.getValue().sourceText() == null
                                        ? ""
                                        : entry.getValue().sourceText(),
                                (left, right) -> right,
                                LinkedHashMap::new));
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
                invoice.getCreatedAt(),
                invoice.getUpdatedAt());
    }
}
