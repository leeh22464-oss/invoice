package com.acme.invoice.ocr;

import com.acme.invoice.credential.PaddleCredential;
import com.acme.invoice.ingestion.StoredFile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

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
    private static final String TRANSCRIPT_PROMPT = """
            你是发票录入员。只输出一个 JSON 对象，不要 Markdown，不要解释。
            字段为 title、invoice_code、invoice_number、ticket_number、date、amount、tax、total。
            数电发票只有 20 位发票号码，写入 invoice_number，invoice_code 用空字符串。
            10 到 12 位发票代码和 8 位发票号码分开写。
            火车票、机票的电子客票号写入 ticket_number。
            date 用 YYYY-MM-DD。amount、tax、total 用阿拉伯数字，不要大写金额，不要货币符号。
            看不清的字段用空字符串。
            """;

    private final WebClient webClient;
    private final Function<StoredFile, byte[]> contentLoader;
    private final Duration timeout;
    private final Consumer<Duration> sleeper;

    public BaiduPaddleOcrProvider(
            WebClient.Builder builder,
            Function<StoredFile, byte[]> contentLoader,
            Duration timeout,
            Consumer<Duration> sleeper) {
        this.webClient = builder == null ? null : builder
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
        this.contentLoader = contentLoader;
        this.timeout = timeout;
        this.sleeper = sleeper;
    }

    @Override
    public OcrDocument recognize(PaddleCredential credential, StoredFile file) {
        byte[] bytes = contentLoader.apply(file);
        if (bytes == null) {
            throw new PaddleServiceUnavailableException();
        }
        List<String> images = imageDataUrls(file, bytes);

        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                String body = invoke(credential.apiKey(), credential.secretKey(), images);
                return parseChatResponse(body);
            } catch (OcrAuthenticationException | OcrEndpointException exception) {
                throw exception;
            } catch (PaddleServiceUnavailableException exception) {
                lastFailure = exception;
                if (attempt == MAX_ATTEMPTS) {
                    throw exception;
                }
                long baseMillis = Math.min(1_000L, Math.max(1L, timeout.toMillis()));
                sleeper.accept(Duration.ofMillis((1L << (attempt - 1)) * baseMillis));
            }
        }
        throw lastFailure == null ? new PaddleServiceUnavailableException() : lastFailure;
    }

    public OcrDocument parseChatResponse(String rawJson) {
        if (rawJson == null || rawJson.isBlank() || !rawJson.stripLeading().startsWith("{")) {
            throw new OcrEndpointException();
        }
        try {
            JsonNode root = MAPPER.readTree(rawJson);
            if (!root.isObject() || !root.has("choices")) {
                throw new OcrEndpointException();
            }
            String text = messageText(root.path("choices").path(0).path("message").path("content"));
            JsonNode id = root.get("id");
            String requestId = id == null || id.isNull() || id.asText("").isBlank() ? null : id.asText();
            return PaddleOcrV5Parser.fromLines(transcriptLines(text), rawJson, requestId);
        } catch (OcrEndpointException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new OcrEndpointException();
        }
    }

    public OcrDocument parseResponse(String rawJson) {
        if (!looksLikeOcr(rawJson)) {
            throw new OcrEndpointException();
        }
        rawJson = withoutImages(rawJson);
        try {
            JsonNode root = MAPPER.readTree(rawJson);
            if (root.has("result") || root.has("ocrResults")) {
                return PaddleOcrV5Parser.parse(root, rawJson);
            }
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

    private String invoke(String apiUrl, String token, List<String> images) {
        try {
            String body = webClient.post()
                    .uri(LlmServiceAddress.chatCompletions(apiUrl))
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + token)
                    .bodyValue(chatPayload(images))
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
            if (current instanceof OcrEndpointException endpointException) {
                return endpointException;
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
        if (status.value() == 401 || status.value() == 403) {
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
                .flatMap(body -> bodyContainsCredentialError(body) && !body.contains("\"choices\"")
                        ? Mono.error(new OcrAuthenticationException())
                        : Mono.just(body));
    }

    private static String chatPayload(List<String> images) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", LlmServiceAddress.VISION_MODEL);
        body.put("stream", false);
        ArrayNode messages = body.putArray("messages");
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        ArrayNode content = user.putArray("content");
        content.addObject().put("type", "text").put("text", TRANSCRIPT_PROMPT);
        for (String image : images) {
            ObjectNode part = content.addObject();
            part.put("type", "image_url");
            part.putObject("image_url").put("url", image).put("detail", "high");
        }
        try {
            return MAPPER.writeValueAsString(body);
        } catch (Exception exception) {
            throw new PaddleServiceUnavailableException();
        }
    }

    private static List<String> imageDataUrls(StoredFile file, byte[] bytes) {
        if ("application/pdf".equals(file.mediaType())) {
            List<String> urls = new ArrayList<>();
            for (byte[] page : PdfPageRasterizer.pngPages(bytes)) {
                urls.add("data:image/png;base64," + Base64.getEncoder().encodeToString(page));
            }
            return urls;
        }
        String mime = file.mediaType() == null || file.mediaType().isBlank() ? "image/jpeg" : file.mediaType();
        if ("image/jpg".equals(mime)) {
            mime = "image/jpeg";
        }
        return List.of("data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes));
    }

    private static String messageText(JsonNode content) {
        if (content == null || content.isMissingNode() || content.isNull()) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText("");
        }
        if (content.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode part : content) {
                JsonNode text = part.get("text");
                if (text != null && !text.asText("").isBlank()) {
                    builder.append(text.asText()).append('\n');
                }
            }
            return builder.toString();
        }
        return "";
    }

    private static List<String> transcriptLines(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        String text = content.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```(?:json|text)?\\s*", "");
            int fence = text.lastIndexOf("```");
            if (fence >= 0) {
                text = text.substring(0, fence);
            }
            text = text.trim();
        }
        List<String> structured = linesFromJson(text);
        if (structured != null) {
            return structured;
        }
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String trimmed = line.trim().replaceFirst("^[-*]\\s+", "");
            if (!trimmed.isEmpty() && !"```".equals(trimmed)) {
                lines.add(trimmed);
            }
        }
        return lines;
    }

    private static List<String> linesFromJson(String text) {
        if (!text.startsWith("{")) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(text);
            if (!node.isObject() || node.has("choices")) {
                return null;
            }
            if (!node.has("invoice_number")
                    && !node.has("invoice_code")
                    && !node.has("total")
                    && !node.has("title")
                    && !node.has("amount")) {
                return null;
            }
            List<String> lines = new ArrayList<>();
            addPlain(lines, node.path("title").asText(""));
            addLabeled(lines, "发票代码", node.path("invoice_code").asText(""));
            addLabeled(lines, "发票号码", node.path("invoice_number").asText(""));
            addLabeled(lines, "电子客票号", node.path("ticket_number").asText(""));
            addLabeled(lines, "开票日期", node.path("date").asText(""));
            addLabeled(lines, "合计金额", node.path("amount").asText(""));
            addLabeled(lines, "合计税额", node.path("tax").asText(""));
            addLabeled(lines, "价税合计", node.path("total").asText(""));
            return lines;
        } catch (Exception exception) {
            return null;
        }
    }

    private static void addPlain(List<String> lines, String value) {
        if (value != null && !value.isBlank()) {
            lines.add(value.trim());
        }
    }

    private static void addLabeled(List<String> lines, String label, String value) {
        if (value != null && !value.isBlank()) {
            lines.add(label + "：" + value.trim());
        }
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

    private static boolean looksLikeOcr(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return false;
        }
        String trimmed = rawJson.stripLeading();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return false;
        }
        try {
            JsonNode root = MAPPER.readTree(rawJson);
            return root.has("result")
                    || root.has("ocrResults")
                    || root.has("words_result")
                    || root.has("errorCode");
        } catch (Exception exception) {
            return false;
        }
    }

    private static String withoutImages(String rawJson) {
        if (rawJson == null
                || (!rawJson.contains("ocrImage")
                && !rawJson.contains("inputImage")
                && !rawJson.contains("docPreprocessingImage"))) {
            return rawJson;
        }
        try {
            JsonNode root = MAPPER.readTree(rawJson);
            removeImageFields(root);
            return MAPPER.writeValueAsString(root);
        } catch (Exception exception) {
            return rawJson;
        }
    }

    private static void removeImageFields(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node instanceof ObjectNode object) {
            object.remove("ocrImage");
            object.remove("inputImage");
            object.remove("docPreprocessingImage");
            object.properties().forEach(entry -> removeImageFields(entry.getValue()));
            return;
        }
        if (node.isArray()) {
            node.forEach(BaiduPaddleOcrProvider::removeImageFields);
        }
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
