package com.nik.exception;

import org.springframework.http.HttpStatus;

/**
 * Custom exception for genre-related operations
 */
public class GenreException extends ApiException {

    public GenreException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }

    public GenreException(String message, Throwable cause) {
        super(HttpStatus.BAD_REQUEST, message, cause);
    }
}
