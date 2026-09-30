package com.acme.invoice.deduplication;

import com.acme.invoice.invoice.Invoice;
import com.acme.invoice.invoice.InvoiceRepository;
import com.acme.invoice.invoice.InvoiceStatus;
import com.acme.invoice.ocr.NormalizedInvoice;
import com.acme.invoice.verification.VerificationEngine;
import com.acme.invoice.verification.VerificationIssue;
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

    private final InvoiceRepository invoices;
    private final ExactDuplicateKeyFactory keyFactory;
    private final SuspectedDuplicateScorer scorer;
    private final VerificationEngine verificationEngine;
    private final Clock clock;
    private final TransactionTemplate requiresNewTx;

    public DeduplicationService(
            InvoiceRepository invoices,
            ExactDuplicateKeyFactory keyFactory,
            SuspectedDuplicateScorer scorer,
            VerificationEngine verificationEngine,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.invoices = invoices;
        this.keyFactory = keyFactory;
        this.scorer = scorer;
        this.verificationEngine = verificationEngine;
        this.clock = clock;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public DeduplicationResult evaluate(UUID userId, Invoice invoice) {
        if (!userId.equals(invoice.getUserId())) {
            throw new IllegalArgumentException("Invoice user scope mismatch");
        }

        NormalizedInvoice normalized = invoice.toNormalized();
        Optional<String> exactKey = keyFactory.create(normalized);
        exactKey.ifPresent(invoice::assignExactDuplicateKey);

        if (exactKey.isPresent()) {
            Optional<Invoice> existing = invoices.findActiveByUserIdAndExactDuplicateKey(
                    userId, exactKey.get());
            if (existing.isPresent() && !existing.get().getId().equals(invoice.getId())) {
                return persistBlocked(invoice, existing.get());
            }
        }

        List<VerificationIssue> issues = verificationEngine.verify(normalized);
        InvoiceStatus eligibleStatus = verificationEngine.statusFor(issues);
        if (eligibleStatus == InvoiceStatus.FAILED) {
            eligibleStatus = InvoiceStatus.REVIEW_REQUIRED;
        }
        invoice.markStatus(eligibleStatus, clock.instant());

        try {
            requiresNewTx.executeWithoutResult(status -> invoices.saveAndFlush(invoice));
        } catch (DataIntegrityViolationException conflict) {
            Invoice matched = invoices.findActiveByUserIdAndExactDuplicateKey(
                            userId, invoice.getExactDuplicateKey())
                    .orElseThrow(() -> conflict);
            return persistBlocked(invoice, matched);
        }

        DuplicateScore suspected = findBestSuspected(userId, invoice, normalized);
        if (suspected != null) {
            invoice.markStatus(InvoiceStatus.REVIEW_REQUIRED, clock.instant());
            requiresNewTx.executeWithoutResult(status -> invoices.saveAndFlush(invoice));
        }
        return new DeduplicationResult(false, null, suspected);
    }

    private DeduplicationResult persistBlocked(Invoice invoice, Invoice matched) {
        Instant now = clock.instant();
        invoice.markStatus(InvoiceStatus.BLOCKED_DUPLICATE, now);
        requiresNewTx.executeWithoutResult(status -> invoices.saveAndFlush(invoice));
        return new DeduplicationResult(
                true,
                matched.getId(),
                new DuplicateScore(BigDecimal.ONE, List.of("exact_duplicate_key")));
    }

    private DuplicateScore findBestSuspected(
            UUID userId,
            Invoice invoice,
            NormalizedInvoice normalized) {
        return invoices.findByUserIdAndIdNot(userId, invoice.getId()).stream()
                .map(other -> scorer.score(normalized, other.toNormalized()))
                .filter(score -> !score.matchedFields().isEmpty())
                .filter(score -> score.score().compareTo(SUSPECT_THRESHOLD) >= 0)
                .max(Comparator.comparing(DuplicateScore::score))
                .orElse(null);
    }
}
