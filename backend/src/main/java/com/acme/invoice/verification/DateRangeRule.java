package com.acme.invoice.verification;

import com.acme.invoice.ocr.NormalizedInvoice;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class DateRangeRule implements VerificationRule {

    private final Clock clock;
    private final int maxAgeYears;

    public DateRangeRule(Clock clock, int maxAgeYears) {
        this.clock = clock;
        this.maxAgeYears = maxAgeYears;
    }

    @Override
    public List<VerificationIssue> evaluate(NormalizedInvoice invoice) {
        List<VerificationIssue> issues = new ArrayList<>();
        LocalDate date = invoice.invoiceDate();
        if (date == null) {
            return issues;
        }
        LocalDate today = LocalDate.now(clock);
        if (date.isAfter(today)) {
            issues.add(new VerificationIssue(
                    "FUTURE_DATE",
                    "invoiceDate",
                    "invoice date is in the future",
                    VerificationSeverity.REVIEW));
        }
        if (date.isBefore(today.minusYears(maxAgeYears))) {
            issues.add(new VerificationIssue(
                    "IMPLAUSIBLY_OLD_DATE",
                    "invoiceDate",
                    "invoice date is implausibly old",
                    VerificationSeverity.REVIEW));
        }
        return issues;
    }
}
