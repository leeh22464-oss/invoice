package com.acme.invoice.ocr;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

final class PaddleOcrV5Parser {

    private PaddleOcrV5Parser() {
    }

    static OcrDocument parse(JsonNode root, String rawJson) {
        JsonNode pages = root.path("result").path("ocrResults");
        if (!pages.isArray()) {
            pages = root.path("ocrResults");
        }
        List<String> texts = new ArrayList<>();
        List<BigDecimal> scores = new ArrayList<>();
        if (pages.isArray()) {
            for (JsonNode page : pages) {
                JsonNode pruned = page.path("prunedResult");
                JsonNode lines = pruned.path("rec_texts");
                JsonNode lineScores = pruned.path("rec_scores");
                if (!lines.isArray()) {
                    continue;
                }
                for (int index = 0; index < lines.size(); index++) {
                    String line = lines.get(index).asText("").trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    texts.add(line);
                    scores.add(scoreAt(lineScores, index));
                }
            }
        }
        return new OcrDocument(text(root, "logId"), rawJson, toFields(texts, scores));
    }

    private static List<OcrField> toFields(List<String> texts, List<BigDecimal> scores) {
        String joined = String.join("\n", texts);
        String providerType = detectType(joined);
        List<OcrField> fields = new ArrayList<>();
        BigDecimal fallback = scores.isEmpty() ? BigDecimal.ONE : scores.get(0);
        fields.add(new OcrField("type", providerType, fallback));
        fields.add(new OcrField("InvoiceType", headline(texts, joined), fallback));

        Hit code = labeled(texts, scores, "发票代码");
        Hit number = firstNonNull(
                labeled(texts, scores, "发票号码"),
                labeled(texts, scores, "电子客票号"));
        Hit date = firstNonNull(
                labeled(texts, scores, "开票日期"),
                labeled(texts, scores, "乘车日期"),
                labeled(texts, scores, "日期"));
        Hit amount = money(firstNonNull(
                labeled(texts, scores, "合计金额"),
                labeled(texts, scores, "票价"),
                labeled(texts, scores, "金额")));
        Hit tax = money(firstNonNull(
                labeled(texts, scores, "合计税额"),
                labeled(texts, scores, "税额")));
        Hit total = money(firstNonNull(
                labeled(texts, scores, "价税合计"),
                labeled(texts, scores, "合计")));

        switch (providerType) {
            case "train_ticket" -> {
                add(fields, "ticket_num", number);
                add(fields, "invoice_num", number);
                add(fields, "fare", amount);
                add(fields, "tax", tax);
                add(fields, "ticket_rates", total == null ? amount : total);
            }
            case "air_ticket" -> {
                add(fields, "ticket_number", number);
                add(fields, "fare", amount);
                add(fields, "commodity_tax", tax);
                add(fields, "ticket_rates", total);
                add(fields, "start_date", date);
            }
            case "taxi_receipt" -> {
                add(fields, "Fare", amount);
                add(fields, "TotalFare", total == null ? amount : total);
            }
            case "quota_invoice" -> {
                add(fields, "invoice_code", code);
                add(fields, "invoice_number", number);
                add(fields, "invoice_rate_in_figure", total == null ? amount : total);
            }
            case "customs_payment" -> {
                add(fields, "InvoiceNum", number);
                add(fields, "InvoiceDate", date);
                add(fields, "TotalAmount", amount);
                add(fields, "TotalTax", tax);
                add(fields, "AmountInFiguers", total);
            }
            default -> {
                add(fields, "InvoiceCode", code);
                add(fields, "InvoiceNum", number);
                add(fields, "InvoiceDate", date);
                add(fields, "TotalAmount", amount);
                add(fields, "TotalTax", tax);
                add(fields, "AmountInFiguers", total);
            }
        }
        return fields;
    }

    private static String detectType(String joined) {
        if (joined.contains("海关")) {
            return "customs_payment";
        }
        if (joined.contains("定额")) {
            return "quota_invoice";
        }
        if (joined.contains("行程单") || joined.contains("航空")) {
            return "air_ticket";
        }
        if (joined.contains("铁路") || joined.contains("火车票")) {
            return "train_ticket";
        }
        if (joined.contains("出租车") || joined.contains("出租汽车")) {
            return "taxi_receipt";
        }
        return "vat_invoice";
    }

