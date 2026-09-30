package com.acme.invoice.deduplication;

import java.util.UUID;

public record DeduplicationResult(
        boolean exactDuplicate,
        UUID matchedInvoiceId,
        DuplicateScore suspectedMatch) {
}
