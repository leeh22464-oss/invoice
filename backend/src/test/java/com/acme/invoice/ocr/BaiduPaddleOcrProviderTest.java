package com.acme.invoice.ocr;

import com.acme.invoice.credential.PaddleCredential;
import com.acme.invoice.ingestion.StoredFile;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BaiduPaddleOcrProviderTest {

    private static final byte[] IMAGE_BYTES = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47};
    private static final PaddleCredential CREDENTIAL =
            new PaddleCredential("api-key-value", "secret-key-value");
    private static final StoredFile FILE =
            new StoredFile("11111111-1111-1111-1111-111111111111", 4, "abc", "image/png");

    private WireMockServer server;
    private BaiduPaddleTokenClient tokenClient;
    private BaiduPaddleOcrProvider provider;
    private final AtomicInteger sleepCalls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        server = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        server.start();
        WireMock.configureFor("localhost", server.port());
        sleepCalls.set(0);
        tokenClient = new BaiduPaddleTokenClient(
                WebClient.builder(),
                "http://localhost:" + server.port() + "/oauth/2.0/token",
                2_000);
        provider = new BaiduPaddleOcrProvider(
                tokenClient,
                WebClient.builder(),
                "http://localhost:" + server.port() + "/rest/2.0/ocr/v1/multiple_invoice",
                file -> IMAGE_BYTES,
                Duration.ofSeconds(2),
                ignored -> sleepCalls.incrementAndGet());
        stubTokenSuccess();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void recognizeMapsSuccessfulResponseWithoutLeakingSecrets() throws Exception {
        String body = """
                {"log_id":4242,"words_result_num":1,"words_result":[
                  {"type":"vat_invoice","probability":0.99,
                   "result":{"InvoiceCode":[{"word":"033001900111"}],
                             "InvoiceNum":[{"word":"00123456"}],
                             "InvoiceType":[{"word":"专用发票"}]}}
                ]}
                """;
        stubFor(post(urlPathEqualTo("/rest/2.0/ocr/v1/multiple_invoice"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));

        OcrDocument document = provider.recognize(CREDENTIAL, FILE);

        assertThat(document.providerRequestId()).isEqualTo("4242");
        assertThat(document.rawJson()).contains("033001900111");
        assertThat(document.fields())
                .anySatisfy(field -> {
                    assertThat(field.name()).isEqualTo("InvoiceCode");
                    assertThat(field.rawValue()).isEqualTo("033001900111");
                    assertThat(field.confidence()).isEqualByComparingTo(new BigDecimal("0.99"));
                });
        verify(1, postRequestedFor(urlPathEqualTo("/oauth/2.0/token")));
        verify(1, postRequestedFor(urlPathEqualTo("/rest/2.0/ocr/v1/multiple_invoice")));
        assertThat(document.rawJson()).doesNotContain("api-key-value", "secret-key-value");
    }

    @Test
    void retriesOnceAfter429ThenSucceeds() {
        stubFor(post(urlPathEqualTo("/rest/2.0/ocr/v1/multiple_invoice"))
                .inScenario("rate-limit")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429).withBody("rate-limited"))
                .willSetStateTo("ok"));
        stubFor(post(urlPathEqualTo("/rest/2.0/ocr/v1/multiple_invoice"))
                .inScenario("rate-limit")
                .whenScenarioStateIs("ok")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"log_id":7,"words_result_num":1,"words_result":[
                                  {"type":"quota_invoice","probability":0.9,
                                   "result":{"invoice_code":[{"word":"132081490320"}],
                                             "invoice_number":[{"word":"01275486"}]}}
                                ]}
                                """)));

        OcrDocument document = provider.recognize(CREDENTIAL, FILE);

        assertThat(document.providerRequestId()).isEqualTo("7");
        assertThat(document.fields())
                .anyMatch(field -> "invoice_number".equals(field.name())
                        && "01275486".equals(field.rawValue()));
        verify(2, postRequestedFor(urlPathEqualTo("/rest/2.0/ocr/v1/multiple_invoice")));
        assertThat(sleepCalls.get()).isEqualTo(1);
    }

    @Test
    void failsAfterThreeHttp500Attempts() {
        stubFor(post(urlPathEqualTo("/rest/2.0/ocr/v1/multiple_invoice"))
                .willReturn(aResponse().withStatus(500).withBody("upstream-500")));

        assertThatThrownBy(() -> provider.recognize(CREDENTIAL, FILE))
                .isInstanceOf(PaddleServiceUnavailableException.class)
                .hasMessageNotContaining("api-key-value")
                .hasMessageNotContaining("secret-key-value")
                .hasMessageNotContaining("upstream-500");
        verify(3, postRequestedFor(urlPathEqualTo("/rest/2.0/ocr/v1/multiple_invoice")));
        assertThat(sleepCalls.get()).isEqualTo(2);
    }

    @Test
    void doesNotRetryUnauthorizedCredentials() {
        stubFor(post(urlPathEqualTo("/rest/2.0/ocr/v1/multiple_invoice"))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withBody("{\"error\":\"invalid_token\"}")));

        assertThatThrownBy(() -> provider.recognize(CREDENTIAL, FILE))
                .isInstanceOf(OcrAuthenticationException.class)
                .hasMessageNotContaining("api-key-value")
                .hasMessageNotContaining("secret-key-value");
        verify(1, postRequestedFor(urlPathEqualTo("/rest/2.0/ocr/v1/multiple_invoice")));
        assertThat(sleepCalls.get()).isZero();
    }

    @Test
    void failsOnTimeoutWithoutRetryingBeyondPolicy() {
        provider = new BaiduPaddleOcrProvider(
                tokenClient,
                WebClient.builder(),
                "http://localhost:" + server.port() + "/rest/2.0/ocr/v1/multiple_invoice",
                file -> IMAGE_BYTES,
                Duration.ofMillis(200),
                ignored -> sleepCalls.incrementAndGet());
        stubFor(post(urlPathEqualTo("/rest/2.0/ocr/v1/multiple_invoice"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(500)
                        .withBody("{\"log_id\":1}")));

        assertThatThrownBy(() -> provider.recognize(CREDENTIAL, FILE))
                .isInstanceOf(PaddleServiceUnavailableException.class);
        verify(3, postRequestedFor(urlPathEqualTo("/rest/2.0/ocr/v1/multiple_invoice")));
    }

    private void stubTokenSuccess() {
        stubFor(post(urlPathEqualTo("/oauth/2.0/token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"token-abc\",\"expires_in\":2592000}")));
    }
}
