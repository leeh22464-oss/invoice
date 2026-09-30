package com.acme.invoice.deduplication;

import com.acme.invoice.ocr.NormalizedField;
import com.acme.invoice.ocr.NormalizedInvoice;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Component
public class SuspectedDuplicateScorer {

    private static final BigDecimal FIELD_WEIGHT = new BigDecimal("0.25");

    public DuplicateScore score(NormalizedInvoice left, NormalizedInvoice right) {
        List<String> matched = new ArrayList<>();
        if (left.type() == right.type()) {
            matched.add("invoiceType");
        }
        if (Objects.equals(left.invoiceDate(), right.invoiceDate()) && left.invoiceDate() != null) {
            matched.add("invoiceDate");
        }
        if (sameMoney(left.total(), right.total())) {
            matched.add("total");
        }
        if (sameMoney(left.amount(), right.amount())) {
            matched.add("amount");
        }
        matchSharedField(left, right, "PurchaserName", matched);
        matchSharedField(left, right, "SellerName", matched);
        matchSharedField(left, right, "station_geton", matched);
        matchSharedField(left, right, "station_getoff", matched);

        BigDecimal score = FIELD_WEIGHT.multiply(BigDecimal.valueOf(matched.size()))
                .setScale(2, RoundingMode.HALF_UP);
        return new DuplicateScore(score, List.copyOf(matched));
    }

    private static void matchSharedField(
            NormalizedInvoice left,
            NormalizedInvoice right,
            String name,
            List<String> matched) {
        String leftValue = text(left, name);
        String rightValue = text(right, name);
        if (leftValue != null && leftValue.equals(rightValue)) {
            matched.add(name);
        }
    }

    private static String text(NormalizedInvoice invoice, String name) {
        NormalizedField field = invoice.fields().get(name);
        if (field == null || field.sourceText() == null || field.sourceText().isBlank()) {
            return null;
        }
        return field.sourceText().trim();
    }

    private static boolean sameMoney(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return false;
        }
        return left.setScale(2, RoundingMode.HALF_UP)
                .subtract(right.setScale(2, RoundingMode.HALF_UP))
                .abs()
                .compareTo(new BigDecimal("0.01")) <= 0;
    }
}
