package com.nik.exception;

import org.springframework.http.HttpStatus;

/**
 * Custom exception for book-related operations
 */
public class BookException extends ApiException {

    public BookException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }

    public BookException(String message, Throwable cause) {
        super(HttpStatus.BAD_REQUEST, message, cause);
    }
}
