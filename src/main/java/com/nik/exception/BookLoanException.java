package com.nik.exception;

import org.springframework.http.HttpStatus;

/**
 * Custom exception for book loan-related operations
 */
public class BookLoanException extends ApiException {

    public BookLoanException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }

    public BookLoanException(String message, Throwable cause) {
        super(HttpStatus.BAD_REQUEST, message, cause);
    }
}
