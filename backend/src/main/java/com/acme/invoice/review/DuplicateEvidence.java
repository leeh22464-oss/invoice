package com.acme.invoice.review;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record DuplicateEvidence(
        String matchType,
        UUID matchedInvoiceId,
        BigDecimal score,
        List<String> matchedFields) {
}
