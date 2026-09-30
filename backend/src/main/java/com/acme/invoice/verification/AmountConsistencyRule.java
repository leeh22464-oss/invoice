package com.acme.invoice.verification;

import com.acme.invoice.ocr.NormalizedInvoice;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Component
public class AmountConsistencyRule implements VerificationRule {

    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    @Override
    public List<VerificationIssue> evaluate(NormalizedInvoice invoice) {
        List<VerificationIssue> issues = new ArrayList<>();
        if (invoice.amount() == null || invoice.tax() == null || invoice.total() == null) {
            return issues;
        }
        BigDecimal expected = invoice.amount().add(invoice.tax()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal actual = invoice.total().setScale(2, RoundingMode.HALF_UP);
        if (expected.subtract(actual).abs().compareTo(TOLERANCE) > 0) {
            issues.add(new VerificationIssue(
                    "AMOUNT_MISMATCH",
                    "total",
                    "amount + tax does not equal total within one cent",
                    VerificationSeverity.REVIEW));
        }
        return issues;
    }
}
