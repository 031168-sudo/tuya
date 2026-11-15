package com.pivotaccess.dlmscosem.apdu;

/**
 * Thrown when an APDU cannot be encoded or decoded, or when the
 * application-level response indicates an error (e.g. access denied,
 * object not found, type mismatch).
 */
public class ApduException extends Exception {

    public ApduException(String message) {
        super(message);
    }

    public ApduException(String message, Throwable cause) {
        super(message, cause);
    }
}