package com.acme.invoice.review;

import com.acme.invoice.identity.CurrentUser;
import com.acme.invoice.ingestion.PreviewFile;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/invoices")
@Profile("!smoke")
public class InvoiceReviewController {

    private final InvoiceReviewService service;
    private final CurrentUser currentUser;

    public InvoiceReviewController(InvoiceReviewService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    List<InvoiceView> list() {
        return service.list(currentUser.requireId());
    }

    @GetMapping("/{id}")
    InvoiceView get(@PathVariable UUID id) {
        return service.get(currentUser.requireId(), id);
    }

    @PatchMapping("/{id}")
    InvoiceView correct(@PathVariable UUID id, @RequestBody InvoiceCorrectionRequest request) {
        return service.correct(currentUser.requireId(), id, request);
    }

    @PostMapping("/{id}/retry")
    InvoiceView retry(@PathVariable UUID id) {
        return service.retry(currentUser.requireId(), id);
    }

    @GetMapping("/{id}/file")
    ResponseEntity<InputStreamResource> preview(@PathVariable UUID id) {
        PreviewFile preview = service.preview(currentUser.requireId(), id);
        ContentDisposition disposition = ContentDisposition.inline()
                .filename(preview.displayName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(preview.mediaType()))
                .contentLength(preview.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(preview.content()));
    }
}
