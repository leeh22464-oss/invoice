package com.acme.invoice.erp;

import com.acme.invoice.invoice.Invoice;
import com.acme.invoice.invoice.InvoiceRepository;
import com.acme.invoice.review.ReviewException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

@Component
@Profile("!smoke")
class HttpErpPushClient implements ErpPushClient {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final WebClient webClient;
    private final InvoiceRepository invoices;
    private final String pushUrl;
    private final String pushToken;
    private final Duration timeout;

    HttpErpPushClient(
            WebClient.Builder builder,
            InvoiceRepository invoices,
            @Value("${app.erp.push-url:}") String pushUrl,
            @Value("${app.erp.push-token:}") String pushToken,
            @Value("${app.erp.timeout-ms:10000}") long timeoutMs) {
        this.webClient = builder
                .codecs(configurer -> {
                    configurer.defaultCodecs().jackson2JsonEncoder(
                            new Jackson2JsonEncoder(MAPPER, MediaType.APPLICATION_JSON));
                    configurer.defaultCodecs().jackson2JsonDecoder(
                            new Jackson2JsonDecoder(MAPPER, MediaType.APPLICATION_JSON));
                })
                .build();
        this.invoices = invoices;
        this.pushUrl = pushUrl == null ? "" : pushUrl.trim();
        this.pushToken = pushToken == null ? "" : pushToken.trim();
        this.timeout = Duration.ofMillis(Math.max(1L, timeoutMs));
    }

    @Override
    public void push(UUID userId, UUID invoiceId) {
        if (pushUrl.isBlank()) {
            throw new ReviewException(
                    "ERP_NOT_CONFIGURED",
                    "尚未配置 ERP 推送地址",
                    HttpStatus.CONFLICT);
        }
        URI endpoint = endpoint();
        Invoice invoice = invoices.findByIdAndUserId(invoiceId, userId)
                .orElseThrow(() -> new ReviewException(
                        "INVOICE_NOT_FOUND",
                        "Invoice not found",
                        HttpStatus.NOT_FOUND));
        try {
            webClient.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        if (!pushToken.isBlank()) {
                            headers.setBearerAuth(pushToken);
                        }
                    })
                    .bodyValue(new Payload(
                            invoice.getId(),
                            invoice.getInvoiceType().name(),
                            invoice.getInvoiceCode(),
                            invoice.getInvoiceNumber(),
                            invoice.getInvoiceDate(),
                            invoice.getAmount(),
                            invoice.getTax(),
                            invoice.getTotal()))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, response -> response.releaseBody()
                            .then(Mono.error(new ReviewException(
                                    "ERP_PUSH_FAILED",
                                    "ERP 推送失败",
                                    HttpStatus.BAD_GATEWAY))))
                    .toBodilessEntity()
                    .block(timeout);
        } catch (ReviewException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ReviewException(
                    "ERP_PUSH_FAILED",
                    "ERP 推送失败",
                    HttpStatus.BAD_GATEWAY);
        }
    }

    private URI endpoint() {
        URI uri;
        try {
            uri = URI.create(pushUrl);
        } catch (IllegalArgumentException exception) {
            throw invalidUrl();
        }
        String scheme = uri.getScheme();
        if (uri.getHost() == null || scheme == null
                || !(scheme.equals("http") || scheme.equals("https"))) {
            throw invalidUrl();
        }
        return uri;
    }

    private static ReviewException invalidUrl() {
        return new ReviewException(
                "ERP_PUSH_URL_INVALID",
                "ERP 推送地址必须是 http 或 https",
                HttpStatus.BAD_REQUEST);
    }

    private record Payload(
            UUID invoiceId,
            String invoiceType,
            String invoiceCode,
            String invoiceNumber,
            LocalDate invoiceDate,
            BigDecimal amount,
            BigDecimal tax,
            BigDecimal total) {
    }
}
