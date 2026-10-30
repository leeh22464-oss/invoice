package com.acme.invoice.ocr;

import com.acme.invoice.credential.OcrCredentialService;
import com.acme.invoice.credential.PaddleCredential;
import com.acme.invoice.deduplication.DeduplicationService;
import com.acme.invoice.ingestion.BatchStatusSync;
import com.acme.invoice.ingestion.FileStorage;
import com.acme.invoice.ingestion.InvoiceFile;
import com.acme.invoice.ingestion.InvoiceFileRepository;
import com.acme.invoice.invoice.Invoice;
import com.acme.invoice.invoice.InvoiceRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Profile("!smoke")
public class OcrProcessingService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OcrCredentialService credentialService;
    private final FileStorage storage;
    private final OcrProvider ocrProvider;
    private final InvoiceNormalizer normalizer;
    private final OcrResultRepository ocrResultRepository;
    private final NormalizedInvoiceRepository normalizedInvoiceRepository;
    private final InvoiceFileRepository invoiceFileRepository;
    private final InvoiceRepository invoiceRepository;
    private final DeduplicationService deduplicationService;
    private final StoredFileContentHolder contentHolder;
    private final BatchStatusSync batchStatusSync;
    private final Clock clock;
    private final TransactionTemplate requiredTx;
    private final TransactionTemplate requiresNewTx;

    @PersistenceContext
    private EntityManager entityManager;

    public OcrProcessingService(
            OcrCredentialService credentialService,
            FileStorage storage,
            OcrProvider ocrProvider,
            InvoiceNormalizer normalizer,
            OcrResultRepository ocrResultRepository,
            NormalizedInvoiceRepository normalizedInvoiceRepository,
            InvoiceFileRepository invoiceFileRepository,
            InvoiceRepository invoiceRepository,
            DeduplicationService deduplicationService,
            StoredFileContentHolder contentHolder,
            BatchStatusSync batchStatusSync,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.credentialService = credentialService;
        this.storage = storage;
        this.ocrProvider = ocrProvider;
        this.normalizer = normalizer;
        this.ocrResultRepository = ocrResultRepository;
        this.normalizedInvoiceRepository = normalizedInvoiceRepository;
        this.invoiceFileRepository = invoiceFileRepository;
        this.invoiceRepository = invoiceRepository;
        this.deduplicationService = deduplicationService;
        this.contentHolder = contentHolder;
        this.batchStatusSync = batchStatusSync;
        this.clock = clock;
        this.requiredTx = new TransactionTemplate(transactionManager);
        // Import recognition runs from afterCommit, where REQUIRED would join the
        // already-finished upload transaction and never commit file or batch status.
        this.requiredTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void processFile(UUID userId, UUID invoiceFileId) {
        try {
            requiredTx.executeWithoutResult(status -> processInTransaction(userId, invoiceFileId));
        } catch (OcrProcessingException exception) {
            if ("INVOICE_FILE_NOT_FOUND".equals(exception.getCode())) {
                throw exception;
            }
            persistFailedStatus(userId, invoiceFileId);
            throw exception;
        } catch (RuntimeException exception) {
            persistFailedStatus(userId, invoiceFileId);
            throw exception;
        } finally {
            if (contentHolder != null) {
                contentHolder.take();
            }
        }
    }

    private void processInTransaction(UUID userId, UUID invoiceFileId) {
        InvoiceFile file = invoiceFileRepository.findByIdAndUserId(invoiceFileId, userId)
                .orElseThrow(() -> new OcrProcessingException(
                        HttpStatus.NOT_FOUND,
                        "INVOICE_FILE_NOT_FOUND",
                        "Invoice file was not found"));
        Instant now = clock.instant();
        file.markStatus("OCR_PROCESSING", now);
        invoiceFileRepository.save(file);

        try {
            byte[] bytes;
            try (InputStream input = storage.open(userId, file.getStorageKey())) {
                bytes = input.readAllBytes();
            }
            if (contentHolder != null) {
                contentHolder.set(bytes);
            }
            OcrDocument document = recognizeDocument(userId, file, bytes);
            NormalizedInvoice normalized = normalizer.normalize(document);
            String rawJson = storedRaw(document);
            String serializedFields = fieldsJson(normalized);

            ocrResultRepository.findByInvoiceFileIdAndUserId(file.getId(), userId)
                    .ifPresentOrElse(
                            existing -> {
                                existing.replace(document.providerRequestId(), rawJson);
                                ocrResultRepository.save(existing);
                            },
                            () -> ocrResultRepository.save(new OcrResult(
                                    UUID.randomUUID(),
                                    userId,
                                    file.getId(),
                                    document.providerRequestId(),
                                    rawJson,
                                    now)));
            UUID normalizedId = normalizedInvoiceRepository
                    .findByInvoiceFileIdAndUserId(file.getId(), userId)
                    .map(existing -> {
                        existing.replace(
                                normalized.type(),
                                normalized.invoiceCode(),
                                normalized.invoiceNumber(),
                                normalized.invoiceDate(),
                                normalized.amount(),
                                normalized.tax(),
                                normalized.total(),
                                serializedFields);
                        normalizedInvoiceRepository.save(existing);
                        return existing.getId();
                    })
                    .orElseGet(() -> {
                        UUID createdId = UUID.randomUUID();
                        normalizedInvoiceRepository.save(new NormalizedInvoiceEntity(
                                createdId,
                                userId,
                                file.getId(),
                                normalized.type(),
                                normalized.invoiceCode(),
                                normalized.invoiceNumber(),
                                normalized.invoiceDate(),
                                normalized.amount(),
                                normalized.tax(),
                                normalized.total(),
                                serializedFields,
                                now));
                        return createdId;
                    });

            Invoice invoice = invoiceRepository.findByUserIdAndInvoiceFileId(userId, file.getId())
                    .map(existing -> {
                        if (entityManager != null && entityManager.contains(existing)) {
                            entityManager.detach(existing);
                        }
                        existing.replaceFromNormalized(normalized, now);
                        existing.linkSources(file.getId(), normalizedId);
                        return existing;
                    })
                    .orElseGet(() -> {
                        Invoice created = Invoice.fromNormalized(
                                UUID.randomUUID(), userId, normalized, clock.instant());
                        created.linkSources(file.getId(), normalizedId);
                        return created;
                    });
            deduplicationService.evaluate(userId, invoice);

            file.markStatus(invoice.getStatus().name(), clock.instant());
            invoiceFileRepository.save(file);
            refreshBatch(userId, file.getBatchId());
        } catch (IOException exception) {
            throw new PaddleServiceUnavailableException();
        }
    }

    private OcrDocument recognizeDocument(UUID userId, InvoiceFile file, byte[] bytes) {
        if ("application/pdf".equals(file.getMediaType())) {
            OcrDocument pdf = documentFromPdf(bytes);
            if (pdf != null && hasUsefulFields(pdf)) {
                return pdf;
            }
        }
        PaddleCredential credential = credentialService.decryptForUser(userId);
        return ocrProvider.recognize(credential, file.toStoredFile());
    }

    private static OcrDocument documentFromPdf(byte[] bytes) {
        List<String> lines = PdfTextExtractor.lines(bytes);
        boolean invoiceText = false;
        for (String line : lines) {
            if (line.contains("发票") || line.contains("客票") || line.contains("行程")) {
                invoiceText = true;
                break;
            }
        }
        if (!invoiceText) {
            return null;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("source", "pdf-text");
            payload.put("lines", lines);
            return PaddleOcrV5Parser.fromLines(lines, MAPPER.writeValueAsString(payload));
        } catch (JsonProcessingException exception) {
            return PaddleOcrV5Parser.fromLines(lines, "{\"source\":\"pdf-text\"}");
        }
    }

    private static boolean hasUsefulFields(OcrDocument document) {
        for (OcrField field : document.fields()) {
            if ("type".equals(field.name()) || "InvoiceType".equals(field.name())) {
                continue;
            }
            if (field.rawValue() != null && !field.rawValue().isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static String storedRaw(OcrDocument document) {
        String raw = document.rawJson();
        return raw == null || raw.isBlank() ? "{}" : raw;
    }

    private void persistFailedStatus(UUID userId, UUID invoiceFileId) {
        requiresNewTx.executeWithoutResult(status -> {
            InvoiceFile failed = invoiceFileRepository.findByIdAndUserId(invoiceFileId, userId)
                    .orElseThrow(() -> new OcrProcessingException(
                            HttpStatus.NOT_FOUND,
                            "INVOICE_FILE_NOT_FOUND",
                            "Invoice file was not found"));
            if (isSuccessfulOutcome(failed.getStatus())) {
                return;
            }
            failed.markStatus("FAILED", clock.instant());
            invoiceFileRepository.save(failed);
            refreshBatch(userId, failed.getBatchId());
        });
    }

    private static boolean isSuccessfulOutcome(String status) {
        return "PASSED".equals(status)
                || "REVIEW_REQUIRED".equals(status)
                || "BLOCKED_DUPLICATE".equals(status);
    }

    private void refreshBatch(UUID userId, UUID batchId) {
        if (batchStatusSync != null && batchId != null) {
            batchStatusSync.refresh(userId, batchId);
        }
    }

    private static String fieldsJson(NormalizedInvoice normalized) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            normalized.fields().forEach((name, field) -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("sourceText", field.sourceText());
                entry.put("confidence", field.confidence());
                payload.put(name, entry);
            });
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize normalized fields", exception);
        }
    }
}
