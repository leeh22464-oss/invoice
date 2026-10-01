package com.acme.invoice.ingestion;

import java.util.Map;

public record BatchProgressEvent(int totalFiles, String status, Map<String, Long> statusCounts) {
}
