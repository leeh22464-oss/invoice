package com.acme.invoice.verification;

import com.acme.invoice.ocr.NormalizedInvoice;

import java.util.List;

public interface VerificationRule {
    List<VerificationIssue> evaluate(NormalizedInvoice invoice);
}
