package com.acme.invoice.erp;

import com.acme.invoice.invoice.Invoice;
import com.acme.invoice.invoice.InvoiceRepository;
import com.acme.invoice.invoice.InvoiceStatus;
import com.acme.invoice.ocr.InvoiceType;
import com.acme.invoice.review.ReviewException;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HttpErpPushClientTest {

    private WireMockServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void blankUrlIsRejectedBeforeAnyCall() {
        HttpErpPushClient client = client("  ", "", mock(InvoiceRepository.class));

        assertThatThrownBy(() -> client.push(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(ReviewException.class)
                .hasFieldOrPropertyWithValue("code", "ERP_NOT_CONFIGURED");
    }

    @Test
    void postsThePassedInvoiceAndDoesNotSendTheTokenInTheBody() {
        server = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        server.start();
        com.github.tomakehurst.wiremock.client.WireMock.configureFor("localhost", server.port());
        stubFor(post(urlPathEqualTo("/erp")).willReturn(aResponse().withStatus(204)));

        UUID userId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = mock(Invoice.class);
        when(invoice.getId()).thenReturn(invoiceId);
        when(invoice.getInvoiceType()).thenReturn(InvoiceType.VAT_SPECIAL);
        when(invoice.getInvoiceCode()).thenReturn("033001900111");
        when(invoice.getInvoiceNumber()).thenReturn("00123456");
        when(invoice.getInvoiceDate()).thenReturn(LocalDate.of(2024, 1, 15));
        when(invoice.getAmount()).thenReturn(new BigDecimal("100.00"));
        when(invoice.getTax()).thenReturn(new BigDecimal("13.00"));
        when(invoice.getTotal()).thenReturn(new BigDecimal("113.00"));
        when(invoice.getStatus()).thenReturn(InvoiceStatus.PASSED);
        InvoiceRepository invoices = mock(InvoiceRepository.class);
        when(invoices.findByIdAndUserId(invoiceId, userId)).thenReturn(Optional.of(invoice));

        client("http://localhost:" + server.port() + "/erp", "erp-token-value", invoices)
                .push(userId, invoiceId);

        verify(postRequestedFor(urlPathEqualTo("/erp"))
                .withHeader("Authorization", equalTo("Bearer erp-token-value"))
                .withRequestBody(equalToJson("""
                        {
                          "invoiceId":"%s",
                          "invoiceType":"VAT_SPECIAL",
                          "invoiceCode":"033001900111",
                          "invoiceNumber":"00123456",
                          "invoiceDate":"2024-01-15",
                          "amount":100.00,
                          "tax":13.00,
                          "total":113.00
                        }
                        """.formatted(invoiceId), true, false)));
    }

    private static HttpErpPushClient client(String url, String token, InvoiceRepository invoices) {
        return new HttpErpPushClient(WebClient.builder(), invoices, url, token, 1000);
    }
}
