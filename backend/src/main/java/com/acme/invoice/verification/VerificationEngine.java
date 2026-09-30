package com.acme.invoice.verification;

import com.acme.invoice.invoice.InvoiceStatus;
import com.acme.invoice.ocr.NormalizedInvoice;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class VerificationEngine {

    private final List<VerificationRule> rules;

    public VerificationEngine(List<VerificationRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public List<VerificationIssue> verify(NormalizedInvoice invoice) {
        List<VerificationIssue> issues = new ArrayList<>();
        for (VerificationRule rule : rules) {
            issues.addAll(rule.evaluate(invoice));
        }
        return List.copyOf(issues);
    }

    public InvoiceStatus statusFor(List<VerificationIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return InvoiceStatus.PASSED;
        }
        boolean hasFail = issues.stream().anyMatch(issue -> issue.severity() == VerificationSeverity.FAIL);
        if (hasFail) {
            return InvoiceStatus.FAILED;
        }
        return InvoiceStatus.REVIEW_REQUIRED;
    }
}
