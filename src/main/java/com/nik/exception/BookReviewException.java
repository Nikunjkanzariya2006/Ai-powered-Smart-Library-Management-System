package com.nik.exception;

import org.springframework.http.HttpStatus;

/**
 * Custom exception for book review related errors
 */
public class BookReviewException extends ApiException {

    public BookReviewException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }

    public BookReviewException(String message, Throwable cause) {
        super(HttpStatus.BAD_REQUEST, message, cause);
    }
}
