package com.pivotaccess.dlmscosem.cosem;

/**
 * Top-level exception thrown by {@link CosemClient} for any error that
 * occurs during association setup, GET, SET, or ACTION operations.
 *
 * <p>Wraps lower-level exceptions (HDLC session errors, APDU decode errors,
 * transport errors) into a single unified exception type for callers.
 */
public class CosemClientException extends Exception {

    public CosemClientException(String message) {
        super(message);
    }

    public CosemClientException(String message, Throwable cause) {
        super(message, cause);
    }
}