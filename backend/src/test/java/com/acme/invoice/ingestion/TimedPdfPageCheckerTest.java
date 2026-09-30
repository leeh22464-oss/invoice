package com.acme.invoice.ingestion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

    @Test
    void rejectsThirdCheckImmediatelyWhenTwoWorkersAreBusy() throws Exception {
        CountDownLatch workersStarted = new CountDownLatch(2);
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        PdfPageChecker hanging = path -> {
            workersStarted.countDown();
            try {
                releaseWorkers.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        };
        Path pdf = root.resolve("doc.pdf");
        Files.writeString(pdf, "%PDF-1.4");

        ExecutorService callers = Executors.newFixedThreadPool(2);
        try (TimedPdfPageChecker checker = new TimedPdfPageChecker(
                hanging, Duration.ofSeconds(5))) {
            Future<?> first = callers.submit(() -> checker.validatePageLimit(pdf));
            Future<?> second = callers.submit(() -> checker.validatePageLimit(pdf));
            assertThat(workersStarted.await(2, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> checker.validatePageLimit(pdf))
                    .isInstanceOf(UploadException.class)
                    .satisfies(exception -> {
                        UploadException uploadException = (UploadException) exception;
                        assertThat(uploadException.getCode()).isEqualTo("PDF_VALIDATION_UNAVAILABLE");
                        assertThat(uploadException.getStatus().value()).isEqualTo(503);
                    });

            releaseWorkers.countDown();
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
        } finally {
            callers.shutdownNow();
        }
    }
}
