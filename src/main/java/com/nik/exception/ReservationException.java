package com.nik.exception;

import org.springframework.http.HttpStatus;

/**
 * Custom exception for reservation-related operations
 */
public class ReservationException extends ApiException {

    public ReservationException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }

    public ReservationException(String message, Throwable cause) {
        super(HttpStatus.BAD_REQUEST, message, cause);
    }
}
