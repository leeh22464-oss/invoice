package com.acme.invoice.review;

import java.util.Map;

public record InvoiceCorrectionRequest(
        Map<String, String> fields,
        String status) {
}
