package com.acme.invoice.ocr;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Component
public class InvoiceNormalizer {

    private static final DateTimeFormatter CN_DATE = new DateTimeFormatterBuilder()
            .appendValue(ChronoField.YEAR, 4)
            .appendLiteral('年')
            .appendValue(ChronoField.MONTH_OF_YEAR, 1, 2, java.time.format.SignStyle.NOT_NEGATIVE)
            .appendLiteral('月')
            .appendValue(ChronoField.DAY_OF_MONTH, 1, 2, java.time.format.SignStyle.NOT_NEGATIVE)
            .appendLiteral('日')
            .toFormatter(Locale.ROOT);

    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    public NormalizedInvoice normalize(OcrDocument document) {
        Map<String, OcrField> byName = index(document);
        String providerType = text(byName, "type");
        InvoiceType type = resolveType(providerType, text(byName, "InvoiceType"));

        String code = firstNonBlank(byName,
                "InvoiceCode", "invoice_code");
        String number = firstNonBlank(byName,
                "InvoiceNum", "invoice_number", "invoice_num", "ticket_num");
        LocalDate date = parseDate(firstNonBlank(byName,
                "InvoiceDate", "invoice_date", "Date", "date", "start_date"));

        BigDecimal amount;
        BigDecimal tax;
        BigDecimal total;
        switch (type) {
            case RAILWAY -> {
                amount = decimal(byName, "fare");
                tax = decimal(byName, "tax");
                total = decimal(byName, "ticket_rates");
                if (number == null) {
                    number = text(byName, "invoice_num");
                }
            }
            case FLIGHT -> {
                amount = decimal(byName, "fare");
                tax = decimal(byName, "commodity_tax");
                total = decimal(byName, "ticket_rates");
                date = Optional.ofNullable(parseDate(text(byName, "start_date"))).orElse(date);
            }
            case TAXI -> {
                amount = decimal(byName, "Fare");
                tax = null;
                total = decimal(byName, "TotalFare");
            }
            case FIXED_AMOUNT -> {
                amount = null;
                tax = null;
                total = firstDecimal(byName, "invoice_rate_in_figure", "invoice_rate_lowercase");
            }
            default -> {
                amount = decimal(byName, "TotalAmount");
                tax = decimal(byName, "TotalTax");
                total = decimal(byName, "AmountInFiguers");
            }
        }

        Map<String, NormalizedField> fields = new LinkedHashMap<>();
        for (OcrField field : document.fields()) {
            fields.put(field.name(), new NormalizedField(field.rawValue(), field.confidence()));
        }
        return new NormalizedInvoice(type, blankToNull(code), blankToNull(number), date, amount, tax, total, Map.copyOf(fields));
    }

    private static InvoiceType resolveType(String providerType, String invoiceType) {
        if ("train_ticket".equals(providerType)) {
            return InvoiceType.RAILWAY;
        }
        if ("air_ticket".equals(providerType)) {
            return InvoiceType.FLIGHT;
        }
        if ("taxi_receipt".equals(providerType)) {
            return InvoiceType.TAXI;
        }
        if ("quota_invoice".equals(providerType)) {
            return InvoiceType.FIXED_AMOUNT;
        }
        if ("customs_payment".equals(providerType)) {
            return InvoiceType.CUSTOMS_PAYMENT;
        }
        if ("vat_invoice".equals(providerType) || providerType == null) {
            if (invoiceType != null) {
                if (invoiceType.contains("专用")) {
                    return InvoiceType.VAT_SPECIAL;
                }
                if (invoiceType.contains("电子")) {
                    return InvoiceType.ELECTRONIC;
                }
                if (invoiceType.contains("普通")) {
                    return InvoiceType.VAT_ORDINARY;
                }
            }
        }
        throw new IllegalArgumentException("Unsupported invoice type");
    }

    private static Map<String, OcrField> index(OcrDocument document) {
        Map<String, OcrField> byName = new LinkedHashMap<>();
        for (OcrField field : document.fields()) {
            byName.putIfAbsent(field.name(), field);
        }
        return byName;
    }

    private static String firstNonBlank(Map<String, OcrField> byName, String... names) {
        for (String name : names) {
            String value = text(byName, name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String text(Map<String, OcrField> byName, String name) {
        OcrField field = byName.get(name);
        return field == null ? null : field.rawValue();
    }

    private static BigDecimal firstDecimal(Map<String, OcrField> byName, String... names) {
        for (String name : names) {
            BigDecimal value = decimal(byName, name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static BigDecimal decimal(Map<String, OcrField> byName, String name) {
        String value = text(byName, name);
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.replace(",", "").replace("￥", "").trim();
        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        try {
            if (value.contains("年")) {
                return LocalDate.parse(value, CN_DATE);
            }
            if (value.length() == 8 && value.chars().allMatch(Character::isDigit)) {
                return LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE);
            }
            return LocalDate.parse(value, ISO_DATE);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
