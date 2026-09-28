package com.acme.invoice.identity;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(String code, String message, Map<String, String> errors) {

    public ApiError(String code, String message) {
        this(code, message, Map.of());
    }
}
