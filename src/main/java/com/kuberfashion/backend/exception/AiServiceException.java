package com.kuberfashion.backend.exception;

import org.springframework.http.HttpStatus;

/**
 * Exception thrown when an AI content provider encounters a service,
 * rate limit, configuration, or parsing error.
 */
public class AiServiceException extends RuntimeException {

    private final HttpStatus httpStatus;

    public AiServiceException(String message) {
        super(message);
        this.httpStatus = HttpStatus.SERVICE_UNAVAILABLE;
    }

    public AiServiceException(String message, HttpStatus httpStatus) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public AiServiceException(String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = HttpStatus.SERVICE_UNAVAILABLE;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
