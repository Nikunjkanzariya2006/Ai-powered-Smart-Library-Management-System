
package com.nik.exception;

import org.springframework.http.HttpStatus;

public class UserException extends ApiException {
	
	public UserException(String message) {
		super(HttpStatus.BAD_REQUEST, message);
	}

    public UserException(String message, Throwable cause) {
        super(HttpStatus.BAD_REQUEST, message, cause);
    }
}
