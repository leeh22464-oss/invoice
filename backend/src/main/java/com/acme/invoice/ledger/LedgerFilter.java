package com.acme.invoice.ledger;

import java.time.LocalDate;

public record LedgerFilter(
        String invoiceType,
        String invoiceNumber,
        LocalDate dateFrom,
        LocalDate dateTo) {

    public static LedgerFilter empty() {
        return new LedgerFilter(null, null, null, null);
    }
}
