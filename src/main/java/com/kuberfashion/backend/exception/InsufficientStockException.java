package com.kuberfashion.backend.exception;

/**
 * Thrown when an order checkout or inventory deduction cannot be satisfied
 * due to insufficient stock or an out-of-stock product.
 */
public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException(String message) {
        super(message);
    }

    public InsufficientStockException(String message, Throwable cause) {
        super(message, cause);
    }
}
