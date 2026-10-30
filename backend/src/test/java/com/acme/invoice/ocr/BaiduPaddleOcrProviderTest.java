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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BaiduPaddleOcrProviderTest {

    private static final byte[] IMAGE_BYTES = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47};
    private static final StoredFile FILE =
            new StoredFile("11111111-1111-1111-1111-111111111111", 4, "abc", "image/png");

    private WireMockServer server;
    private PaddleCredential credential;
    private BaiduPaddleOcrProvider provider;
    private final AtomicInteger sleepCalls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        server = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        server.start();
        WireMock.configureFor("localhost", server.port());
        sleepCalls.set(0);
        credential = new PaddleCredential(
                "http://localhost:" + server.port() + "/llm/lmapi/v3",
                "secret-key-value");
        provider = provider(Duration.ofSeconds(2));
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void recognizeMapsPaddleOcrV5ResponseWithoutLeakingSecrets() {
        stubFor(post(urlPathEqualTo("/llm/lmapi/v3/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer secret-key-value"))
                .withRequestBody(containing("\"model\":\"ernie-4.5-turbo-vl\""))
                .withRequestBody(containing("data:image/png;base64,"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(chat("4242", "增值税专用发票\n发票代码：033001900111\n发票号码：00123456"))));

        OcrDocument document = provider.recognize(credential, FILE);

        assertThat(document.providerRequestId()).isEqualTo("4242");
        assertThat(document.fields())
                .anySatisfy(field -> {
                    assertThat(field.name()).isEqualTo("InvoiceCode");
                    assertThat(field.rawValue()).isEqualTo("033001900111");
                });
        assertThat(document.fields())
                .anySatisfy(field -> {
                    assertThat(field.name()).isEqualTo("InvoiceNum");
                    assertThat(field.rawValue()).isEqualTo("00123456");
                });
        assertThat(document.rawJson()).doesNotContain("secret-key-value");
        verify(1, postRequestedFor(urlPathEqualTo("/llm/lmapi/v3/chat/completions")));
    }

    @Test
    void rejectsATokenPageOrEmptyBodyInsteadOfSavingBlankRecognition() {
        stubFor(post(urlPathEqualTo("/llm/lmapi/v3/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody("<html><title>accessToken</title></html>")));

        assertThatThrownBy(() -> provider.recognize(credential, FILE))
                .isInstanceOf(OcrEndpointException.class)
                .hasMessageContaining("llm/lmapi/v3");
        verify(1, postRequestedFor(urlPathEqualTo("/llm/lmapi/v3/chat/completions")));

        assertThatThrownBy(() -> provider.parseResponse("{}"))
                .isInstanceOf(OcrEndpointException.class);
    }

    @Test
    void parseResponseReadsGluedLabelsAndUsesTheValueLineConfidence() {
        OcrDocument document = provider.parseResponse("""
                {"logId":"9","result":{"ocrResults":[{"prunedResult":{
                  "rec_texts":["增值税专用发票","发票代码033001900111","发票号码00123456","开票日期：2024年01月15日","合计金额¥100.00","合计税额¥13.00","价税合计（小写）¥113.00"],
                  "rec_scores":[0.40,0.91,0.92,0.93,0.94,0.95,0.96]
                }}]}}
                """);

        assertThat(document.fields())
                .anySatisfy(field -> {
                    assertThat(field.name()).isEqualTo("InvoiceCode");
                    assertThat(field.rawValue()).isEqualTo("033001900111");
                    assertThat(field.confidence()).isEqualByComparingTo("0.91");
                });
        assertThat(document.fields())
                .anySatisfy(field -> {
                    assertThat(field.name()).isEqualTo("InvoiceNum");
                    assertThat(field.rawValue()).isEqualTo("00123456");
                    assertThat(field.confidence()).isEqualByComparingTo("0.92");
                });
        assertThat(document.fields())
                .anySatisfy(field -> {
                    assertThat(field.name()).isEqualTo("AmountInFiguers");
                    assertThat(field.rawValue()).isEqualTo("113.00");
                    assertThat(field.confidence()).isEqualByComparingTo("0.96");
                });
        assertThat(document.fields())
                .anySatisfy(field -> {
                    assertThat(field.name()).isEqualTo("TotalAmount");
                    assertThat(field.rawValue()).isEqualTo("100.00");
                });
        assertThat(document.fields())
                .anySatisfy(field -> {
                    assertThat(field.name()).isEqualTo("TotalTax");
                    assertThat(field.rawValue()).isEqualTo("13.00");
                });
    }

    @Test
    void parseResponseStillReadsBaiduMultipleInvoicePayload() {
        OcrDocument document = provider.parseResponse("""
                {"log_id":4242,"words_result_num":1,"words_result":[
                  {"type":"vat_invoice","probability":0.99,
                   "result":{"InvoiceCode":[{"word":"033001900111"}],
                             "InvoiceNum":[{"word":"00123456"}],
                             "InvoiceType":[{"word":"专用发票"}]}}
                ]}
                """);

        assertThat(document.providerRequestId()).isEqualTo("4242");
        assertThat(document.fields())
                .anySatisfy(field -> {
                    assertThat(field.name()).isEqualTo("InvoiceCode");
                    assertThat(field.rawValue()).isEqualTo("033001900111");
                    assertThat(field.confidence()).isEqualByComparingTo(new BigDecimal("0.99"));
                });
    }

    @Test
    void retriesOnceAfter429ThenSucceeds() {
        stubFor(post(urlPathEqualTo("/llm/lmapi/v3/chat/completions"))
                .inScenario("rate-limit")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429).withBody("rate-limited"))
                .willSetStateTo("ok"));
        stubFor(post(urlPathEqualTo("/llm/lmapi/v3/chat/completions"))
                .inScenario("rate-limit")
                .whenScenarioStateIs("ok")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(chat("7", "定额发票\n发票号码：01275486\n金额：20.00"))));

        OcrDocument document = provider.recognize(credential, FILE);

        assertThat(document.providerRequestId()).isEqualTo("7");
        assertThat(document.fields())
                .anyMatch(field -> "invoice_number".equals(field.name())
                        && "01275486".equals(field.rawValue()));
        verify(2, postRequestedFor(urlPathEqualTo("/llm/lmapi/v3/chat/completions")));
        assertThat(sleepCalls.get()).isEqualTo(1);
    }

    @Test
    void failsAfterThreeHttp500Attempts() {
        stubFor(post(urlPathEqualTo("/llm/lmapi/v3/chat/completions"))
                .willReturn(aResponse().withStatus(500).withBody("upstream-500")));

        assertThatThrownBy(() -> provider.recognize(credential, FILE))
                .isInstanceOf(PaddleServiceUnavailableException.class)
                .hasMessageNotContaining("secret-key-value")
                .hasMessageNotContaining("upstream-500");
        verify(3, postRequestedFor(urlPathEqualTo("/llm/lmapi/v3/chat/completions")));
        assertThat(sleepCalls.get()).isEqualTo(2);
    }

    @Test
    void doesNotRetryUnauthorizedCredentials() {
        stubFor(post(urlPathEqualTo("/llm/lmapi/v3/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withBody("{\"errorCode\":401}")));

        assertThatThrownBy(() -> provider.recognize(credential, FILE))
                .isInstanceOf(OcrAuthenticationException.class)
                .hasMessageNotContaining("secret-key-value");
        verify(1, postRequestedFor(urlPathEqualTo("/llm/lmapi/v3/chat/completions")));
        assertThat(sleepCalls.get()).isZero();
    }

    @Test
    void failsOnTimeoutWithoutRetryingBeyondPolicy() {
        provider = provider(Duration.ofMillis(200));
        stubFor(post(urlPathEqualTo("/llm/lmapi/v3/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(500)
                        .withBody("{\"logId\":\"1\",\"errorCode\":0,\"result\":{\"ocrResults\":[]}}")));

        assertThatThrownBy(() -> provider.recognize(credential, FILE))
                .isInstanceOf(PaddleServiceUnavailableException.class);
        verify(3, postRequestedFor(urlPathEqualTo("/llm/lmapi/v3/chat/completions")));
    }

    private BaiduPaddleOcrProvider provider(Duration timeout) {
        return new BaiduPaddleOcrProvider(
                WebClient.builder(),
                file -> IMAGE_BYTES,
                timeout,
                ignored -> sleepCalls.incrementAndGet());
    }

    private static String chat(String id, String content) {
        String escaped = content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return "{\"id\":\"" + id + "\",\"choices\":[{\"message\":{\"content\":\"" + escaped + "\"}}]}";
    }
}
