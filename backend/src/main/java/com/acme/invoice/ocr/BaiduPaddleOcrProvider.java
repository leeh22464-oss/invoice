package com.acme.invoice.ocr;

import com.acme.invoice.credential.PaddleCredential;
import com.acme.invoice.ingestion.StoredFile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

public class BaiduPaddleOcrProvider implements OcrProvider {

    private static final int MAX_ATTEMPTS = 3;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PaddleTokenClient tokenClient;
    private final WebClient webClient;
    private final String recognizeUrl;
    private final Function<StoredFile, byte[]> contentLoader;
    private final Duration timeout;
    private final Consumer<Duration> sleeper;

    public BaiduPaddleOcrProvider(
            PaddleTokenClient tokenClient,
            WebClient.Builder builder,
            String recognizeUrl,
            Function<StoredFile, byte[]> contentLoader,
            Duration timeout,
            Consumer<Duration> sleeper) {
        this.tokenClient = tokenClient;
        this.webClient = builder == null ? null : builder.build();
        this.recognizeUrl = recognizeUrl;
        this.contentLoader = contentLoader;
        this.timeout = timeout;
        this.sleeper = sleeper;
    }

    @Override
    public OcrDocument recognize(PaddleCredential credential, StoredFile file) {
        String accessToken = tokenClient.obtainAccessToken(credential.apiKey(), credential.secretKey());
        byte[] bytes = contentLoader.apply(file);
        if (bytes == null) {
            throw new PaddleServiceUnavailableException();
        }
        String payloadField = "application/pdf".equals(file.mediaType()) ? "pdf_file" : "image";
        String encoded = Base64.getEncoder().encodeToString(bytes);

        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                String body = invoke(accessToken, payloadField, encoded);
                return parseResponse(body);
            } catch (OcrAuthenticationException exception) {
                throw exception;
            } catch (PaddleServiceUnavailableException exception) {
                lastFailure = exception;
                if (attempt == MAX_ATTEMPTS) {
                    throw exception;
                }
                long baseMillis = Math.max(1L, timeout.toMillis());
                sleeper.accept(Duration.ofMillis((1L << (attempt - 1)) * baseMillis));
            }
        }
        throw lastFailure == null ? new PaddleServiceUnavailableException() : lastFailure;
    }

    public OcrDocument parseResponse(String rawJson) {
        try {
            JsonNode root = MAPPER.readTree(rawJson);
            String requestId = textOrNumber(root.get("log_id"));
            List<OcrField> fields = new ArrayList<>();
            JsonNode words = root.get("words_result");
            if (words != null && words.isArray() && !words.isEmpty()) {
                JsonNode first = words.get(0);
                BigDecimal confidence = decimalOrNull(first.get("probability"));
                if (confidence == null) {
                    confidence = BigDecimal.ONE;
                }
                JsonNode typeNode = first.get("type");
                if (typeNode != null && !typeNode.asText("").isBlank()) {
                    fields.add(new OcrField("type", typeNode.asText(), confidence));
                }
                JsonNode result = first.get("result");
                if (result != null && result.isObject()) {
                    Iterator<Map.Entry<String, JsonNode>> properties = result.fields();
                    while (properties.hasNext()) {
                        Map.Entry<String, JsonNode> entry = properties.next();
                        String value = extractWord(entry.getValue());
                        if (value != null) {
                            fields.add(new OcrField(entry.getKey(), value, confidence));
                        }
                    }
                }
            }
            return new OcrDocument(requestId, rawJson, fields);
        } catch (RuntimeException exception) {
            throw new PaddleServiceUnavailableException();
        } catch (Exception exception) {
            throw new PaddleServiceUnavailableException();
        }
    }

    private String invoke(String accessToken, String payloadField, String encoded) {
        try {
            String body = webClient.post()
                    .uri(UriComponentsBuilder.fromUri(URI.create(recognizeUrl))
                            .queryParam("access_token", accessToken)
                            .build(true)
                            .toUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(payloadField, encoded)
                            .with("probability", "true"))
                    .exchangeToMono(this::mapBody)
                    .block(timeout);
            if (body == null) {
                throw new PaddleServiceUnavailableException();
            }
            return body;
        } catch (OcrAuthenticationException | PaddleServiceUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unwrapProviderFailure(exception);
        }
    }

    private static RuntimeException unwrapProviderFailure(RuntimeException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof OcrAuthenticationException authenticationException) {
                return authenticationException;
            }
            if (current instanceof PaddleServiceUnavailableException unavailableException) {
                return unavailableException;
            }
            current = current.getCause();
        }
        return new PaddleServiceUnavailableException();
    }

    private Mono<String> mapBody(ClientResponse response) {
        HttpStatusCode status = response.statusCode();
        if (status.value() == 401) {
            return response.releaseBody().then(Mono.error(new OcrAuthenticationException()));
        }
        if (status.value() == 429 || status.is5xxServerError()) {
            return response.releaseBody().then(Mono.error(new PaddleServiceUnavailableException()));
        }
        if (status.isError()) {
            return response.bodyToMono(String.class)
                    .defaultIfEmpty("")
                    .flatMap(body -> bodyContainsCredentialError(body)
                            ? Mono.error(new OcrAuthenticationException())
                            : Mono.error(new PaddleServiceUnavailableException()));
        }
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .flatMap(body -> bodyContainsCredentialError(body)
                        ? Mono.error(new OcrAuthenticationException())
                        : Mono.just(body));
    }

    private static boolean bodyContainsCredentialError(String body) {
        return body != null
                && (body.contains("\"error\"")
                || body.contains("invalid_token")
                || body.contains("invalid_client"));
    }

    private static String extractWord(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual() || node.isNumber()) {
            String text = node.asText();
            return text.isBlank() ? null : text;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (item != null && item.has("word")) {
                    String word = item.get("word").asText("");
                    if (!word.isBlank()) {
                        return word;
                    }
                }
            }
            return null;
        }
        if (node.has("word")) {
            String word = node.get("word").asText("");
            return word.isBlank() ? null : word;
        }
        return null;
    }

    private static String textOrNumber(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.asText();
    }

    private static BigDecimal decimalOrNull(JsonNode node) {
        if (node == null || node.isNull() || node.asText("").isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(node.asText());
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
