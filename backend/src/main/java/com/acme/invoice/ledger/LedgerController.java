package com.acme.invoice.ledger;

import com.acme.invoice.identity.CurrentUser;
import com.acme.invoice.review.InvoiceView;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/ledger")
@Profile("!smoke")
public class LedgerController {

    private final LedgerService service;
    private final CurrentUser currentUser;

    public LedgerController(LedgerService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    List<InvoiceView> list(
            @RequestParam(required = false) String invoiceType,
            @RequestParam(required = false) String invoiceNumber,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        return service.listPassed(currentUser.requireId(), filter(invoiceType, invoiceNumber, dateFrom, dateTo))
                .stream()
                .map(InvoiceView::from)
                .toList();
    }

    @GetMapping(value = "/export.csv", produces = "text/csv")
    ResponseEntity<byte[]> exportCsv(
            @RequestParam(required = false) String invoiceType,
            @RequestParam(required = false) String invoiceNumber,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        byte[] body = service.exportCsv(
                currentUser.requireId(),
                filter(invoiceType, invoiceNumber, dateFrom, dateTo));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ledger.csv\"")
                .contentType(new MediaType("text", "csv"))
                .body(body);
    }

    @GetMapping(value = "/export.xlsx")
    ResponseEntity<byte[]> exportXlsx(
            @RequestParam(required = false) String invoiceType,
            @RequestParam(required = false) String invoiceNumber,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        byte[] body = service.exportXlsx(
                currentUser.requireId(),
                filter(invoiceType, invoiceNumber, dateFrom, dateTo));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ledger.xlsx\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }

    private static LedgerFilter filter(
            String invoiceType,
            String invoiceNumber,
            LocalDate dateFrom,
            LocalDate dateTo) {
        return new LedgerFilter(invoiceType, invoiceNumber, dateFrom, dateTo);
    }
}
