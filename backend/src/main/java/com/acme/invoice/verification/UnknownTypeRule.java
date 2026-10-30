package com.acme.invoice.verification;

import com.acme.invoice.ocr.InvoiceType;
import com.acme.invoice.ocr.NormalizedInvoice;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class UnknownTypeRule implements VerificationRule {

    @Override
    public List<VerificationIssue> evaluate(NormalizedInvoice invoice) {
        if (invoice.type() == InvoiceType.UNKNOWN) {
            return List.of(new VerificationIssue(
                    "UNKNOWN_TYPE",
                    "invoiceType",
                    "未能识别发票类型，需要人工复核",
                    VerificationSeverity.REVIEW));
        }
        return List.of();
    }
}
