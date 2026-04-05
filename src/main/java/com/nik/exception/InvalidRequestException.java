package com.nik.exception;

import org.springframework.http.HttpStatus;

/**
 * Raised when request input is syntactically correct but invalid for the use case.
 */
public class InvalidRequestException extends ApiException {

    public InvalidRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }

    public InvalidRequestException(String message, Throwable cause) {
        super(HttpStatus.BAD_REQUEST, message, cause);
    }
}
