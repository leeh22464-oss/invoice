package com.acme.invoice.ocr;

import com.acme.invoice.credential.CredentialValidation;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Component
@Profile("!smoke")
public class BaiduPaddleTokenClient implements PaddleTokenClient {

    private static final CredentialValidation VALID =
            new CredentialValidation(true, "Credentials are valid");
    private static final CredentialValidation INVALID =
            new CredentialValidation(false, "Credentials are invalid");

    private final WebClient webClient;
    private final String tokenUrl;
    private final Duration timeout;

    public BaiduPaddleTokenClient(
            WebClient.Builder builder,
            @Value("${app.paddle.token-url:https://aip.baidubce.com/oauth/2.0/token}")
            String tokenUrl,
            @Value("${app.paddle.timeout-ms:10000}") int timeoutMillis) {
        this.webClient = builder.build();
        this.tokenUrl = tokenUrl;
        this.timeout = Duration.ofMillis(timeoutMillis);
    }

    @Override
    public CredentialValidation validate(String apiKey, String secretKey) {
        try {
            TokenOutcome outcome = requestToken(apiKey, secretKey);
            return outcome.token() == null ? INVALID : VALID;
        } catch (OcrAuthenticationException exception) {
            return INVALID;
        } catch (PaddleServiceUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    @Override
    public String obtainAccessToken(String apiKey, String secretKey) {
        try {
            TokenOutcome outcome = requestToken(apiKey, secretKey);
            if (outcome.token() == null || outcome.token().isBlank()) {
                throw new OcrAuthenticationException();
            }
            return outcome.token();
        } catch (OcrAuthenticationException | PaddleServiceUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    private TokenOutcome requestToken(String apiKey, String secretKey) {
        TokenOutcome outcome = webClient.post()
                .uri(tokenUrl)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("grant_type", "client_credentials")
                        .with("client_id", apiKey)
                        .with("client_secret", secretKey))
                .exchangeToMono(this::mapTokenResponse)
                .block(timeout);
        if (outcome == null) {
            throw unavailable();
        }
        return outcome;
    }

    private Mono<TokenOutcome> mapTokenResponse(ClientResponse response) {
        HttpStatusCode status = response.statusCode();
        if (status.value() == 400 || status.value() == 401) {
            return response.releaseBody().thenReturn(TokenOutcome.invalid());
        }
        if (status.value() == 429 || status.is5xxServerError()) {
            return response.releaseBody().then(Mono.error(unavailable()));
        }
        if (status.isError()) {
            return response.bodyToMono(JsonNode.class)
                    .defaultIfEmpty(com.fasterxml.jackson.databind.node.NullNode.getInstance())
                    .flatMap(json -> hasCredentialError(json)
                            ? Mono.just(TokenOutcome.invalid())
                            : Mono.error(unavailable()));
        }
        return response.bodyToMono(JsonNode.class)
                .defaultIfEmpty(com.fasterxml.jackson.databind.node.NullNode.getInstance())
                .map(this::tokenFrom);
    }

    private TokenOutcome tokenFrom(JsonNode json) {
        if (hasCredentialError(json)) {
            return TokenOutcome.invalid();
        }
        JsonNode accessToken = json.get("access_token");
        if (accessToken == null || accessToken.asText("").isBlank()) {
            return TokenOutcome.invalid();
        }
        return TokenOutcome.valid(accessToken.asText());
    }

    private boolean hasCredentialError(JsonNode json) {
        return hasNonblank(json, "error") || hasNonblank(json, "error_description");
    }

    private boolean hasNonblank(JsonNode json, String field) {
        JsonNode value = json.get(field);
        return value != null && !value.asText("").isBlank();
    }

    private static PaddleServiceUnavailableException unavailable() {
        return new PaddleServiceUnavailableException();
    }

    private record TokenOutcome(String token) {
        static TokenOutcome valid(String token) {
            return new TokenOutcome(token);
        }

        static TokenOutcome invalid() {
            return new TokenOutcome(null);
        }
    }
}
