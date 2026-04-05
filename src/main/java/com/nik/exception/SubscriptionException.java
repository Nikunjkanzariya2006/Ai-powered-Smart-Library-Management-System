package com.nik.exception;

import org.springframework.http.HttpStatus;

/**
 * Custom exception for subscription-related operations
 */
public class SubscriptionException extends ApiException {

    public SubscriptionException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }

    public SubscriptionException(String message, Throwable cause) {
        super(HttpStatus.BAD_REQUEST, message, cause);
    }
}
