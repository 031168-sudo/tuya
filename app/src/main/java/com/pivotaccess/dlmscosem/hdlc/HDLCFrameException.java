package com.pivotaccess.dlmscosem.hdlc;

/**
 * Thrown when an HDLC frame cannot be encoded or decoded — for example,
 * invalid flag bytes, bad CRC, unsupported address length, or truncated data.
 */
public class HDLCFrameException extends Exception {

    public HDLCFrameException(String message) {
        super(message);
    }

    public HDLCFrameException(String message, Throwable cause) {
        super(message, cause);
    }
}