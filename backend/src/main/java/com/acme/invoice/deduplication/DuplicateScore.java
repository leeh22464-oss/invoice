package com.acme.invoice.deduplication;

import java.math.BigDecimal;
import java.util.List;

public record DuplicateScore(BigDecimal score, List<String> matchedFields) {
}
