package com.acme.invoice.ingestion;

import java.nio.file.Path;

@FunctionalInterface
public interface PdfPageChecker {

    void validatePageLimit(Path pdfFile);
}
