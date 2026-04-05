package com.nik.exception;

import org.springframework.http.HttpStatus;

/**
 * Custom exception for fine-related operations
 */
public class FineException extends ApiException {

    public FineException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }

    public FineException(String message, Throwable cause) {
        super(HttpStatus.BAD_REQUEST, message, cause);
    }
}
