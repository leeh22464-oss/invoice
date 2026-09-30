package com.acme.invoice.ocr;

import java.math.BigDecimal;

public record OcrField(String name, String rawValue, BigDecimal confidence) {
}
