package com.acme.invoice.ingestion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimedPdfPageCheckerTest {

    @TempDir
    Path root;

    @Test
    void timesOutInjectableCheckerWithoutSpawningPerRequestPools() throws Exception {
        AtomicInteger poolCreations = new AtomicInteger();
        PdfPageChecker hanging = path -> {
            try {
                Thread.sleep(Duration.ofSeconds(30).toMillis());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        };
        Path pdf = root.resolve("doc.pdf");
        Files.writeString(pdf, "%PDF-1.4");

        try (TimedPdfPageChecker checker = new TimedPdfPageChecker(
                hanging, Duration.ofMillis(40), poolCreations::incrementAndGet)) {
            assertThatThrownBy(() -> checker.validatePageLimit(pdf))
                    .isInstanceOf(UploadException.class)
                    .extracting(exception -> ((UploadException) exception).getCode())
                    .isEqualTo("PDF_VALIDATION_TIMEOUT");
            assertThatThrownBy(() -> checker.validatePageLimit(pdf))
                    .isInstanceOf(UploadException.class);
            assertThat(poolCreations.get()).isEqualTo(1);
        }
    }
}
