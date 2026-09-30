package com.acme.invoice.ocr;

import java.util.List;

public final class OcrDocument {

    private final String providerRequestId;
    private final String rawJson;
    private final List<OcrField> fields;

    public OcrDocument(String providerRequestId, String rawJson, List<OcrField> fields) {
        this.providerRequestId = providerRequestId;
        this.rawJson = rawJson;
        this.fields = List.copyOf(fields);
    }

    public String providerRequestId() {
        return providerRequestId;
    }

    public String rawJson() {
        return rawJson;
    }

    public List<OcrField> fields() {
        return fields;
    }
}
