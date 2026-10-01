package com.acme.invoice.erp;

import com.acme.invoice.identity.CurrentUser;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/erp")
@Profile("!smoke")
public class ErpController {

    private final ErpService service;
    private final CurrentUser currentUser;

    public ErpController(ErpService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping("/invoices")
    List<ErpInvoiceView> list() {
        return service.list(currentUser.requireId());
    }

    @PostMapping(value = "/invoices/push", consumes = MediaType.APPLICATION_JSON_VALUE)
    ErpPushResponse push(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody ErpPushRequest request) {
        return service.push(currentUser.requireId(), idempotencyKey, request);
    }
}
