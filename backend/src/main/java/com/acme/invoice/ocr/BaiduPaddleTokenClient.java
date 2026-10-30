package com.acme.invoice.ocr;

import com.acme.invoice.credential.CredentialValidation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatusCode;
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
    private static final CredentialValidation VALID =
            new CredentialValidation(true, "Credentials are valid");
    private static final CredentialValidation INVALID =
            new CredentialValidation(false, "接口地址或访问令牌无效");
    private static final CredentialValidation NOT_OCR_ENDPOINT =
            new CredentialValidation(false, OcrEndpointException.MESSAGE);

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
            String verdict = webClient.get()
                    .uri(LlmServiceAddress.models(apiUrl))
                    .header("Authorization", "Bearer " + token.trim())
                    .exchangeToMono(this::mapProbe)
                    .block(timeout);
            if ("valid".equals(verdict)) {
                return VALID;
            }
            if ("invalid".equals(verdict)) {
                return INVALID;
            }
            return NOT_OCR_ENDPOINT;
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

    private Mono<String> mapProbe(ClientResponse response) {
        HttpStatusCode status = response.statusCode();
        if (status.value() == 401 || status.value() == 403) {
            return response.releaseBody().thenReturn("invalid");
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

    private String acceptedBody(String body) {
        if (body == null || body.isBlank()) {
            return "not-ocr";
        }
        try {
            JsonNode json = MAPPER.readTree(body);
            if (!json.isObject()) {
                return "not-ocr";
            }
            JsonNode errorCode = json.get("errorCode");
            if (errorCode != null && errorCode.isNumber()) {
                int code = errorCode.asInt();
                return code == 401 || code == 403 ? "invalid" : "valid";
            }
            if (json.has("data") && json.get("data").isArray()) {
                return "valid";
            }
            if (json.has("result") || json.has("ocrResults") || json.has("words_result")) {
                return "valid";
            }
            JsonNode error = json.get("error");
            if (error != null && error.isObject()) {
                String code = error.path("code").asText("");
                String message = error.path("message").asText("").toLowerCase();
                if (code.contains("invalid") || message.contains("api key") || message.contains("token")) {
                    return "invalid";
                }
            }
            return "not-ocr";
        } catch (Exception exception) {
            return "not-ocr";
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
