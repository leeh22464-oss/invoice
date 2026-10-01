package com.acme.invoice.ledger;

import com.acme.invoice.identity.CurrentUser;
import com.acme.invoice.review.InvoiceView;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    List<InvoiceView> list() {
        return service.listPassed(currentUser.requireId()).stream()
                .map(InvoiceView::from)
                .toList();
    }

    @GetMapping(value = "/export.csv", produces = "text/csv")
    ResponseEntity<byte[]> exportCsv() {
        byte[] body = service.exportCsv(currentUser.requireId());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ledger.csv\"")
                .contentType(new MediaType("text", "csv"))
                .body(body);
    }

    @GetMapping(value = "/export.xlsx")
    ResponseEntity<byte[]> exportXlsx() {
        byte[] body = service.exportXlsx(currentUser.requireId());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ledger.xlsx\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }
}
