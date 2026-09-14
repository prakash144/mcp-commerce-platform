package com.commerce.order.client;

public class PermanentPaymentException extends RuntimeException {
    public PermanentPaymentException(String message) {
        super(message);
    }
}
