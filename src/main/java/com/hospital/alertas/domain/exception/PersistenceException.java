package com.hospital.alertas.domain.exception;

/**
 * Custom unchecked exception used exclusively by the Persistence Adapter layer 
 * when DynamoDB or underlying infrastructure operations fail.
 */
public class PersistenceException extends RuntimeException {

    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }

    // Private constructor for programmatic use where no specific root cause is available but an error must be signaled.
    public PersistenceException(String message) {
        super(message);
    }
}