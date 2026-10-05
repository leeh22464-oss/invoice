package com.acme.invoice.review;

import com.acme.invoice.audit.AuditService;
import com.acme.invoice.deduplication.DeduplicationService;
import com.acme.invoice.deduplication.DuplicateMatchEntity;
import com.acme.invoice.deduplication.DuplicateMatchRepository;
import com.acme.invoice.ingestion.FileStorage;
import com.acme.invoice.ingestion.InvoiceFile;
import com.acme.invoice.ingestion.InvoiceFileRepository;
import com.acme.invoice.ingestion.PreviewFile;
import com.acme.invoice.invoice.Invoice;
import com.acme.invoice.invoice.InvoiceRepository;
import com.acme.invoice.invoice.InvoiceStatus;
import com.acme.invoice.ocr.OcrProcessingService;
import com.acme.invoice.verification.VerificationIssue;
import com.acme.invoice.verification.VerificationIssueEntity;
import com.acme.invoice.verification.VerificationIssueRepository;
import com.acme.invoice.verification.VerificationSeverity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Profile("!smoke")
public class InvoiceReviewService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> FIELD_LIST = new TypeReference<>() {};

    private final InvoiceRepository invoices;
    private final InvoiceFileRepository files;
    private final FileStorage storage;
    private final AuditService auditService;
    private final DeduplicationService deduplicationService;
    private final OcrProcessingService ocrProcessingService;
    private final VerificationIssueRepository issueRepository;
    private final DuplicateMatchRepository matchRepository;
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
            VerificationIssueRepository issueRepository,
            DuplicateMatchRepository matchRepository,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.invoices = invoices;
        this.files = files;
        this.storage = storage;
        this.auditService = auditService;
        this.deduplicationService = deduplicationService;
        this.ocrProcessingService = ocrProcessingService;
        this.issueRepository = issueRepository;
        this.matchRepository = matchRepository;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional(readOnly = true)
    public List<InvoiceView> list(UUID userId) {
        List<Invoice> rows = invoices.findByUserIdOrderByCreatedAtDesc(userId);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = rows.stream().map(Invoice::getId).toList();
        Map<UUID, List<VerificationIssue>> issues = issueRepository.findByUserIdAndInvoiceIdIn(userId, ids)
                .stream()
                .collect(Collectors.groupingBy(
                        VerificationIssueEntity::getInvoiceId,
                        Collectors.mapping(InvoiceReviewService::toIssue, Collectors.toList())));
        Map<UUID, DuplicateEvidence> evidence = matchRepository.findByUserIdAndInvoiceIdIn(userId, ids)
                .stream()
                .collect(Collectors.groupingBy(DuplicateMatchEntity::getInvoiceId))
                .entrySet()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> toEvidence(entry.getValue())));
        return rows.stream()
                .map(invoice -> InvoiceView.from(
                        invoice,
                        issues.getOrDefault(invoice.getId(), List.of()),
                        evidence.get(invoice.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public InvoiceView get(UUID userId, UUID invoiceId) {
        Invoice invoice = requireOwned(userId, invoiceId);
        List<VerificationIssue> issues = issueRepository.findByUserIdAndInvoiceId(userId, invoiceId)
                .stream()
                .map(InvoiceReviewService::toIssue)
                .toList();
        List<DuplicateMatchEntity> matches = matchRepository.findByUserIdAndInvoiceId(userId, invoiceId);
        return InvoiceView.from(invoice, issues, matches.isEmpty() ? null : toEvidence(matches));
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
            boolean fieldsChanged = false;
            if (request != null && request.fields() != null) {
                for (Map.Entry<String, String> entry : request.fields().entrySet()) {
                    String field = entry.getKey();
                    String newValue = entry.getValue();
                    String oldValue = invoice.readFieldValue(field);
                    if (Objects.equals(oldValue, newValue)) {
                        continue;
                    }
                    invoice.applyFieldCorrection(field, newValue, clock.instant());
                    auditService.recordCorrection(userId, invoiceId, field, oldValue, newValue);
                    fieldsChanged = true;
                }
            }
            boolean statusChanged = false;
            if (request != null
                    && "PASSED".equalsIgnoreCase(request.status())
                    && invoice.getStatus() != InvoiceStatus.BLOCKED_DUPLICATE
                    && invoice.getStatus() != InvoiceStatus.PASSED) {
                String previous = invoice.getStatus().name();
                invoice.markStatus(InvoiceStatus.PASSED, clock.instant());
                auditService.recordCorrection(userId, invoiceId, "status", previous, "PASSED");
                statusChanged = true;
            }
            if (fieldsChanged || statusChanged) {
                invoices.save(invoice);
            }
            return fieldsChanged;
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

    public InvoiceView retry(UUID userId, UUID invoiceId) {
        UUID fileId = transactionTemplate.execute(status -> {
            Invoice invoice = requireOwned(userId, invoiceId);
            if (invoice.getInvoiceFileId() == null) {
                throw new ReviewException(
                        "RETRY_UNAVAILABLE",
                        "Invoice has no source file to retry",
                        HttpStatus.UNPROCESSABLE_ENTITY);
            }
            return invoice.getInvoiceFileId();
        });
        ocrProcessingService.processFile(userId, fileId);
        return get(userId, invoiceId);
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

    private static VerificationIssue toIssue(VerificationIssueEntity row) {
        return new VerificationIssue(
                row.getCode(),
                row.getFieldName(),
                row.getMessage(),
                VerificationSeverity.valueOf(row.getSeverity()));
    }

    private static DuplicateEvidence toEvidence(List<DuplicateMatchEntity> matches) {
        DuplicateMatchEntity best = matches.stream()
                .max(Comparator
                        .comparingInt((DuplicateMatchEntity row) -> "EXACT".equals(row.getMatchType()) ? 1 : 0)
                        .thenComparing(row -> row.getScore() == null ? BigDecimal.ZERO : row.getScore()))
                .orElseThrow();
        return new DuplicateEvidence(
                best.getMatchType(),
                best.getMatchedInvoiceId(),
                best.getScore(),
                readFields(best.getMatchedFieldsJson()));
    }

    private static List<String> readFields(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.copyOf(MAPPER.readValue(json, FIELD_LIST));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            return List.of();
        }
    }

    private Invoice requireOwned(UUID userId, UUID invoiceId) {
        return invoices.findByIdAndUserId(invoiceId, userId)
                .orElseThrow(() -> new ReviewException(
                        "INVOICE_NOT_FOUND",
                        "Invoice not found",
                        HttpStatus.NOT_FOUND));
    }
}
