package com.acme.invoice.verification;

import com.acme.invoice.ocr.InvoiceType;
import com.acme.invoice.ocr.NormalizedField;
import com.acme.invoice.ocr.NormalizedInvoice;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class RequiredFieldRule implements VerificationRule {

    @Override
    public List<VerificationIssue> evaluate(NormalizedInvoice invoice) {
        List<VerificationIssue> issues = new ArrayList<>();
        InvoiceType type = invoice.type();
        if (type == InvoiceType.VAT_SPECIAL
                || type == InvoiceType.VAT_ORDINARY
                || type == InvoiceType.ELECTRONIC) {
            require(issues, "invoiceCode", invoice.invoiceCode(), type != InvoiceType.ELECTRONIC);
            require(issues, "invoiceNumber", invoice.invoiceNumber(), true);
            requireDate(issues, invoice);
            requireTotal(issues, invoice);
        } else if (type == InvoiceType.RAILWAY) {
            require(issues, "ticket_num", fieldText(invoice, "ticket_num"), true);
            requireDate(issues, invoice);
            requireTotal(issues, invoice);
        } else if (type == InvoiceType.FLIGHT) {
            require(issues, "ticket_number", fieldText(invoice, "ticket_number"), true);
            requireDate(issues, invoice);
            requireTotal(issues, invoice);
        } else if (type != InvoiceType.UNKNOWN) {
            requireDate(issues, invoice);
            requireTotal(issues, invoice);
        }
        return issues;
    }

    private static void require(
            List<VerificationIssue> issues,
            String field,
            String value,
            boolean required) {
        if (required && (value == null || value.isBlank())) {
            issues.add(new VerificationIssue(
                    "MISSING_REQUIRED_FIELD",
                    field,
                    field + " is required",
                    VerificationSeverity.REVIEW));
        }
    }

    private static void requireDate(List<VerificationIssue> issues, NormalizedInvoice invoice) {
        if (invoice.invoiceDate() == null) {
            issues.add(new VerificationIssue(
                    "MISSING_REQUIRED_FIELD",
                    "invoiceDate",
                    "invoiceDate is required",
                    VerificationSeverity.REVIEW));
        }
    }

    private static void requireTotal(List<VerificationIssue> issues, NormalizedInvoice invoice) {
        if (invoice.total() == null) {
            issues.add(new VerificationIssue(
                    "MISSING_REQUIRED_FIELD",
                    "total",
                    "total is required",
                    VerificationSeverity.REVIEW));
        }
    }

    private static String fieldText(NormalizedInvoice invoice, String name) {
        if (invoice.fields() == null) {
            return null;
        }
        NormalizedField field = invoice.fields().get(name);
        return field == null ? null : field.sourceText();
    }
}
