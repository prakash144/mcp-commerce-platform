package com.commerce.order.exception;

import lombok.Getter;

import java.util.Map;

@Getter
public class OrderValidationException extends RuntimeException {

    private final String code;
    private final Map<String, Object> details;

    public OrderValidationException(String code, String message) {
        this(code, message, Map.of());
    }

    public OrderValidationException(String code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details;
    }
}
