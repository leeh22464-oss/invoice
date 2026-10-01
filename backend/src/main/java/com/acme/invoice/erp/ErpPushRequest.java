package com.acme.invoice.erp;

import java.util.UUID;

public record ErpPushRequest(UUID invoiceId) {
}
