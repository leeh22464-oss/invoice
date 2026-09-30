package com.acme.invoice.verification;

import com.acme.invoice.ocr.NormalizedField;
import com.acme.invoice.ocr.NormalizedInvoice;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ConfidenceRule implements VerificationRule {

    private static final Set<String> CRITICAL_FIELDS = Set.of(
            "InvoiceCode",
            "InvoiceNum",
            "invoice_number",
            "invoice_num",
            "ticket_num",
            "ticket_number",
            "InvoiceDate",
            "AmountInFiguers",
            "ticket_rates",
            "TotalFare");

    private final BigDecimal threshold;

    public ConfidenceRule(BigDecimal threshold) {
        this.threshold = threshold;
    }

    @Override
    public List<VerificationIssue> evaluate(NormalizedInvoice invoice) {
        List<VerificationIssue> issues = new ArrayList<>();
        for (Map.Entry<String, NormalizedField> entry : invoice.fields().entrySet()) {
            if (!CRITICAL_FIELDS.contains(entry.getKey())) {
                continue;
            }
            NormalizedField field = entry.getValue();
            if (field.confidence() != null && field.confidence().compareTo(threshold) < 0) {
                issues.add(new VerificationIssue(
                        "LOW_CONFIDENCE",
                        entry.getKey(),
                        "field confidence is below threshold",
                        VerificationSeverity.REVIEW));
            }
        }
        return issues;
    }
}
