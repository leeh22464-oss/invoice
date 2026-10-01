package com.acme.invoice.ingestion;

import com.acme.invoice.identity.CurrentUser;
import org.springframework.core.io.InputStreamResource;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/import-batches")
@Profile("!smoke")
public class ImportController {

    private final ImportService service;
    private final CurrentUser currentUser;
    private final ExecutorService sseExecutor = Executors.newCachedThreadPool();

    public ImportController(ImportService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<ImportBatchView> create(
            @RequestPart("files") List<MultipartFile> files) {
        ImportBatchView view = service.create(currentUser.requireId(), files);
        return ResponseEntity.status(201).body(view);
    }

    @GetMapping("/{id}")
    ImportBatchView get(@PathVariable UUID id) {
        return service.get(currentUser.requireId(), id);
    }

    @GetMapping(path = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events(@PathVariable UUID id) {
        UUID userId = currentUser.requireId();
        BatchProgressEvent progress = service.progress(userId, id);
        SseEmitter emitter = new SseEmitter(30_000L);
        sseExecutor.execute(() -> {
            try {
                emitter.send(SseEmitter.event()
                        .name("progress")
                        .data(progress));
                emitter.complete();
            } catch (IOException | RuntimeException exception) {
                emitter.completeWithError(exception);
            }
        });
        return emitter;
    }

    @GetMapping("/{batchId}/files/{fileId}/content")
    ResponseEntity<InputStreamResource> content(
            @PathVariable UUID batchId,
            @PathVariable UUID fileId) {
        PreviewFile preview = service.open(
                currentUser.requireId(), batchId, fileId);
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
