package com.acme.invoice.erp;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.UUID;

public interface ErpPushClient {
    void push(UUID userId, UUID invoiceId);
}

@Component
@Profile("!smoke")
class NoOpErpPushClient implements ErpPushClient {
    @Override
    public void push(UUID userId, UUID invoiceId) {
        // Side effect boundary for external ERP systems.
    }
}
