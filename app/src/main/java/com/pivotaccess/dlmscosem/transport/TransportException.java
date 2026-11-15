package com.pivotaccess.dlmscosem.transport;

/**
 * Thrown when a transport-level error occurs (port failure, timeout,
 * socket error, etc.).
 */
public class TransportException extends Exception {

    public TransportException(String message) {
        super(message);
    }

    public TransportException(String message, Throwable cause) {
        super(message, cause);
    }
}