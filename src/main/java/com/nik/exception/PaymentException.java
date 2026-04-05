package com.nik.exception;

import org.springframework.http.HttpStatus;

/**
 * Custom exception for payment-related operations
 */
public class PaymentException extends ApiException {

    public PaymentException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }

    public PaymentException(String message, Throwable cause) {
        super(HttpStatus.BAD_REQUEST, message, cause);
    }
}
