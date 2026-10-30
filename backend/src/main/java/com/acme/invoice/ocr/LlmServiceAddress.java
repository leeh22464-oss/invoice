package com.acme.invoice.ocr;

import java.net.URI;

final class LlmServiceAddress {

    static final String OFFICIAL_BASE = "https://aistudio.baidu.com/llm/lmapi/v3";
    static final String VISION_MODEL = "ernie-4.5-turbo-vl";

    private LlmServiceAddress() {
    }

    static URI chatCompletions(String apiUrl) {
        return URI.create(base(apiUrl) + "/chat/completions");
    }

    static URI models(String apiUrl) {
        return URI.create(base(apiUrl) + "/models");
    }

    static String base(String apiUrl) {
        if (apiUrl == null || apiUrl.isBlank() || isTokenPage(apiUrl)) {
            return OFFICIAL_BASE;
        }
        String trimmed = stripTrailingSlash(apiUrl.trim());
        if (trimmed.endsWith("/chat/completions")) {
            trimmed = stripTrailingSlash(trimmed.substring(0, trimmed.length() - "/chat/completions".length()));
        }
        if (trimmed.endsWith("/models")) {
            trimmed = stripTrailingSlash(trimmed.substring(0, trimmed.length() - "/models".length()));
        }
        return trimmed.isBlank() ? OFFICIAL_BASE : trimmed;
    }

    private static boolean isTokenPage(String apiUrl) {
        try {
            URI uri = URI.create(apiUrl.trim());
            String path = uri.getPath() == null ? "" : uri.getPath();
            return path.contains("accessToken") || path.contains("access_token");
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static String stripTrailingSlash(String value) {
        String trimmed = value;
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
