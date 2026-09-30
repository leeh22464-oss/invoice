package com.acme.invoice.ocr;

import java.math.BigDecimal;

public record NormalizedField(String sourceText, BigDecimal confidence) {
}
