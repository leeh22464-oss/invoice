package com.acme.invoice.deduplication;

import com.acme.invoice.ocr.InvoiceType;
import com.acme.invoice.ocr.NormalizedField;
import com.acme.invoice.ocr.NormalizedInvoice;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class ExactDuplicateKeyFactory {

    public Optional<String> create(NormalizedInvoice invoice) {
        if (invoice == null || invoice.type() == null) {
            return Optional.empty();
        }
        return switch (invoice.type()) {
            case VAT_SPECIAL, VAT_ORDINARY -> vatKey(invoice);
            case ELECTRONIC -> electronicKey(invoice);
            case RAILWAY -> railwayKey(invoice);
            case FLIGHT -> flightKey(invoice);
            default -> Optional.empty();
        };
    }

    private static Optional<String> vatKey(NormalizedInvoice invoice) {
        if (blank(invoice.invoiceCode()) || blank(invoice.invoiceNumber())) {
            return Optional.empty();
        }
        return Optional.of(invoice.type().name()
                + "|" + invoice.invoiceCode()
                + "|" + invoice.invoiceNumber());
    }

    private static Optional<String> electronicKey(NormalizedInvoice invoice) {
        if (blank(invoice.invoiceNumber())) {
            return Optional.empty();
        }
        if (!blank(invoice.invoiceCode())) {
            return Optional.of(invoice.type().name()
                    + "|" + invoice.invoiceCode()
                    + "|" + invoice.invoiceNumber());
        }
        return Optional.of(invoice.type().name() + "|" + invoice.invoiceNumber());
    }

    private static Optional<String> railwayKey(NormalizedInvoice invoice) {
        String ticket = fieldText(invoice, "ticket_num");
        if (blank(ticket)) {
            return Optional.empty();
        }
        return Optional.of(InvoiceType.RAILWAY.name() + "|" + ticket);
    }

    private static Optional<String> flightKey(NormalizedInvoice invoice) {
        String ticket = fieldText(invoice, "ticket_number");
        if (blank(ticket)) {
            return Optional.empty();
        }
        return Optional.of(InvoiceType.FLIGHT.name() + "|" + ticket);
    }

    private static String fieldText(NormalizedInvoice invoice, String name) {
        NormalizedField field = invoice.fields().get(name);
        return field == null ? null : field.sourceText();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
