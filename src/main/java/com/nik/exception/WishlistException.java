package com.nik.exception;

import org.springframework.http.HttpStatus;

/**
 * Custom exception for wishlist-related errors
 */
public class WishlistException extends ApiException {

    public WishlistException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }

    public WishlistException(String message, Throwable cause) {
        super(HttpStatus.BAD_REQUEST, message, cause);
    }
}
