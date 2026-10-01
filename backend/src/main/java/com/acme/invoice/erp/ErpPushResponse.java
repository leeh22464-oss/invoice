package com.acme.invoice.erp;

import java.util.UUID;

public record ErpPushResponse(UUID invoiceId, String status) {
}
