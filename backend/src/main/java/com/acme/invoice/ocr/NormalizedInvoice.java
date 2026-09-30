package com.acme.invoice.ocr;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

public record NormalizedInvoice(
        InvoiceType type,
        String invoiceCode,
        String invoiceNumber,
        LocalDate invoiceDate,
        BigDecimal amount,
        BigDecimal tax,
        BigDecimal total,
        Map<String, NormalizedField> fields) {
}
