package com.pivotaccess.dlmscosem.wrapper;

import com.pivotaccess.dlmscosem.session.CosemSession;
import com.pivotaccess.dlmscosem.session.CosemSessionException;
import com.pivotaccess.dlmscosem.transport.Transport;
import com.pivotaccess.dlmscosem.transport.TransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;

/**
 * DLMS/COSEM WRAPPER session as defined in IEC 62056-47.
 *
 * <p>WRAPPER framing is the simpler alternative to HDLC for TCP/IP connections.
 * APDUs are prefixed with an 8-byte header and sent directly over TCP — no
 * SNRM/UA handshake, no I-frame sequencing.
 *
 * <h3>WRAPPER frame format</h3>
 * <pre>
 * Byte 0–1 : version        = 0x0001
 * Byte 2–3 : source wPort   (client logical address, e.g. 0x0010 = 16)
 * Byte 4–5 : destination wPort (server logical address, e.g. 0x0001 = 1)
 * Byte 6–7 : payload length (APDU byte count)
 * Byte 8+  : APDU payload
 * </pre>
 *
 * <h3>Server-initiated frame on connect</h3>
 * <p>Some push an unsolicited rejected
 * AARE frame immediately after the TCP connection is accepted — before the
 * client has sent anything.  If this stale AARE is read as the response to our
 * AARQ the association will appear to be rejected.
 *
 * <p>{@link #connect()} therefore sleeps briefly to let the server's scheduler
 * actually send that initial frame, then drains and discards it before the
 * caller sends AARQ.
 */
public final class WrapperSession implements CosemSession {

    private static final Logger log = LoggerFactory.getLogger(WrapperSession.class);

    private static final int VERSION     = 0x0001;
    private static final int HEADER_SIZE = 8;
    private static final int READ_CHUNK  = 4096;

    /** Time to sleep after connect so the server can send its initial frame. */
    private static final int DRAIN_SLEEP_MS   = 150;

    /** Read timeout for the drain call — must be long enough for the server's
     *  JVM thread to wake up and write to the socket. */
    private static final int DRAIN_TIMEOUT_MS = 500;

    // -------------------------------------------------------------------------

    private final Transport transport;
    private final int       clientWPort;
    private final int       serverWPort;
    private final int       timeoutMs;
    private boolean         connected = false;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /**
     * @param transport   TCP transport (not yet open)
     * @param clientWPort source wPort — client logical address (e.g. 16)
     * @param serverWPort destination wPort — server logical address (e.g. 1)
     * @param timeoutMs   read timeout per response in milliseconds
     */
    public WrapperSession(Transport transport,
                          int clientWPort,
                          int serverWPort,
                          int timeoutMs) {
        this.transport   = transport;
        this.clientWPort = clientWPort;
        this.serverWPort = serverWPort;
        this.timeoutMs   = timeoutMs;
    }

    /** Convenience constructor with a default 10-second timeout. */
    public WrapperSession(Transport transport, int clientWPort, int serverWPort) {
        this(transport, clientWPort, serverWPort, 10_000);
    }

    // -------------------------------------------------------------------------
    // CosemSession
    // -------------------------------------------------------------------------

    /**
     * Opens the TCP connection, then drains any server-initiated frame.
     *
     * <p>No HDLC handshake is needed — TCP provides connection management for
     * WRAPPER sessions.  However, some servers push an unsolicited frame
     * immediately on connect.  That frame is
     * consumed here so it is not mistakenly read as the AARE response to our
     * AARQ.
     */
    @Override
    public void connect() throws CosemSessionException {
        try {
            if (!transport.isOpen()) {
                transport.open();
            }
            connected = true;
            log.debug("WRAPPER session connected: {}", transport.describe());
        } catch (TransportException e) {
            throw new CosemSessionException(
                    "WRAPPER: failed to open transport: " + transport.describe(), e);
        }

        drainServerInitiatedFrame();
    }

    /**
     * Wraps {@code apdu} in a WRAPPER header, sends it, and returns the
     * unwrapped response APDU.
     */
    @Override
    public byte[] sendReceive(byte[] apdu) throws CosemSessionException {
        assertConnected();

        byte[] frame = buildFrame(apdu);
        log.debug("WRAPPER TX → {} bytes: {}", frame.length, hex(frame));

        try {
            transport.write(frame);
        } catch (TransportException e) {
            throw new CosemSessionException("WRAPPER: write failed", e);
        }

        return readResponse();
    }

    @Override
    public void disconnect() {
        transport.close();
        connected = false;
        log.debug("WRAPPER session disconnected: {}", transport.describe());
    }

    @Override
    public boolean isConnected() {
        return connected && transport.isOpen();
    }

    // -------------------------------------------------------------------------
    // Drain
    // -------------------------------------------------------------------------

