package com.pivotaccess.dlmscosem.session;

import com.pivotaccess.dlmscosem.hdlc.HDLCAddress;
import com.pivotaccess.dlmscosem.hdlc.HDLCNegotiationParameters;
import com.pivotaccess.dlmscosem.hdlc.HDLCSession;
import com.pivotaccess.dlmscosem.hdlc.HDLCSessionException;
import com.pivotaccess.dlmscosem.transport.Transport;

/**
 * Adapts {@link HDLCSession} to the {@link CosemSession} interface.
 *
 * <p>Used for both RS-485 and HDLC-over-TCP transports.
 */
public final class HDLCSessionAdapter implements CosemSession {

    private final HDLCSession delegate;
    private boolean           connected = false;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    public HDLCSessionAdapter(
            Transport transport,
            HDLCAddress clientAddress,
            HDLCAddress serverAddress,
            HDLCNegotiationParameters params,
            int timeoutMs,
            int maxRetries,
            boolean strictSequenceControl) {
        this.delegate = new HDLCSession(
                transport, clientAddress, serverAddress,
                params, timeoutMs, maxRetries, strictSequenceControl);
    }

    // -------------------------------------------------------------------------
    // CosemSession
    // -------------------------------------------------------------------------

    @Override
    public void connect() throws CosemSessionException {
        try {
            delegate.connect();
            connected = true;
        } catch (HDLCSessionException e) {
            throw new CosemSessionException("HDLC link establishment failed", e);
        }
    }

    @Override
    public byte[] sendReceive(byte[] apdu) throws CosemSessionException {
        try {
            return delegate.sendReceive(apdu);
        } catch (HDLCSessionException e) {
            throw new CosemSessionException("HDLC send/receive failed", e);
        }
    }

    @Override
    public void disconnect() {
        delegate.disconnect();
        connected = false;
    }

    @Override
    public boolean isConnected() {
        return connected && delegate.isConnected();
    }
}