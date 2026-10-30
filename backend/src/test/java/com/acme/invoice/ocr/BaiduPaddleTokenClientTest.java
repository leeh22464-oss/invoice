package com.acme.invoice.ocr;

import com.acme.invoice.credential.CredentialValidation;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BaiduPaddleTokenClientTest {

    private HttpServer server;
    private BaiduPaddleTokenClient client;
    private volatile int responseStatus;
    private volatile String responseBody;
    private volatile int responseDelayMillis;
    private volatile String authorization;
    private volatile String receivedBody;
    private String apiUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/llm/lmapi/v3/models", exchange -> {
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            receivedBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try {
                Thread.sleep(responseDelayMillis);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        apiUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/llm/lmapi/v3";
        client = new BaiduPaddleTokenClient(WebClient.builder(), 2_000);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void acceptsHttp200AndSendsTokenHeader() {
        respond(200, "{\"object\":\"list\",\"data\":[{\"id\":\"ernie-4.5-turbo-vl\"}]}");

        assertThat(client.validate(apiUrl, "access-token"))
                .isEqualTo(new CredentialValidation(true, "Credentials are valid"));
        assertThat(authorization).isEqualTo("Bearer access-token");
        assertThat(receivedBody).doesNotContain("access-token");
    }

    @Test
    void mapsUnauthorizedAndAuthErrorCodesToInvalid() {
        respond(401, "");
        assertThat(client.validate(apiUrl, "bad-token").valid()).isFalse();

        respond(403, "{\"errorCode\":403}");
        assertThat(client.validate(apiUrl, "bad-token").valid()).isFalse();

        respond(200, "{\"errorCode\":403,\"errorMsg\":\"token invalid\"}");
        assertThat(client.validate(apiUrl, "bad-token").valid()).isFalse();

        assertThat(client.validate("not-a-url", "bad-token").valid()).isFalse();
        assertThat(LlmServiceAddress.chatCompletions("https://aistudio.baidu.com/account/accessToken").toString())
                .isEqualTo("https://aistudio.baidu.com/llm/lmapi/v3/chat/completions");
        assertThat(LlmServiceAddress.base("https://aistudio.baidu.com/llm/lmapi/v3/"))
                .isEqualTo("https://aistudio.baidu.com/llm/lmapi/v3");
    }

    @Test
    void rejectsAnHtmlPageAndAnEmptyObjectAsTheOcrEndpoint() {
        respond(200, "<!DOCTYPE html><html><title>accessToken</title></html>");
        assertThat(client.validate(apiUrl, "access-token"))
                .isEqualTo(new CredentialValidation(false, OcrEndpointException.MESSAGE));

        respond(200, "{}");
        assertThat(client.validate(apiUrl, "access-token").valid()).isFalse();
    }

    @Test
    void mapsRateLimitServerFailureAndTimeoutWithoutLeakingSecrets() {
        respond(429, "raw-secret-response");
        assertUnavailableWithoutSecrets();

        respond(503, "raw-secret-response");
        assertUnavailableWithoutSecrets();

        client = new BaiduPaddleTokenClient(WebClient.builder(), 200);
        respond(200, "{}");
        responseDelayMillis = 500;
        assertUnavailableWithoutSecrets();
    }

    private void respond(int status, String body) {
        responseStatus = status;
        responseBody = body;
        responseDelayMillis = 0;
    }

    private void assertUnavailableWithoutSecrets() {
        assertThatThrownBy(() -> client.validate(apiUrl, "secret-token"))
                .isInstanceOf(PaddleServiceUnavailableException.class)
                .hasMessageNotContaining(apiUrl)
                .hasMessageNotContaining("secret-token")
                .hasMessageNotContaining("raw-secret-response");
    }
}
