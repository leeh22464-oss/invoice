package com.acme.invoice.ocr;

import com.acme.invoice.credential.EncryptedSecret;
import com.acme.invoice.credential.OcrCredential;
import com.acme.invoice.credential.OcrCredentialPersistence;
import com.acme.invoice.credential.OcrCredentialService;
import com.acme.invoice.credential.PaddleCredential;
import com.acme.invoice.credential.SecretCipher;
import com.acme.invoice.deduplication.DeduplicationResult;
import com.acme.invoice.deduplication.DeduplicationService;
import com.acme.invoice.ingestion.FileStorage;
import com.acme.invoice.ingestion.InvoiceFile;
import com.acme.invoice.ingestion.InvoiceFileRepository;
import com.acme.invoice.ingestion.StoredFile;
import com.acme.invoice.invoice.Invoice;
import com.acme.invoice.invoice.InvoiceRepository;
import com.acme.invoice.invoice.InvoiceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OcrProcessingServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID batchId = UUID.randomUUID();
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-30T00:00:00Z"), ZoneOffset.UTC);

    private OcrCredentialPersistence persistence;
    private SecretCipher cipher;
    private OcrCredentialService credentialService;
    private FileStorage storage;
    private OcrProvider ocrProvider;
    private InvoiceNormalizer normalizer;
    private OcrResultRepository ocrResultRepository;
    private NormalizedInvoiceRepository normalizedInvoiceRepository;
    private InvoiceFileRepository invoiceFileRepository;
    private DeduplicationService deduplicationService;
    private OcrProcessingService service;

    @BeforeEach
    void setUp() {
        persistence = mock(OcrCredentialPersistence.class);
        cipher = mock(SecretCipher.class);
        credentialService = new OcrCredentialService(
                persistence, cipher, mock(PaddleTokenClient.class), clock);
        storage = mock(FileStorage.class);
        ocrProvider = mock(OcrProvider.class);
        normalizer = mock(InvoiceNormalizer.class);
        ocrResultRepository = mock(OcrResultRepository.class);
        normalizedInvoiceRepository = mock(NormalizedInvoiceRepository.class);
        invoiceFileRepository = mock(InvoiceFileRepository.class);
        deduplicationService = mock(DeduplicationService.class);
        when(deduplicationService.evaluate(eq(userId), any(Invoice.class))).thenAnswer(invocation -> {
            Invoice invoice = invocation.getArgument(1);
            invoice.markStatus(InvoiceStatus.REVIEW_REQUIRED, clock.instant());
            return new DeduplicationResult(false, null, null);
        });
        service = new OcrProcessingService(
                credentialService,
                storage,
                ocrProvider,
                normalizer,
                ocrResultRepository,
                normalizedInvoiceRepository,
                invoiceFileRepository,
                mock(InvoiceRepository.class),
                deduplicationService,
                null,
                null,
                clock,
                passthroughTransactionManager());
    }

    private static PlatformTransactionManager passthroughTransactionManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
    }

    @Test
    void decryptForUserReturnsOwnerScopedCredential() {
        OcrCredential stored = new OcrCredential(
                UUID.randomUUID(),
                userId,
                new byte[]{1},
                new byte[]{2},
                new byte[]{3},
                new byte[]{4},
                "api-****key1",
                "VALID",
                clock.instant(),
                clock.instant(),
                clock.instant());
        when(persistence.findByUserId(userId)).thenReturn(Optional.of(stored));
        when(cipher.decrypt(any(EncryptedSecret.class))).thenReturn("api-key", "secret-key");

        PaddleCredential credential = credentialService.decryptForUser(userId);

        assertThat(credential).isEqualTo(new PaddleCredential("api-key", "secret-key"));
        verify(persistence).findByUserId(userId);
    }

    @Test
    void processesFilesIndependentlyAndPersistsRawSeparately() {
        InvoiceFile ok = invoiceFile("ok.png");
        InvoiceFile bad = invoiceFile("bad.png");
        when(invoiceFileRepository.findByIdAndUserId(ok.getId(), userId)).thenReturn(Optional.of(ok));
        when(invoiceFileRepository.findByIdAndUserId(bad.getId(), userId)).thenReturn(Optional.of(bad));
        stubCredential();
        when(storage.open(eq(userId), any())).thenReturn(new ByteArrayInputStream(new byte[]{1, 2}));

        OcrDocument document = new OcrDocument(
                "9",
                "{\"log_id\":9}",
                List.of(new OcrField("InvoiceNum", "001", new BigDecimal("0.9"))));
        NormalizedInvoice normalized = new NormalizedInvoice(
                InvoiceType.VAT_SPECIAL,
                "033001900111",
                "001",
                LocalDate.of(2024, 1, 15),
                new BigDecimal("100.00"),
                new BigDecimal("13.00"),
                new BigDecimal("113.00"),
                Map.of("InvoiceNum", new NormalizedField("001", new BigDecimal("0.9"))));
        when(ocrProvider.recognize(any(), any())).thenReturn(document)
                .thenThrow(new PaddleServiceUnavailableException());
        when(normalizer.normalize(document)).thenReturn(normalized);

        AtomicInteger failures = new AtomicInteger();
        service.processFile(userId, ok.getId());
        try {
            service.processFile(userId, bad.getId());
        } catch (RuntimeException exception) {
            failures.incrementAndGet();
        }

        assertThat(failures.get()).isEqualTo(1);
        verify(ocrResultRepository).save(argThat(result ->
                result.getUserId().equals(userId)
                        && result.getInvoiceFileId().equals(ok.getId())
                        && result.getRawJson().equals("{\"log_id\":9}")));
        verify(normalizedInvoiceRepository).save(argThat(row ->
                row.getUserId().equals(userId)
                        && row.getInvoiceFileId().equals(ok.getId())
                        && row.getInvoiceType() == InvoiceType.VAT_SPECIAL
                        && "001".equals(row.getInvoiceNumber())));
        verify(deduplicationService).evaluate(eq(userId), argThat(invoice ->
                invoice.getInvoiceFileId().equals(ok.getId())
                        && invoice.getUserId().equals(userId)));
        verify(invoiceFileRepository, atLeastOnce()).save(argThat(file ->
                file.getId().equals(ok.getId())
                        && "REVIEW_REQUIRED".equals(file.getStatus())));
        verify(invoiceFileRepository, atLeastOnce()).save(argThat(file ->
                file.getId().equals(bad.getId())
                        && "FAILED".equals(file.getStatus())));
        verify(normalizedInvoiceRepository, never()).save(argThat(row ->
                row.getInvoiceFileId().equals(bad.getId())));
        verify(deduplicationService, never()).evaluate(eq(userId), argThat(invoice ->
                invoice.getInvoiceFileId().equals(bad.getId())));
    }

    private void stubCredential() {
        OcrCredential stored = new OcrCredential(
                UUID.randomUUID(),
                userId,
                new byte[]{1},
                new byte[]{2},
                new byte[]{3},
                new byte[]{4},
                "mask",
                "VALID",
                clock.instant(),
                clock.instant(),
                clock.instant());
        when(persistence.findByUserId(userId)).thenReturn(Optional.of(stored));
        when(cipher.decrypt(any())).thenReturn("api", "secret");
    }

    private InvoiceFile invoiceFile(String name) {
        UUID id = UUID.randomUUID();
        return new InvoiceFile(
                id,
                userId,
                batchId,
                name,
                new StoredFile(id.toString(), 2, "ab", "image/png"),
                clock.instant());
    }
}
