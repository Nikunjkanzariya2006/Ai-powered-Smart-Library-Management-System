package com.nik.exception;

import org.springframework.http.HttpStatus;

/**
 * Exception for subscription plan related errors
 */
public class SubscriptionPlanException extends ApiException {

    public SubscriptionPlanException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }

    public SubscriptionPlanException(String message, Throwable cause) {
        super(HttpStatus.BAD_REQUEST, message, cause);
    }
}
