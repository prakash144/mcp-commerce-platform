package com.commerce.order.client;

public class RecoverablePaymentException extends RuntimeException {
    public RecoverablePaymentException(String message, Throwable cause) {
        super(message, cause);
    }
}
