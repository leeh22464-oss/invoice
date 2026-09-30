package com.acme.invoice.ocr;

import com.acme.invoice.credential.OcrCredentialService;
import com.acme.invoice.credential.PaddleCredential;
import com.acme.invoice.ingestion.FileStorage;
import com.acme.invoice.ingestion.InvoiceFile;
import com.acme.invoice.ingestion.InvoiceFileRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
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
    private final StoredFileContentHolder contentHolder;
    private final Clock clock;

    public OcrProcessingService(
            OcrCredentialService credentialService,
            FileStorage storage,
            OcrProvider ocrProvider,
            InvoiceNormalizer normalizer,
            OcrResultRepository ocrResultRepository,
            NormalizedInvoiceRepository normalizedInvoiceRepository,
            InvoiceFileRepository invoiceFileRepository,
            StoredFileContentHolder contentHolder,
            Clock clock) {
        this.credentialService = credentialService;
        this.storage = storage;
        this.ocrProvider = ocrProvider;
        this.normalizer = normalizer;
        this.ocrResultRepository = ocrResultRepository;
        this.normalizedInvoiceRepository = normalizedInvoiceRepository;
        this.invoiceFileRepository = invoiceFileRepository;
        this.contentHolder = contentHolder;
        this.clock = clock;
    }

    @Transactional
    public void processFile(UUID userId, UUID invoiceFileId) {
        InvoiceFile file = invoiceFileRepository.findByIdAndUserId(invoiceFileId, userId)
                .orElseThrow(() -> new OcrProcessingException(
                        HttpStatus.NOT_FOUND,
                        "INVOICE_FILE_NOT_FOUND",
                        "Invoice file was not found"));
        Instant now = clock.instant();
        file.markStatus("OCR_PROCESSING", now);
        invoiceFileRepository.save(file);

        try {
            PaddleCredential credential = credentialService.decryptForUser(userId);
            byte[] bytes;
            try (InputStream input = storage.open(userId, file.getStorageKey())) {
                bytes = input.readAllBytes();
            }
            if (contentHolder != null) {
                contentHolder.set(bytes);
            }
            OcrDocument document = ocrProvider.recognize(credential, file.toStoredFile());
            NormalizedInvoice normalized = normalizer.normalize(document);

            ocrResultRepository.save(new OcrResult(
                    UUID.randomUUID(),
                    userId,
                    file.getId(),
                    document.providerRequestId(),
                    document.rawJson(),
                    now));
            normalizedInvoiceRepository.save(new NormalizedInvoiceEntity(
                    UUID.randomUUID(),
                    userId,
                    file.getId(),
                    normalized.type(),
                    normalized.invoiceCode(),
                    normalized.invoiceNumber(),
                    normalized.invoiceDate(),
                    normalized.amount(),
                    normalized.tax(),
                    normalized.total(),
                    fieldsJson(normalized),
                    now));

            file.markStatus("REVIEW_REQUIRED", clock.instant());
            invoiceFileRepository.save(file);
        } catch (RuntimeException | IOException exception) {
            file.markStatus("FAILED", clock.instant());
            invoiceFileRepository.save(file);
            if (exception instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new PaddleServiceUnavailableException();
        } finally {
            if (contentHolder != null) {
                contentHolder.take();
            }
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
