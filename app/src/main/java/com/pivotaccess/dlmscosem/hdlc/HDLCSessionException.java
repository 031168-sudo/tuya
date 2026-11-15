package com.pivotaccess.dlmscosem.hdlc;

import com.pivotaccess.dlmscosem.session.CosemSessionException;

/**
 * Thrown when an HDLC session-level error occurs — link establishment failure,
 * timeout, sequence error, unexpected frame type, or DM response.
 */
public class HDLCSessionException extends CosemSessionException {

    public HDLCSessionException(String message) {
        super(message);
    }

    public HDLCSessionException(String message, Throwable cause) {
        super(message, cause);
    }
}