    private static String headline(List<String> texts, String joined) {
        for (String text : texts) {
            if (text.contains("发票") || text.contains("行程") || text.contains("铁路") || text.contains("出租")) {
                return text;
            }
        }
        return joined.isBlank() ? "UNKNOWN" : joined;
    }

    private static Hit labeled(List<String> texts, List<BigDecimal> scores, String label) {
        for (int index = 0; index < texts.size(); index++) {
            String compact = compact(texts.get(index));
            int split = splitAt(compact);
            if (split > 0 && labelMatches(compact.substring(0, split), label)) {
                String value = compact.substring(split + 1).trim();
                if (!value.isBlank()) {
                    return new Hit(value, scoreAt(scores, index));
                }
            }
            String rest = remainder(compact, label);
            if (rest != null && !rest.isBlank()) {
                return new Hit(rest, scoreAt(scores, index));
            }
            if ((rest != null || labelMatches(compact, label)) && index + 1 < texts.size()) {
                String value = compact(texts.get(index + 1));
                if (!value.isBlank() && remainder(value, label) == null && !labelMatches(value, label)) {
                    return new Hit(value, scoreAt(scores, index + 1));
                }
            }
        }
        return null;
    }

    private static boolean labelMatches(String compact, String label) {
        String normalized = stripParens(compact);
        if ("价税合计".equals(label)) {
            return normalized.equals(label) || normalized.startsWith(label);
        }
        return normalized.equals(label);
    }

    private static String remainder(String compact, String label) {
        String normalized = stripParens(compact);
        if (!normalized.startsWith(label) || normalized.equals(label)) {
            return null;
        }
        String rest = normalized.substring(label.length()).replaceFirst("^[：:]+", "");
        if ("价税合计".equals(label)) {
            rest = rest.replaceFirst("^[大小]写", "");
        }
        if (rest.isEmpty()) {
            return "";
        }
        if (rest.chars().allMatch(ch -> Character.isDigit(ch) || ch == '.' || ch == ',' || ch == '，'
                || ch == '¥' || ch == '￥' || ch == '年' || ch == '月' || ch == '日' || ch == '-')) {
            return rest;
        }
        return null;
    }

    private static String stripParens(String compact) {
        return compact.replace("（", "").replace("）", "").replace("(", "").replace(")", "");
    }

    private static int splitAt(String compact) {
        int full = compact.indexOf('：');
        int half = compact.indexOf(':');
        if (full < 0) {
            return half;
        }
        if (half < 0) {
            return full;
        }
        return Math.min(full, half);
    }

    private static String compact(String value) {
        return value.replace(" ", "").replace("\u3000", "").trim();
    }

    private static Hit money(Hit hit) {
        if (hit == null) {
            return null;
        }
        String cleaned = hit.value()
                .replace(",", "")
                .replace("，", "")
                .replace("￥", "")
                .replace("¥", "")
                .replace("元", "")
                .trim();
        if (cleaned.isBlank()) {
            return null;
        }
        return new Hit(cleaned, hit.confidence());
    }

    private static void add(List<OcrField> fields, String name, Hit hit) {
        if (hit != null && hit.value() != null && !hit.value().isBlank()) {
            fields.add(new OcrField(name, hit.value(), hit.confidence()));
        }
    }

    private static Hit firstNonNull(Hit... values) {
        for (Hit value : values) {
            if (value != null && value.value() != null && !value.value().isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static BigDecimal scoreAt(List<BigDecimal> scores, int index) {
        if (index < 0 || index >= scores.size() || scores.get(index) == null) {
            return BigDecimal.ONE;
        }
        return scores.get(index);
    }

    private static BigDecimal scoreAt(JsonNode scores, int index) {
        if (scores == null || !scores.isArray() || index >= scores.size()) {
            return BigDecimal.ONE;
        }
        JsonNode score = scores.get(index);
        if (score == null || score.isNull()) {
            return BigDecimal.ONE;
        }
        try {
            return new BigDecimal(score.asText());
        } catch (NumberFormatException exception) {
            return BigDecimal.ONE;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    private record Hit(String value, BigDecimal confidence) {
    }
}
