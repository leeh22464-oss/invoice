package com.acme.invoice.verification;

import com.acme.invoice.invoice.InvoiceStatus;
import com.acme.invoice.ocr.InvoiceType;
import com.acme.invoice.ocr.NormalizedField;
import com.acme.invoice.ocr.NormalizedInvoice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VerificationEngineTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2024-06-15T00:00:00Z"), ZoneOffset.UTC);

    private VerificationEngine engine;

    @BeforeEach
    void setUp() {
        engine = new VerificationEngine(
                List.of(
                        new RequiredFieldRule(),
                        new FormatRule(),
                        new AmountConsistencyRule(),
                        new DateRangeRule(CLOCK, 10),
                        new ConfidenceRule(new BigDecimal("0.80")),
                        new UnknownTypeRule()));
    }

    @Test
    void missingRequiredFieldsProduceReviewIssues() {
        NormalizedInvoice invoice = vat(
                InvoiceType.VAT_SPECIAL,
                null,
                null,
                LocalDate.of(2024, 1, 15),
                new BigDecimal("100.00"),
                new BigDecimal("13.00"),
                new BigDecimal("113.00"),
                Map.of("InvoiceNum", field("00123456", "0.99")));

        List<VerificationIssue> issues = engine.verify(invoice);

        assertThat(issues)
                .anyMatch(issue -> "MISSING_REQUIRED_FIELD".equals(issue.code())
                        && "invoiceCode".equals(issue.field()))
                .anyMatch(issue -> "MISSING_REQUIRED_FIELD".equals(issue.code())
                        && "invoiceNumber".equals(issue.field()));
        assertThat(engine.statusFor(issues)).isEqualTo(InvoiceStatus.REVIEW_REQUIRED);
    }

    @Test
    void invalidFormatsProduceReviewIssues() {
        NormalizedInvoice invoice = vat(
                InvoiceType.VAT_SPECIAL,
                "ABC",
                "12",
                LocalDate.of(2024, 1, 15),
                new BigDecimal("100.00"),
                new BigDecimal("13.00"),
                new BigDecimal("113.00"),
                Map.of());

        List<VerificationIssue> issues = engine.verify(invoice);

        assertThat(issues)
                .anyMatch(issue -> "INVALID_FORMAT".equals(issue.code()));
        assertThat(engine.statusFor(issues)).isEqualTo(InvoiceStatus.REVIEW_REQUIRED);
    }

    @Test
    void futureDatesProduceReviewIssues() {
        NormalizedInvoice invoice = vat(
                InvoiceType.VAT_SPECIAL,
                "033001900111",
                "00123456",
                LocalDate.of(2025, 1, 1),
                new BigDecimal("100.00"),
                new BigDecimal("13.00"),
                new BigDecimal("113.00"),
                Map.of());

        List<VerificationIssue> issues = engine.verify(invoice);

        assertThat(issues).anyMatch(issue -> "FUTURE_DATE".equals(issue.code()));
        assertThat(engine.statusFor(issues)).isEqualTo(InvoiceStatus.REVIEW_REQUIRED);
    }

    @Test
    void implausiblyOldDatesProduceReviewIssues() {
        NormalizedInvoice invoice = vat(
                InvoiceType.VAT_SPECIAL,
                "033001900111",
                "00123456",
                LocalDate.of(2010, 1, 1),
                new BigDecimal("100.00"),
                new BigDecimal("13.00"),
                new BigDecimal("113.00"),
                Map.of());

        List<VerificationIssue> issues = engine.verify(invoice);

        assertThat(issues).anyMatch(issue -> "IMPLAUSIBLY_OLD_DATE".equals(issue.code()));
        assertThat(engine.statusFor(issues)).isEqualTo(InvoiceStatus.REVIEW_REQUIRED);
    }

    @Test
    void amountEqualityAllowsOneCentRoundingTolerance() {
        NormalizedInvoice exact = vat(
                InvoiceType.VAT_SPECIAL,
                "033001900111",
                "00123456",
                LocalDate.of(2024, 1, 15),
                new BigDecimal("100.00"),
                new BigDecimal("13.00"),
                new BigDecimal("113.00"),
                Map.of());
        NormalizedInvoice withinTolerance = vat(
                InvoiceType.VAT_SPECIAL,
                "033001900111",
                "00123457",
                LocalDate.of(2024, 1, 15),
                new BigDecimal("100.00"),
                new BigDecimal("13.00"),
                new BigDecimal("113.01"),
                Map.of());
        NormalizedInvoice beyondTolerance = vat(
                InvoiceType.VAT_SPECIAL,
                "033001900111",
                "00123458",
                LocalDate.of(2024, 1, 15),
                new BigDecimal("100.00"),
                new BigDecimal("13.00"),
                new BigDecimal("113.02"),
                Map.of());

        assertThat(engine.verify(exact)).noneMatch(issue -> "AMOUNT_MISMATCH".equals(issue.code()));
        assertThat(engine.verify(withinTolerance)).noneMatch(issue -> "AMOUNT_MISMATCH".equals(issue.code()));
        assertThat(engine.verify(beyondTolerance)).anyMatch(issue -> "AMOUNT_MISMATCH".equals(issue.code()));
    }

    @Test
    void confidenceBelowThresholdCreatesReviewNotFailure() {
        NormalizedInvoice invoice = vat(
                InvoiceType.VAT_SPECIAL,
                "033001900111",
                "00123456",
                LocalDate.of(2024, 1, 15),
                new BigDecimal("100.00"),
                new BigDecimal("13.00"),
                new BigDecimal("113.00"),
                Map.of(
                        "InvoiceNum", field("00123456", "0.55"),
                        "InvoiceCode", field("033001900111", "0.99")));

        List<VerificationIssue> issues = engine.verify(invoice);

        assertThat(issues).anyMatch(issue -> "LOW_CONFIDENCE".equals(issue.code()));
        assertThat(engine.statusFor(issues)).isEqualTo(InvoiceStatus.REVIEW_REQUIRED);
        assertThat(engine.statusFor(issues)).isNotEqualTo(InvoiceStatus.FAILED);
    }

    @Test
    void unknownInvoiceTypeBecomesReviewRequiredNeverFailed() {
        NormalizedInvoice invoice = new NormalizedInvoice(
                InvoiceType.UNKNOWN,
                "033001900111",
                "00123456",
                LocalDate.of(2024, 1, 15),
                new BigDecimal("100.00"),
                new BigDecimal("13.00"),
                new BigDecimal("113.00"),
                Map.of());

        List<VerificationIssue> issues = engine.verify(invoice);

        assertThat(issues).anyMatch(issue -> "UNKNOWN_TYPE".equals(issue.code()));
        assertThat(engine.statusFor(issues)).isEqualTo(InvoiceStatus.REVIEW_REQUIRED);
        assertThat(engine.statusFor(issues)).isNotEqualTo(InvoiceStatus.FAILED);
    }

    @Test
    void cleanInvoicePasses() {
        NormalizedInvoice invoice = vat(
                InvoiceType.VAT_SPECIAL,
                "033001900111",
                "00123456",
                LocalDate.of(2024, 1, 15),
                new BigDecimal("100.00"),
                new BigDecimal("13.00"),
                new BigDecimal("113.00"),
                Map.of(
                        "InvoiceNum", field("00123456", "0.99"),
                        "InvoiceCode", field("033001900111", "0.98")));

        List<VerificationIssue> issues = engine.verify(invoice);

        assertThat(issues).isEmpty();
        assertThat(engine.statusFor(issues)).isEqualTo(InvoiceStatus.PASSED);
    }

    private static NormalizedInvoice vat(
            InvoiceType type,
            String code,
            String number,
            LocalDate date,
            BigDecimal amount,
            BigDecimal tax,
            BigDecimal total,
            Map<String, NormalizedField> fields) {
        return new NormalizedInvoice(type, code, number, date, amount, tax, total, fields);
    }

    private static NormalizedField field(String text, String confidence) {
        return new NormalizedField(text, new BigDecimal(confidence));
    }
}
