package com.acme.invoice.erp;

import com.acme.invoice.invoice.Invoice;
import com.acme.invoice.invoice.InvoiceRepository;
import com.acme.invoice.invoice.InvoiceStatus;
import com.acme.invoice.review.ReviewException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Service
@Profile("!smoke")
public class ErpService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final InvoiceRepository invoices;
    private final ErpPushReceiptRepository receipts;
    private final ErpPushClient pushClient;
    private final Clock clock;

    public ErpService(
            InvoiceRepository invoices,
            ErpPushReceiptRepository receipts,
            ErpPushClient pushClient,
            Clock clock) {
        this.invoices = invoices;
        this.receipts = receipts;
        this.pushClient = pushClient;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ErpInvoiceView> list(UUID userId) {
        return invoices.findByUserIdAndStatusOrderByCreatedAtDesc(userId, InvoiceStatus.PASSED)
                .stream()
                .map(invoice -> new ErpInvoiceView(
                        invoice.getId(),
                        invoice.getInvoiceCode(),
                        invoice.getInvoiceNumber(),
                        invoice.getStatus().name()))
                .toList();
    }

    @Transactional
    public ErpPushResponse push(UUID userId, String idempotencyKey, ErpPushRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ReviewException(
                    "IDEMPOTENCY_KEY_REQUIRED",
                    "Idempotency-Key header is required",
                    HttpStatus.BAD_REQUEST);
        }
        return receipts.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
                .map(this::toResponse)
                .orElseGet(() -> createPush(userId, idempotencyKey, request));
    }

    private ErpPushResponse createPush(UUID userId, String idempotencyKey, ErpPushRequest request) {
        if (request == null || request.invoiceId() == null) {
            throw new ReviewException(
                    "INVOICE_REQUIRED",
                    "invoiceId is required",
                    HttpStatus.BAD_REQUEST);
        }
        Invoice invoice = invoices.findByIdAndUserId(request.invoiceId(), userId)
                .orElseThrow(() -> new ReviewException(
                        "INVOICE_NOT_FOUND",
                        "Invoice not found",
                        HttpStatus.NOT_FOUND));
        if (invoice.getStatus() != InvoiceStatus.PASSED) {
            throw new ReviewException(
                    "INVOICE_NOT_PASSED",
                    "Only PASSED invoices can be pushed to ERP",
                    HttpStatus.CONFLICT);
        }

        pushClient.push(userId, invoice.getId());
        ErpPushResponse response = new ErpPushResponse(invoice.getId(), "PUSHED");
        try {
            receipts.saveAndFlush(new ErpPushReceipt(
                    UUID.randomUUID(),
                    userId,
                    invoice.getId(),
                    idempotencyKey,
                    MAPPER.writeValueAsString(response),
                    clock.instant()));
        } catch (DataIntegrityViolationException conflict) {
            return receipts.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
                    .map(this::toResponse)
                    .orElseThrow(() -> conflict);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize ERP push response", exception);
        }
        return response;
    }

    private ErpPushResponse toResponse(ErpPushReceipt receipt) {
        try {
            return MAPPER.readValue(receipt.getResponseJson(), ErpPushResponse.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to parse ERP push receipt", exception);
        }
    }
}
