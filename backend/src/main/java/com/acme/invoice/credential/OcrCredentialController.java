package com.acme.invoice.credential;

import com.acme.invoice.identity.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings/ocr-credentials")
@Profile("!smoke")
public class OcrCredentialController {

    private final OcrCredentialService service;
    private final CurrentUser currentUser;

    public OcrCredentialController(OcrCredentialService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    CredentialStatusResponse status() {
        return service.status(currentUser.requireId());
    }

    @PutMapping
    CredentialStatusResponse save(@Valid @RequestBody CredentialRequest request) {
        return service.save(currentUser.requireId(), request);
    }

    @PostMapping("/test")
    CredentialStatusResponse test() {
        return service.test(currentUser.requireId());
    }

    @DeleteMapping
    ResponseEntity<Void> delete() {
        service.delete(currentUser.requireId());
        return ResponseEntity.noContent().build();
    }
}
