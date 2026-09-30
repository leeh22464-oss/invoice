package com.acme.invoice.verification;

import com.acme.invoice.ocr.InvoiceType;
import com.acme.invoice.ocr.NormalizedInvoice;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class FormatRule implements VerificationRule {

    @Override
    public List<VerificationIssue> evaluate(NormalizedInvoice invoice) {
        List<VerificationIssue> issues = new ArrayList<>();
        InvoiceType type = invoice.type();
        if (type == InvoiceType.VAT_SPECIAL || type == InvoiceType.VAT_ORDINARY) {
            if (invoice.invoiceCode() != null && !invoice.invoiceCode().matches("\\d{10,12}")) {
                issues.add(new VerificationIssue(
                        "INVALID_FORMAT",
                        "invoiceCode",
                        "invoice code format is invalid",
                        VerificationSeverity.REVIEW));
            }
            if (invoice.invoiceNumber() != null && !invoice.invoiceNumber().matches("\\d{8}")) {
                issues.add(new VerificationIssue(
                        "INVALID_FORMAT",
                        "invoiceNumber",
                        "invoice number format is invalid",
                        VerificationSeverity.REVIEW));
            }
        }
        return issues;
    }
}
