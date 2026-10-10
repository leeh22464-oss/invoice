package com.acme.invoice.ocr;

import com.acme.invoice.credential.CredentialValidation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;

@Component
@Profile("!smoke")
public class BaiduPaddleTokenClient implements PaddleTokenClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROBE_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";
    private static final CredentialValidation VALID =
            new CredentialValidation(true, "Credentials are valid");
    private static final CredentialValidation INVALID =
            new CredentialValidation(false, "Credentials are invalid");

    private final WebClient webClient;
    private final Duration timeout;

    public BaiduPaddleTokenClient(
            WebClient.Builder builder,
            @Value("${app.paddle.timeout-ms:10000}") int timeoutMillis) {
        this.webClient = builder.build();
        this.timeout = Duration.ofMillis(timeoutMillis);
    }

    @Override
    public CredentialValidation validate(String apiUrl, String token) {
        if (!isHttpUrl(apiUrl) || token == null || token.isBlank()) {
            return INVALID;
        }
        try {
            Boolean accepted = webClient.post()
                    .uri(URI.create(apiUrl.trim()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "token " + token.trim())
                    .bodyValue("{\"file\":\"" + PROBE_PNG + "\",\"fileType\":1}")
                    .exchangeToMono(this::mapProbe)
                    .block(timeout);
            return Boolean.TRUE.equals(accepted) ? VALID : INVALID;
        } catch (OcrAuthenticationException exception) {
            return INVALID;
        } catch (PaddleServiceUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    @Override
    public String obtainAccessToken(String apiUrl, String token) {
        CredentialValidation validation = validate(apiUrl, token);
        if (!validation.valid()) {
            throw new OcrAuthenticationException();
        }
        return token.trim();
    }

    private Mono<Boolean> mapProbe(ClientResponse response) {
        HttpStatusCode status = response.statusCode();
        if (status.value() == 401 || status.value() == 403) {
            return response.releaseBody().thenReturn(false);
        }
        if (status.value() == 429 || status.is5xxServerError()) {
            return response.releaseBody().then(Mono.error(unavailable()));
        }
        if (status.isError()) {
            return response.releaseBody().then(Mono.error(unavailable()));
        }
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(this::acceptedBody);
    }

    private boolean acceptedBody(String body) {
        if (body == null || body.isBlank()) {
            return true;
        }
        try {
            JsonNode json = MAPPER.readTree(body);
            JsonNode errorCode = json.get("errorCode");
            if (errorCode != null && errorCode.isNumber()) {
                int code = errorCode.asInt();
                return code != 401 && code != 403;
            }
            return true;
        } catch (Exception exception) {
            return true;
        }
    }

    private static boolean isHttpUrl(String apiUrl) {
        if (apiUrl == null || apiUrl.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(apiUrl.trim());
            String scheme = uri.getScheme();
            return uri.getHost() != null
                    && ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static PaddleServiceUnavailableException unavailable() {
        return new PaddleServiceUnavailableException();
    }
}
