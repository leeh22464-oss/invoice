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
    private volatile String receivedQuery;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/oauth/2.0/token", exchange -> {
            receivedQuery = exchange.getRequestURI().getRawQuery();
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
        client = new BaiduPaddleTokenClient(
                WebClient.builder(),
                "http://127.0.0.1:" + server.getAddress().getPort() + "/oauth/2.0/token",
                2_000);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void acceptsNonblankAccessTokenAndSendsRequiredParameters() {
        respond(200, "{\"access_token\":\"token-value\"}");

        assertThat(client.validate("client-id", "client-secret"))
                .isEqualTo(new CredentialValidation(true, "Credentials are valid"));
        assertThat(receivedQuery)
                .contains("grant_type=client_credentials")
                .contains("client_id=client-id")
                .contains("client_secret=client-secret");
    }

    @Test
    void mapsBadRequestUnauthorizedAndJsonErrorsToInvalid() {
        respond(400, "{\"error\":\"invalid_client\",\"error_description\":\"bad\"}");
        assertThat(client.validate("bad-api", "bad-secret").valid()).isFalse();

        respond(401, "");
        assertThat(client.validate("bad-api", "bad-secret").valid()).isFalse();

        respond(200, "{\"error\":\"invalid_client\"}");
        assertThat(client.validate("bad-api", "bad-secret").valid()).isFalse();
    }

    @Test
    void mapsRateLimitServerFailureAndTimeoutWithoutLeakingSecrets() {
        respond(429, "raw-secret-response");
        assertUnavailableWithoutSecrets("api-429", "secret-429");

        respond(503, "raw-secret-response");
        assertUnavailableWithoutSecrets("api-503", "secret-503");

        respond(200, "{}");
        client = new BaiduPaddleTokenClient(
                WebClient.builder(),
                "http://127.0.0.1:" + server.getAddress().getPort() + "/oauth/2.0/token",
                200);
        responseDelayMillis = 500;
        assertUnavailableWithoutSecrets("api-timeout", "secret-timeout");
    }

    private void respond(int status, String body) {
        responseStatus = status;
        responseBody = body;
        responseDelayMillis = 0;
    }

    private void assertUnavailableWithoutSecrets(String apiKey, String secretKey) {
        assertThatThrownBy(() -> client.validate(apiKey, secretKey))
                .isInstanceOf(PaddleServiceUnavailableException.class)
                .hasMessageNotContaining(apiKey)
                .hasMessageNotContaining(secretKey)
                .hasMessageNotContaining("raw-secret-response");
    }
}
