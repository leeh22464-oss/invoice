package com.acme.invoice.review;

import com.acme.invoice.audit.AuditService;
import com.acme.invoice.deduplication.DeduplicationService;
import com.acme.invoice.ingestion.FileStorage;
import com.acme.invoice.ingestion.InvoiceFile;
import com.acme.invoice.ingestion.InvoiceFileRepository;
import com.acme.invoice.ingestion.PreviewFile;
import com.acme.invoice.invoice.Invoice;
import com.acme.invoice.invoice.InvoiceRepository;
import com.acme.invoice.invoice.InvoiceStatus;
import com.acme.invoice.ocr.OcrProcessingService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@Profile("!smoke")
public class InvoiceReviewService {

    private final InvoiceRepository invoices;
    private final InvoiceFileRepository files;
    private final FileStorage storage;
    private final AuditService auditService;
    private final DeduplicationService deduplicationService;
    private final OcrProcessingService ocrProcessingService;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate requiresNewTx;

    public InvoiceReviewService(
            InvoiceRepository invoices,
            InvoiceFileRepository files,
            FileStorage storage,
            AuditService auditService,
            DeduplicationService deduplicationService,
            OcrProcessingService ocrProcessingService,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.invoices = invoices;
        this.files = files;
        this.storage = storage;
        this.auditService = auditService;
        this.deduplicationService = deduplicationService;
        this.ocrProcessingService = ocrProcessingService;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional(readOnly = true)
    public List<InvoiceView> list(UUID userId) {
        return invoices.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(InvoiceView::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public InvoiceView get(UUID userId, UUID invoiceId) {
        return InvoiceView.from(requireOwned(userId, invoiceId));
    }

    public InvoiceView correct(UUID userId, UUID invoiceId, InvoiceCorrectionRequest request) {
        boolean rerun = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            Invoice invoice = requireOwned(userId, invoiceId);
            if (request != null
                    && "PASSED".equalsIgnoreCase(request.status())
                    && invoice.getStatus() == InvoiceStatus.BLOCKED_DUPLICATE) {
                throw new ReviewException(
                        "DUPLICATE_CANNOT_PASS",
                        "A definite duplicate cannot be forced to PASSED",
                        HttpStatus.CONFLICT);
            }
            if (request == null || request.fields() == null) {
                return false;
            }
            for (Map.Entry<String, String> entry : request.fields().entrySet()) {
                String field = entry.getKey();
                String newValue = entry.getValue();
                String oldValue = invoice.readFieldValue(field);
                if (Objects.equals(oldValue, newValue)) {
                    continue;
                }
                invoice.applyFieldCorrection(field, newValue, clock.instant());
                auditService.recordCorrection(userId, invoiceId, field, oldValue, newValue);
            }
            invoices.save(invoice);
            return true;
        }));
        if (rerun) {
            try {
                deduplicationService.evaluate(userId, requireOwned(userId, invoiceId));
            } catch (RuntimeException exception) {
                requiresNewTx.executeWithoutResult(status -> {
                    Invoice invoice = requireOwned(userId, invoiceId);
                    if (invoice.getStatus() != InvoiceStatus.BLOCKED_DUPLICATE) {
                        invoice.markStatus(InvoiceStatus.REVIEW_REQUIRED, clock.instant());
                        invoices.save(invoice);
                    }
                });
                throw exception;
            }
        }
        return get(userId, invoiceId);
    }

    @Transactional
    public InvoiceView retry(UUID userId, UUID invoiceId) {
        Invoice invoice = requireOwned(userId, invoiceId);
        UUID fileId = invoice.getInvoiceFileId();
        if (fileId == null) {
            throw new ReviewException(
                    "RETRY_UNAVAILABLE",
                    "Invoice has no source file to retry",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        ocrProcessingService.processFile(userId, fileId);
        return InvoiceView.from(requireOwned(userId, invoiceId));
    }

    @Transactional(readOnly = true)
    public PreviewFile preview(UUID userId, UUID invoiceId) {
        Invoice invoice = requireOwned(userId, invoiceId);
        UUID fileId = invoice.getInvoiceFileId();
        if (fileId == null) {
            throw new ReviewException(
                    "PREVIEW_UNAVAILABLE",
                    "Invoice has no source file",
                    HttpStatus.NOT_FOUND);
        }
        InvoiceFile file = files.findByIdAndUserId(fileId, userId)
                .orElseThrow(() -> new ReviewException(
                        "INVOICE_NOT_FOUND",
                        "Invoice not found",
                        HttpStatus.NOT_FOUND));
        String display = file.getOriginalName()
                .replace('\\', '/')
                .substring(file.getOriginalName().replace('\\', '/').lastIndexOf('/') + 1)
                .replaceAll("[\\p{Cntrl}\"]", "_");
        if (display.isBlank()) {
            display = "invoice";
        }
        return new PreviewFile(
                storage.open(userId, file.getStorageKey()),
                file.getMediaType(),
                file.getSize(),
                display);
    }

    private Invoice requireOwned(UUID userId, UUID invoiceId) {
        return invoices.findByIdAndUserId(invoiceId, userId)
                .orElseThrow(() -> new ReviewException(
                        "INVOICE_NOT_FOUND",
                        "Invoice not found",
                        HttpStatus.NOT_FOUND));
    }
}
