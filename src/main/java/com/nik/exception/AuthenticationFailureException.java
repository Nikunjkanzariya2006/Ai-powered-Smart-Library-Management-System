package com.nik.exception;

import org.springframework.http.HttpStatus;

public class AuthenticationFailureException extends ApiException {

    public AuthenticationFailureException(String message) {
        super(HttpStatus.UNAUTHORIZED, message);
    }

    public AuthenticationFailureException(String message, Throwable cause) {
        super(HttpStatus.UNAUTHORIZED, message, cause);
    }
}
