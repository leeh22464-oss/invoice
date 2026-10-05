package com.acme.invoice.deduplication;

import com.acme.invoice.invoice.Invoice;
import com.acme.invoice.invoice.InvoiceRepository;
import com.acme.invoice.invoice.InvoiceStatus;
import com.acme.invoice.ocr.NormalizedInvoice;
import com.acme.invoice.verification.VerificationEngine;
import com.acme.invoice.verification.VerificationIssue;
import com.acme.invoice.verification.VerificationIssueEntity;
import com.acme.invoice.verification.VerificationIssueRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Profile("!smoke")
public class DeduplicationService {

    private static final BigDecimal SUSPECT_THRESHOLD = new BigDecimal("0.50");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final InvoiceRepository invoices;
    private final ExactDuplicateKeyFactory keyFactory;
    private final SuspectedDuplicateScorer scorer;
    private final VerificationEngine verificationEngine;
    private final VerificationIssueRepository issueRepository;
    private final DuplicateMatchRepository matchRepository;
    private final Clock clock;
    private final TransactionTemplate requiresNewTx;

    public DeduplicationService(
            InvoiceRepository invoices,
            ExactDuplicateKeyFactory keyFactory,
            SuspectedDuplicateScorer scorer,
            VerificationEngine verificationEngine,
            VerificationIssueRepository issueRepository,
            DuplicateMatchRepository matchRepository,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.invoices = invoices;
        this.keyFactory = keyFactory;
        this.scorer = scorer;
        this.verificationEngine = verificationEngine;
        this.issueRepository = issueRepository;
        this.matchRepository = matchRepository;
        this.clock = clock;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public DeduplicationResult evaluate(UUID userId, Invoice invoice) {
        if (!userId.equals(invoice.getUserId())) {
            throw new IllegalArgumentException("Invoice user scope mismatch");
        }

        boolean humanAccepted = invoice.getStatus() == InvoiceStatus.PASSED;
        NormalizedInvoice normalized = invoice.toNormalized();
        Optional<String> exactKey = keyFactory.create(normalized);
        if (exactKey.isPresent()) {
            invoice.assignExactDuplicateKey(exactKey.get());
        } else {
            invoice.clearExactDuplicateKey();
        }

        List<VerificationIssue> issues = verificationEngine.verify(normalized);
        if (exactKey.isPresent()) {
            Optional<Invoice> existing = invoices.findActiveByUserIdAndExactDuplicateKey(
                    userId, exactKey.get());
            if (existing.isPresent() && !existing.get().getId().equals(invoice.getId())) {
                return persistBlocked(invoice, existing.get(), issues);
            }
        }

        InvoiceStatus eligibleStatus = verificationEngine.statusFor(issues);
        if (eligibleStatus == InvoiceStatus.FAILED) {
            eligibleStatus = InvoiceStatus.REVIEW_REQUIRED;
        }
        invoice.markStatus(eligibleStatus, clock.instant());

        SuspectedHit suspected = findBestSuspected(userId, invoice, normalized);
        boolean suspicionWouldDemote = suspected != null
                && !(humanAccepted && eligibleStatus == InvoiceStatus.PASSED);
        if (suspicionWouldDemote) {
            invoice.markStatus(InvoiceStatus.REVIEW_REQUIRED, clock.instant());
        }

        try {
            requiresNewTx.executeWithoutResult(status -> {
                invoices.saveAndFlush(invoice);
                replaceIssues(invoice, issues);
                if (suspected != null) {
                    replaceMatch(invoice, suspected.matchedInvoiceId(), "SUSPECTED", suspected.score());
                } else {
                    matchRepository.deleteByUserIdAndInvoiceId(invoice.getUserId(), invoice.getId());
                }
            });
        } catch (DataIntegrityViolationException conflict) {
            Invoice matched = invoices.findActiveByUserIdAndExactDuplicateKey(
                            userId, invoice.getExactDuplicateKey())
                    .orElseThrow(() -> conflict);
            return persistBlocked(invoice, matched, issues);
        }

        return new DeduplicationResult(
                false,
                null,
                suspected == null ? null : suspected.score());
    }

    private DeduplicationResult persistBlocked(
            Invoice invoice,
            Invoice matched,
            List<VerificationIssue> issues) {
        Instant now = clock.instant();
        invoice.markStatus(InvoiceStatus.BLOCKED_DUPLICATE, now);
        DuplicateScore score = new DuplicateScore(BigDecimal.ONE, List.of("exact_duplicate_key"));
        requiresNewTx.executeWithoutResult(status -> {
            invoices.saveAndFlush(invoice);
            replaceIssues(invoice, issues);
            replaceMatch(invoice, matched.getId(), "EXACT", score);
        });
        return new DeduplicationResult(true, matched.getId(), score);
    }

    private void replaceIssues(Invoice invoice, List<VerificationIssue> issues) {
        issueRepository.deleteByUserIdAndInvoiceId(invoice.getUserId(), invoice.getId());
        issueRepository.flush();
        Instant now = clock.instant();
        for (VerificationIssue issue : issues) {
            issueRepository.save(new VerificationIssueEntity(
                    UUID.randomUUID(),
                    invoice.getUserId(),
                    invoice.getId(),
                    issue.code(),
                    issue.field(),
                    issue.message(),
                    issue.severity().name(),
                    now));
        }
    }

    private void replaceMatch(Invoice invoice, UUID matchedInvoiceId, String matchType, DuplicateScore score) {
        matchRepository.deleteByUserIdAndInvoiceId(invoice.getUserId(), invoice.getId());
        matchRepository.flush();
        matchRepository.save(new DuplicateMatchEntity(
                UUID.randomUUID(),
                invoice.getUserId(),
                invoice.getId(),
                matchedInvoiceId,
                matchType,
                score.score(),
                matchedFieldsJson(score.matchedFields()),
                clock.instant()));
    }

    private SuspectedHit findBestSuspected(
            UUID userId,
            Invoice invoice,
            NormalizedInvoice normalized) {
        return invoices.findByUserIdAndIdNot(userId, invoice.getId()).stream()
                .map(other -> new SuspectedHit(
                        other.getId(),
                        scorer.score(normalized, other.toNormalized())))
                .filter(hit -> !hit.score().matchedFields().isEmpty())
                .filter(hit -> hit.score().score().compareTo(SUSPECT_THRESHOLD) >= 0)
                .max(Comparator.comparing(hit -> hit.score().score()))
                .orElse(null);
    }

    private static String matchedFieldsJson(List<String> matchedFields) {
        try {
            return MAPPER.writeValueAsString(matchedFields == null ? List.of() : matchedFields);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize duplicate match fields", exception);
        }
    }

    private record SuspectedHit(UUID matchedInvoiceId, DuplicateScore score) {
    }
}
