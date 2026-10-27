package com.kuberfashion.backend.exception;

/**
 * Thrown when a request reuses an existing idempotency key with a conflicting payload
 * (e.g. different items, quantities, address, or payment method).
 */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String message) {
        super(message);
    }

    public IdempotencyConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
