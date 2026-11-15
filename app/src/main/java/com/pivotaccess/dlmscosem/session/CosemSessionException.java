package com.pivotaccess.dlmscosem.session;

/**
 * Unified session-layer exception thrown by both
 * {@link HDLCSessionAdapter} and
 * {@link com.pivotaccess.dlmscosem.wrapper.WrapperSession}.
 *
 * <p>Callers only need to catch this one type regardless of whether the
 * underlying session uses HDLC or WRAPPER framing.
 */
public class CosemSessionException extends Exception {

    public CosemSessionException(String message) {
        super(message);
    }

    public CosemSessionException(String message, Throwable cause) {
        super(message, cause);
    }
}