package com.acme.invoice.erp;

import java.util.UUID;

public interface ErpPushClient {
    void push(UUID userId, UUID invoiceId);
}
