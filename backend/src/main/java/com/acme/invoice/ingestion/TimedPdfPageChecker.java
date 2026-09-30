package com.acme.invoice.ingestion;

import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public final class TimedPdfPageChecker implements PdfPageChecker, AutoCloseable {

    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private final PdfPageChecker delegate;
    private final Duration timeout;
    private final ExecutorService executor;

    public TimedPdfPageChecker(PdfPageChecker delegate) {
        this(delegate, DEFAULT_TIMEOUT);
    }

    public TimedPdfPageChecker(PdfPageChecker delegate, Duration timeout) {
        this(delegate, timeout, null);
    }

    TimedPdfPageChecker(
            PdfPageChecker delegate, Duration timeout, Runnable onPoolCreated) {
        this.delegate = delegate;
        this.timeout = timeout;
        if (onPoolCreated != null) {
            onPoolCreated.run();
        }
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "pdf-page-check-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newFixedThreadPool(2, factory);
    }

    @Override
    public void validatePageLimit(Path pdfFile) {
        Future<?> future = executor.submit(() -> delegate.validatePageLimit(pdfFile));
        try {
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new UploadException(
                    "PDF_VALIDATION_TIMEOUT",
                    "PDF validation timed out",
                    HttpStatus.BAD_REQUEST,
                    exception);
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new UploadException(
                    "PDF_VALIDATION_TIMEOUT",
                    "PDF validation interrupted",
                    HttpStatus.BAD_REQUEST,
                    exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            if (cause instanceof UploadException uploadException) {
                throw uploadException;
            }
            throw new UploadException(
                    "INVALID_PDF",
                    "PDF content is invalid or exceeds resource limits",
                    HttpStatus.BAD_REQUEST,
                    cause);
        }
    }

    @PreDestroy
    @Override
    public void close() {
        executor.shutdownNow();
    }
}