    /**
     * Discards up to one WRAPPER frame that the server may have sent before
     * receiving any request from us.
     *
     * <p>Strategy:
     * <ol>
     *   <li>Sleep {@value #DRAIN_SLEEP_MS} ms — gives the server's OS/JVM
     *       scheduler time to actually deliver the frame into our socket
     *       receive buffer.  Without this sleep the first {@code in.read()}
     *       call can time out while the bytes are still in transit from the
     *       server's send buffer.</li>
     *   <li>Read the 8-byte WRAPPER header with timeout
     *       {@value #DRAIN_TIMEOUT_MS} ms.</li>
     *   <li>If a header arrives, extract the payload length and read + discard
     *       the payload.</li>
     *   <li>If the read times out, no server-initiated frame was present.</li>
     * </ol>
     */
    private void drainServerInitiatedFrame() {
        // Step 1: give the server's scheduler time to send its initial frame.
        try {
            Thread.sleep(DRAIN_SLEEP_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Step 2: try to read the 8-byte header.
        byte[] header;
        try {
            header = transport.read(HEADER_SIZE, DRAIN_TIMEOUT_MS);
        } catch (TransportException e) {
            log.debug("WRAPPER drain: no server-initiated frame ({})", e.getMessage());
            return;
        }

        if (header.length == 0) {
            log.debug("WRAPPER drain: receive buffer empty — clean connection");
            return;
        }

        if (header.length < HEADER_SIZE) {
            log.debug("WRAPPER drain: partial header ({} bytes) — discarding",
                    header.length);
            return;
        }

        // Step 3: extract payload length and read the body.
        int payloadLen = ((header[6] & 0xFF) << 8) | (header[7] & 0xFF);
        log.debug("WRAPPER drain: discarding server-initiated frame, payload={} bytes",
                payloadLen);

        if (payloadLen > 0) {
            try {
                transport.read(payloadLen, DRAIN_TIMEOUT_MS);
            } catch (TransportException e) {
                log.debug("WRAPPER drain: failed to read payload body ({})",
                        e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Frame building / parsing
    // -------------------------------------------------------------------------

    /**
     * Builds a complete WRAPPER frame:
     * {@code version(2) | srcWPort(2) | dstWPort(2) | length(2) | apdu(n)}
     */
    byte[] buildFrame(byte[] apdu) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(HEADER_SIZE + apdu.length);
        out.write((VERSION     >> 8) & 0xFF);
        out.write( VERSION           & 0xFF);
        out.write((clientWPort >> 8) & 0xFF);
        out.write( clientWPort       & 0xFF);
        out.write((serverWPort >> 8) & 0xFF);
        out.write( serverWPort       & 0xFF);
        out.write((apdu.length >> 8) & 0xFF);
        out.write( apdu.length       & 0xFF);
        out.write(apdu, 0, apdu.length);
        return out.toByteArray();
    }

    /**
     * Reads one complete WRAPPER response frame and returns the APDU payload.
     */
    private byte[] readResponse() throws CosemSessionException {
        byte[] header = readExactly(HEADER_SIZE);

        int version = ((header[0] & 0xFF) << 8) | (header[1] & 0xFF);
        if (version != VERSION) {
            throw new CosemSessionException(String.format(
                    "WRAPPER: unexpected version 0x%04X (expected 0x%04X)",
                    version, VERSION));
        }

        int srcWPort = ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
        int dstWPort = ((header[4] & 0xFF) << 8) | (header[5] & 0xFF);

        if (srcWPort != serverWPort)
            log.warn("WRAPPER: unexpected source wPort {} (expected {})", srcWPort, serverWPort);
        if (dstWPort != clientWPort)
            log.warn("WRAPPER: unexpected dest wPort {} (expected {})", dstWPort, clientWPort);

        int payloadLength = ((header[6] & 0xFF) << 8) | (header[7] & 0xFF);
        if (payloadLength == 0) return new byte[0];

        byte[] payload = readExactly(payloadLength);
        log.trace("WRAPPER RX ← length: {}, bytes: {}", payloadLength, hex(payload));
        return payload;
    }

    /**
     * Reads exactly {@code count} bytes, accumulating across multiple TCP reads
     * to handle fragmentation.
     */
    private byte[] readExactly(int count) throws CosemSessionException {
        ByteArrayOutputStream accumulator = new ByteArrayOutputStream(count);
        int remaining = count;
        while (remaining > 0) {
            byte[] chunk;
            try {
                chunk = transport.read(Math.min(remaining, READ_CHUNK), timeoutMs);
            } catch (TransportException e) {
                throw new CosemSessionException("WRAPPER: read error", e);
            }
            if (chunk.length == 0) {
                throw new CosemSessionException(
                        "WRAPPER: timeout — need " + remaining + " more bytes, got 0");
            }
            accumulator.write(chunk, 0, chunk.length);
            remaining -= chunk.length;
        }
        return accumulator.toByteArray();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void assertConnected() throws CosemSessionException {
        if (!isConnected()) {
            throw new CosemSessionException(
                    "WRAPPER session not connected. Call connect() first.");
        }
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 3);
        for (byte b : data) sb.append(String.format("%02X ", b));
        return sb.toString().trim();
    }
}