package com.acme.invoice.erp;

import java.util.UUID;

public record ErpInvoiceView(
        UUID id,
        String invoiceCode,
        String invoiceNumber,
        String status) {
}
