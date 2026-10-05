package com.acme.invoice.ledger;

import com.acme.invoice.invoice.Invoice;
import com.acme.invoice.invoice.InvoiceRepository;
import com.acme.invoice.invoice.InvoiceStatus;
import com.acme.invoice.ocr.InvoiceType;
import com.acme.invoice.ocr.NormalizedField;
import com.acme.invoice.review.ReviewException;
import org.springframework.http.HttpStatus;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Profile("!smoke")
public class LedgerService {

    private final InvoiceRepository invoices;

    public LedgerService(InvoiceRepository invoices) {
        this.invoices = invoices;
    }

    @Transactional(readOnly = true)
    public List<Invoice> listPassed(UUID userId) {
        return listPassed(userId, LedgerFilter.empty());
    }

    @Transactional(readOnly = true)
    public List<Invoice> listPassed(UUID userId, LedgerFilter filter) {
        LedgerFilter safe = filter == null ? LedgerFilter.empty() : filter;
        InvoiceType type = parseType(safe.invoiceType());
        String number = blankToNull(safe.invoiceNumber());
        return invoices.findByUserIdAndStatusOrderByCreatedAtDesc(userId, InvoiceStatus.PASSED)
                .stream()
                .filter(invoice -> type == null || invoice.getInvoiceType() == type)
                .filter(invoice -> number == null || number.equals(invoice.getInvoiceNumber()))
                .filter(invoice -> safe.dateFrom() == null
                        || (invoice.getInvoiceDate() != null
                        && !invoice.getInvoiceDate().isBefore(safe.dateFrom())))
                .filter(invoice -> safe.dateTo() == null
                        || (invoice.getInvoiceDate() != null
                        && !invoice.getInvoiceDate().isAfter(safe.dateTo())))
                .toList();
    }

    @Transactional(readOnly = true)
    public byte[] exportCsv(UUID userId) {
        return exportCsv(userId, LedgerFilter.empty());
    }

    @Transactional(readOnly = true)
    public byte[] exportCsv(UUID userId, LedgerFilter filter) {
        List<Invoice> rows = listPassed(userId, filter);
        StringBuilder csv = new StringBuilder();
        csv.append("id,invoiceCode,invoiceNumber,invoiceDate,amount,tax,total,purchaser,seller\n");
        for (Invoice invoice : rows) {
            csv.append(csvCell(invoice.getId().toString())).append(',')
                    .append(csvCell(sanitize(invoice.getInvoiceCode()))).append(',')
                    .append(csvCell(sanitize(invoice.getInvoiceNumber()))).append(',')
                    .append(csvCell(sanitize(invoice.getInvoiceDate() == null
                            ? ""
                            : invoice.getInvoiceDate().toString()))).append(',')
                    .append(csvCell(sanitize(plain(invoice.getAmount())))).append(',')
                    .append(csvCell(sanitize(plain(invoice.getTax())))).append(',')
                    .append(csvCell(sanitize(plain(invoice.getTotal())))).append(',')
                    .append(csvCell(sanitize(fieldText(invoice, "PurchaserName")))).append(',')
                    .append(csvCell(sanitize(fieldText(invoice, "SellerName"))))
                    .append('\n');
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Transactional(readOnly = true)
    public byte[] exportXlsx(UUID userId) {
        return exportXlsx(userId, LedgerFilter.empty());
    }

    @Transactional(readOnly = true)
    public byte[] exportXlsx(UUID userId, LedgerFilter filter) {
        List<Invoice> rows = listPassed(userId, filter);
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("ledger");
            Row header = sheet.createRow(0);
            String[] columns = {
                    "id", "invoiceCode", "invoiceNumber", "invoiceDate",
                    "amount", "tax", "total", "purchaser", "seller"};
            for (int i = 0; i < columns.length; i++) {
                header.createCell(i).setCellValue(columns[i]);
            }
            int rowIndex = 1;
            for (Invoice invoice : rows) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(invoice.getId().toString());
                row.createCell(1).setCellValue(sanitize(nullToEmpty(invoice.getInvoiceCode())));
                row.createCell(2).setCellValue(sanitize(nullToEmpty(invoice.getInvoiceNumber())));
                row.createCell(3).setCellValue(sanitize(invoice.getInvoiceDate() == null
                        ? ""
                        : invoice.getInvoiceDate().toString()));
                row.createCell(4).setCellValue(sanitize(plain(invoice.getAmount())));
                row.createCell(5).setCellValue(sanitize(plain(invoice.getTax())));
                row.createCell(6).setCellValue(sanitize(plain(invoice.getTotal())));
                row.createCell(7).setCellValue(sanitize(fieldText(invoice, "PurchaserName")));
                row.createCell(8).setCellValue(sanitize(fieldText(invoice, "SellerName")));
            }
            workbook.write(output);
            workbook.dispose();
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to export ledger workbook", exception);
        }
    }

    private static InvoiceType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return InvoiceType.valueOf(raw.trim());
        } catch (IllegalArgumentException exception) {
            throw new ReviewException(
                    "INVALID_INVOICE_TYPE",
                    "invoiceType is not recognized",
                    HttpStatus.BAD_REQUEST);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return value == null ? "" : value;
        }
        char first = value.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@') {
            return "'" + value;
        }
        return value;
    }

    private static String fieldText(Invoice invoice, String name) {
        Map<String, NormalizedField> fields = invoice.getFields();
        if (fields == null) {
            return "";
        }
        NormalizedField field = fields.get(name);
        return field == null || field.sourceText() == null ? "" : field.sourceText();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String plain(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }

    private static String csvCell(String value) {
        String safe = value == null ? "" : value;
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }
}
