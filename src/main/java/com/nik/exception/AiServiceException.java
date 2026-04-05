package com.nik.exception;

import org.springframework.http.HttpStatus;

/**
 * Raised when AI-specific dependencies or request context are unavailable.
 */
public class AiServiceException extends ApiException {

    public AiServiceException(String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, message);
    }

    public AiServiceException(String message, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, message, cause);
    }
}
