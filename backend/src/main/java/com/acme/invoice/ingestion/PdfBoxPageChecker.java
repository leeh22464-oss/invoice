package com.acme.invoice.ingestion;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.nio.file.Path;

public class PdfBoxPageChecker implements PdfPageChecker {

    static final long MAX_MAIN_MEMORY_BYTES = 16L * 1024 * 1024;
    static final long MAX_STORAGE_BYTES = 64L * 1024 * 1024;

    private final UploadPolicy policy;
    private final MemoryUsageSetting memoryUsageSetting;

    public PdfBoxPageChecker(UploadPolicy policy) {
        this(policy, MemoryUsageSetting.setupMixed(MAX_MAIN_MEMORY_BYTES, MAX_STORAGE_BYTES));
    }

    PdfBoxPageChecker(UploadPolicy policy, MemoryUsageSetting memoryUsageSetting) {
        this.policy = policy;
        this.memoryUsageSetting = memoryUsageSetting;
    }

    @Override
    public void validatePageLimit(Path pdfFile) {
        try (var randomAccess = new RandomAccessReadBufferedFile(pdfFile);
             PDDocument document = Loader.loadPDF(randomAccess, memoryUsageSetting.streamCache)) {
            policy.validatePdfPages(document);
        } catch (UploadException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new UploadException(
                    "INVALID_PDF",
                    "PDF content is invalid or exceeds resource limits",
                    HttpStatus.BAD_REQUEST,
                    exception);
        }
    }
}
