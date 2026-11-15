package com.pivotaccess.dlmscosem.session;

/**
 * Common interface for DLMS/COSEM transport sessions.
 *
 * <p>Two implementations exist:
 * <ul>
 *   <li>{@link HDLCSessionAdapter} — wraps
 *       {@link com.pivotaccess.dlmscosem.hdlc.HDLCSession} for RS-485 and
 *       HDLC-over-TCP connections</li>
 *   <li>{@link com.pivotaccess.dlmscosem.wrapper.WrapperSession} — IEC
 *       62056-47 WRAPPER framing over TCP (no HDLC)</li>
 * </ul>
 *
 * <p>{@link com.pivotaccess.dlmscosem.cosem.CosemClient} depends only on
 * this interface and is unaware of which framing mode is active.
 */
public interface CosemSession {

    /**
     * Opens the transport and establishes the link-layer connection.
     * <ul>
     *   <li>HDLC: opens serial/TCP and exchanges SNRM/UA.</li>
     *   <li>WRAPPER: opens the TCP socket (no handshake needed).</li>
     * </ul>
     *
     * @throws CosemSessionException if the connection fails
     */
    void connect() throws CosemSessionException;

    /**
     * Sends an APDU to the meter and returns the response APDU.
     * Framing (HDLC I-frames or WRAPPER header) is handled internally.
     *
     * @param apdu request APDU bytes
     * @return response APDU bytes
     * @throws CosemSessionException on any framing, timeout, or transport error
     */
    byte[] sendReceive(byte[] apdu) throws CosemSessionException;

    /**
     * Closes the link-layer connection and releases the transport.
     * Safe to call even if {@link #connect()} was never called.
     */
    void disconnect();

    /** Returns {@code true} if the session is currently connected. */
    boolean isConnected();
}