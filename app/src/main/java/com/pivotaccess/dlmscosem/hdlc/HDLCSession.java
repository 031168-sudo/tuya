package com.pivotaccess.dlmscosem.hdlc;

import com.pivotaccess.dlmscosem.session.CosemSession;
import com.pivotaccess.dlmscosem.transport.Transport;
import com.pivotaccess.dlmscosem.transport.TransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * HDLC session state machine for DLMS/COSEM (IEC 62056-46).
 *
 * <h3>LLC sub-layer</h3>
 * <p>Per IEC 62056-46 Section 8.4, every APDU in an HDLC I-frame must be
 * preceded by a 3-byte LLC sub-layer header {@code E6 E6 00}:
 * <ul>
 *   <li>{@code 0xE6} — DSAP (Destination Service Access Point)</li>
 *   <li>{@code 0xE6} — SSAP (Source Service Access Point)</li>
 *   <li>{@code 0x00} — LLC Control (UI — Unnumbered Information)</li>
 * </ul>
 * Without this header the server receives the raw APDU bytes starting with
 * {@code 0x60} (AARQ tag) where it expects {@code 0xE6} — causing the
 * association to fail silently.
 *
 * <h3>Drain on connect</h3>
 * <p>Some meters push an unsolicited UA
 * frame immediately after the TCP connection is accepted. If that stale frame
 * is read as the SNRM response it supplies wrong negotiation parameters (e.g.
 * maxInfoTx=1), fragmenting the AARQ into single-byte I-frames and breaking
 * sequence numbering. {@link #drainServerInitiatedBytes()} discards stale
 * bytes before the SNRM is sent.
 *
 * <h3>Stop-and-wait</h3>
 * <p>Window size 1 — every I-frame waits for an acknowledgment before the
 * next is sent. The server may acknowledge via an explicit RR frame OR by
 * piggybacking N(R) on its own I-frame (the common case for AARQ → AARE).
 * Both paths are handled in {@link #sendAndAwaitAck}.
 */
public class HDLCSession implements CosemSession {

    private static final Logger log = LoggerFactory.getLogger(HDLCSession.class);

    private static final int MAX_READ_ATTEMPTS = 128;
    private static final int READ_BUFFER_SIZE  = 4096;
    private static final int DRAIN_SLEEP_MS    = 150;
    private static final int DRAIN_TIMEOUT_MS  = 500;

    // -------------------------------------------------------------------------
    // LLC sub-layer header (IEC 62056-46 Section 8.4)
    // -------------------------------------------------------------------------

    /**
     * DLMS/COSEM LLC PDU header, prepended to every APDU inside an I-frame.
     * <pre>
     * E6  ← DSAP (Destination Service Access Point)
     * E6  ← SSAP (Source Service Access Point)
     * 00  ← LLC Control (UI frame)
     * </pre>
     * Only used in HDLC sessions — WRAPPER sessions have no HDLC layer.
     */
    private static final byte[] LLC_HEADER = {(byte) 0xE6, (byte) 0xE6, 0x00};

    // -------------------------------------------------------------------------
    // Runtime state
    // -------------------------------------------------------------------------
    private final ByteArrayOutputStream leftoverBuffer = new ByteArrayOutputStream();

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------implements------------------------------------

    private final Transport                  transport;
    private final HDLCAddress                clientAddress;
    private final HDLCAddress                serverAddress;
    private final HDLCNegotiationParameters  proposedParams;
    private final int                        timeoutMs;
    private final int                        maxRetries;

    // -------------------------------------------------------------------------
    // Runtime state
    // -------------------------------------------------------------------------

    private HDLCSessionState          state             = HDLCSessionState.CLOSED;
    private HDLCNegotiationParameters negotiatedParams;
    private int                       sendSeq           = 0;
    private int                       recvSeq           = 0;
    private boolean                   strictSequenceControl;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    public HDLCSession(Transport transport,
                       HDLCAddress clientAddress,
                       HDLCAddress serverAddress,
                       HDLCNegotiationParameters params,
                       int timeoutMs,
                       int maxRetries,
                       boolean strictSequenceControl) {
        this.transport       = transport;
        this.clientAddress   = clientAddress;
        this.serverAddress   = serverAddress;
        this.proposedParams  = params;
        this.negotiatedParams = params;
        this.timeoutMs       = timeoutMs;
        this.maxRetries      = maxRetries;
        this.strictSequenceControl = strictSequenceControl;
    }

    public HDLCSession(Transport transport,
                       HDLCAddress clientAddress,
                       HDLCAddress serverAddress,
                       int timeoutMs,
                       int maxRetries, boolean strictSequenceControl) {
        this(transport, clientAddress, serverAddress,
                HDLCNegotiationParameters.defaults(), timeoutMs, maxRetries, strictSequenceControl);
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Blasts a DISC frame to wipe the meter's internal state, silently ignoring any response or timeout.
     */
    private void sendDiscQuietly() {
        try {
            writeFrame(HDLCFrame.disc(serverAddress, clientAddress));
            readNextFrame(); // Attempt to eat the UA acknowledgment if it sends one
        } catch (Exception ignored) {
            // Completely fine if this fails or times out. The goal was just to push the DISC byte sequence.
        }
    }

    /**
     * Opens the transport, drains any server-initiated bytes, then exchanges
     * SNRM / UA to establish the HDLC link.
     */
    public void connect() throws HDLCSessionException {
        assertState(HDLCSessionState.CLOSED);

        try {
            if (!transport.isOpen()) transport.open();
        } catch (TransportException e) {
            throw new HDLCSessionException("Failed to open transport", e);
        }

        // Drain any bytes the server pushed before we sent anything.
        // (The Meter sends an unsolicited UA on TCP accept.)
        drainServerInitiatedBytes();

        sendSeq = 0;
        recvSeq = 0;
        state   = HDLCSessionState.CONNECTING;

        log.debug("Sending SNRM to {}", serverAddress);
        HDLCFrame snrm = HDLCFrame.snrm(serverAddress, clientAddress,
                proposedParams.encode());
        log.debug("SNRM payload: {}", hex(snrm.encode()));
        HDLCFrame ua   = sendAndExpectUA(snrm);
        log.debug("UA response: {}, payload: {}", hex(ua.encode()), hex(ua.getPayload()));

        HDLCNegotiationParameters accepted =
                HDLCNegotiationParameters.decode(ua.getPayload());
        negotiatedParams = HDLCNegotiationParameters.negotiate(proposedParams, accepted);
        log.debug("HDLC link established with {} — negotiated: {}",
                serverAddress, negotiatedParams);

        state = HDLCSessionState.CONNECTED;

        // Short delay before sending the first I-frame — some meters (e.g. Elster A3) need it.
        // This helps ensure the SNRM/UA exchange is fully processed before we send the AARQ in the first I-frame.
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Closes the HDLC link (DISC → UA).
     * Always transitions to CLOSED, even if the exchange fails.
     */
    public void disconnect() {
        if (state == HDLCSessionState.CLOSED) return;
        state = HDLCSessionState.DISCONNECTING;
        log.debug("Sending DISC to {}", serverAddress);
        try {
            sendAndExpectUA(HDLCFrame.disc(serverAddress, clientAddress));
            log.debug("HDLC link disconnected from {}", serverAddress);
        } catch (HDLCSessionException e) {
            log.warn("DISC/UA exchange failed ({}), forcing close", e.getMessage());
        } finally {
            state = HDLCSessionState.CLOSED;
        }
    }

    // -------------------------------------------------------------------------
    // Data transfer
    // -------------------------------------------------------------------------

    /**
     * Sends {@code apdu} as one or more I-frames (with LLC header) and returns
     * the complete response APDU (LLC header stripped).
     *
     * <p>Stop-and-wait per frame: every I-frame waits for either an explicit RR
     * or the server's data I-frame (implicit ack + response combined) before
     * proceeding.
     */
    public byte[] sendReceive(byte[] apdu) throws HDLCSessionException {
        assertState(HDLCSessionState.CONNECTED);

        int maxPayload = negotiatedParams.getMaxInfoLengthTransmit();
        int offset     = 0;

        // IEC 62056-46: prepend LLC sub-layer header before framing the APDU
        byte[] payload = addLlcHeader(apdu);

        log.debug("Sending APDU ({} bytes + 3 LLC) to {}. bytes: {}", apdu.length, serverAddress, hex(apdu));

        ByteArrayOutputStream earlyResponse = new ByteArrayOutputStream();

        while (offset < payload.length) {
            int chunkSize = Math.min(payload.length - offset, maxPayload);
            boolean isLast = (offset + chunkSize) >= payload.length;
            byte[] chunk  = Arrays.copyOfRange(payload, offset, offset + chunkSize);

            HDLCFrame iFrame = HDLCFrame.iFrame(
                    serverAddress, clientAddress,
                    sendSeq, recvSeq,
                    true,     // P=1 (stop-and-wait: always poll)
                    !isLast,  // segmentation bit
                    chunk);

            byte[] peerData = sendAndAwaitAck(iFrame);
            sendSeq = (sendSeq + 1) % 8;
            offset += chunkSize;

            if (peerData != null) {
                // Server responded with an I-frame (implicit ack + response combined)
                earlyResponse.write(peerData, 0, peerData.length);
            }
        }

        // If the server already sent response data as part of its I-frame ack, use it.
        // Otherwise, read the response I-frame(s) now.
        byte[] raw = earlyResponse.size() > 0
                ? earlyResponse.toByteArray()
                : receiveApdu();

        return stripLlcHeader(raw);
    }

    // -------------------------------------------------------------------------
    // Internal: send I-frame and await acknowledgment
    // -------------------------------------------------------------------------

    /**
     * Sends an I-frame and waits for the peer's acknowledgment.
     *
     * <p>The peer may respond with:
     * <ul>
     *   <li>RR frame (explicit ack) → returns {@code null}</li>
     *   <li>I-frame (implicit ack + data) → returns the I-frame payload bytes</li>
     * </ul>
     */
    private byte[] sendAndAwaitAck(HDLCFrame frame) throws HDLCSessionException {
        HDLCSessionException lastEx = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                writeFrame(frame);
                HDLCFrame response = readNextFrame();

                log.debug("Received ACK response: {}", hex(response.encode()));

                switch (response.getType()) {
                    case RR:
                        recvSeq = response.getRecvSeq();
                        log.debug("RR received N(R)={}", recvSeq);
                        return null;

                    case RNR:
                        // Receiver Not Ready — wait and retry
                        log.debug("RNR received, retrying");
                        Thread.sleep(200);
                        continue;

                    case I:
                        // Implicit ack + response data
                        if (response.getSendSeq() != recvSeq) {
                            String errorMsg = String.format(
                                    "Unexpected I-frame N(S)=%d (expected %d)",
                                    response.getSendSeq(), recvSeq);

                            if (strictSequenceControl) {
                                throw new HDLCSessionException(errorMsg);
                            } else {
                                log.warn("Strict Sequence Disabled: Ignoring mismatch -> {}", errorMsg);
                            }
                        }
                        if (strictSequenceControl) {
                            recvSeq = (recvSeq + 1) % 8;
                        } else {
                            // Using the server's actual sequence if strict mode is off
                            recvSeq = (response.getSendSeq() + 1) % 8;
                        }
                        // Acknowledge the server's I-frame
                        writeFrame(HDLCFrame.rr(serverAddress, clientAddress,
                                recvSeq, false));
                        return response.getPayload();

                    case DM:
                        throw new HDLCSessionException(
                                "Meter responded with DM (Disconnected Mode)");

                    default:
                        throw new HDLCSessionException(
                                "Unexpected frame: " + response.getType());
                }
            } catch (HDLCSessionException e) {
                lastEx = e;
                if (attempt < maxRetries) {
                    log.warn("Attempt {}/{} failed: {} — retrying",
                            attempt + 1, maxRetries + 1, e.getMessage());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new HDLCSessionException("Interrupted while waiting for ack", e);
            }
        }
        throw new HDLCSessionException(
                "No valid ack after " + (maxRetries + 1) + " attempts", lastEx);
    }

    // -------------------------------------------------------------------------
    // Internal: receive I-frame(s) from the peer
    // -------------------------------------------------------------------------

    /**
     * Reads one or more I-frames from the peer, sending RR after each, and
     * returns the reassembled info field bytes (still includes LLC header).
     * Non-data frames (stray RR/RNR) are silently skipped.
     */
    private byte[] receiveApdu() throws HDLCSessionException {
        ByteArrayOutputStream responseBuffer = new ByteArrayOutputStream();

        while (true) {
            HDLCFrame frame = readNextFrame();

            if (frame.getType() == HDLCFrameType.I) {
                // We got an Information frame! Update our reception sequence counter.
                recvSeq = (frame.getSendSeq() + 1) % 8;

                responseBuffer.write(frame.getPayload(), 0, frame.getPayload().length);

                if (frame.isSegmented()) {
                    // The meter has more segments to send. Poll for the next one.
                    log.debug("Segment received. Polling for next segment...");
                    sendRrPoll();
                } else {
                    // Final segment received. Return the complete APDU payload.
                    return responseBuffer.toByteArray();
                }
            }
            else if (frame.getType() == HDLCFrameType.RR) {
                // FLOW CONTROL: The meter acknowledged our previous frame,
                // but its data is not ready yet. We must poll it.
                log.debug("Meter sent RR (data not ready). Polling...");

                // Add a tiny sleep to prevent flooding the RS-485 bus / Meter MCU
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

                sendRrPoll();
            }
            else {
                throw new HDLCSessionException("Unexpected frame type during APDU receive: " + frame.getType());
            }
        }
    }

    /**
     * Sends an RR (Receive Ready) S-frame to poll the meter for data.
     */
    private void sendRrPoll() throws HDLCSessionException {
        // N(R) must be the sequence number we expect to receive next.
        // The Poll/Final bit (P/F) MUST be true to force the meter to reply.
        HDLCFrame rrFrame = new HDLCFrame(
                HDLCFrameType.RR,
                serverAddress,    // destination
                clientAddress,    // source
                0,                // sendSeq (ignored for S-frames)
                recvSeq,          // recvSeq (what we expect next)
                true,             // poll = true
                false,            // segmented = false
                new byte[0]       // empty payload
        );

        try {
            transport.write(rrFrame.encode());
        } catch (TransportException e) {
            throw new HDLCSessionException("Failed to send RR poll", e);
        }
    }

    // -------------------------------------------------------------------------
    // Internal: SNRM/DISC handshake helper
    // -------------------------------------------------------------------------

    private HDLCFrame sendAndExpectUA(HDLCFrame frame)
            throws HDLCSessionException {
        HDLCSessionException lastEx = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                writeFrame(frame);
                HDLCFrame response = readNextFrame();

                // Force reset maneuver: if we expected UA but got RR/I, the meter is confused and thinks we're already connected. Send DISC to reset it and try again.
                if (response.getType() == HDLCFrameType.RR || response.getType() == HDLCFrameType.I) {
                    log.debug("State mismatch. Forcing reset...");
                    sendDiscQuietly();
                    // Loop around to immediately re-send the SNRM
                    continue;
                }

                if (response.getType() == HDLCFrameType.UA) return response;
                if (response.getType() == HDLCFrameType.DM) {
                    throw new HDLCSessionException("Meter responded with DM");
                }
                throw new HDLCSessionException(String.format(
                        "Expected %s, got %s", HDLCFrameType.UA, response.getType()));

            } catch (HDLCSessionException e) {
                lastEx = e;
                if (attempt < maxRetries) {
                    log.debug("Attempt {}/{} failed: {} — retrying", attempt + 1, maxRetries + 1, e.getMessage());
                }
            }
        }
        throw new HDLCSessionException(
                "No valid response after " + (maxRetries + 1) + " attempts", lastEx);
    }

    // -------------------------------------------------------------------------
    // Internal: raw frame I/O
    // -------------------------------------------------------------------------

    private void writeFrame(HDLCFrame frame) throws HDLCSessionException {
        byte[] encoded = frame.encode();
        log.trace("→ {}", frame);
        try {
            transport.write(encoded);
        } catch (TransportException e) {
            throw new HDLCSessionException("Failed to write frame", e);
        }
    }

    /**
     * Reads raw bytes until a complete HDLC frame (opening flag → closing flag
     * after ≥5 bytes) is assembled, then decodes and returns it.
     */
    private HDLCFrame readNextFrame() throws HDLCSessionException {
        ByteArrayOutputStream frameBuf = new ByteArrayOutputStream();
        boolean openFlagSeen = false;

        for (int attempt = 0; attempt < MAX_READ_ATTEMPTS; attempt++) {
            byte[] chunk;

            // 1. Prioritize bytes left over from the previous read
            if (leftoverBuffer.size() > 0) {
                chunk = leftoverBuffer.toByteArray();
                leftoverBuffer.reset();
            } else {
                // 2. Only read from transport if we have no leftover bytes
                try {
                    chunk = transport.read(READ_BUFFER_SIZE, timeoutMs);
                } catch (TransportException e) {
                    throw new HDLCSessionException("Transport read error", e);
                }

                if (chunk.length == 0) {
                    if (frameBuf.size() == 0) {
                        throw new HDLCSessionException(
                                "Timeout: no response from meter at " + serverAddress
                                        + " after " + timeoutMs + "ms");
                    }
                    continue; // partial frame — keep reading
                }
            }

            // 3. Process the chunk byte by byte
            for (int i = 0; i < chunk.length; i++) {
                int v = chunk[i] & 0xFF;

                if (!openFlagSeen) {
                    if (v == 0x7E) {
                        openFlagSeen = true;
                        frameBuf.write(v);
                    }
                } else {
                    frameBuf.write(v);
                    if (v == 0x7E && frameBuf.size() > 5) {

                        // FRAME COMPLETE! Save any remaining bytes for the NEXT call
                        if (i + 1 < chunk.length) {
                            leftoverBuffer.write(chunk, i + 1, chunk.length - (i + 1));
                        }

                        byte[] raw = frameBuf.toByteArray();
                        try {
                            HDLCFrame frame = HDLCFrame.decode(raw);
                            log.trace("← {}", frame);
                            return frame;
                        } catch (HDLCFrameException e) {
                            throw new HDLCSessionException(
                                    "Frame decode failed: " + e.getMessage(), e);
                        }
                    }
                }
            }
        }
        throw new HDLCSessionException(
                "Failed to read a complete HDLC frame after "
                        + MAX_READ_ATTEMPTS + " read attempts");
    }

    // -------------------------------------------------------------------------
    // Internal: drain server-initiated bytes before SNRM
    // -------------------------------------------------------------------------

    /**
     * Discards any bytes pushed by the server before we sent anything.
     * Uses a short timeout — if no bytes arrive we proceed normally.
     */
    private void drainServerInitiatedBytes() {
        try {
            Thread.sleep(DRAIN_SLEEP_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            byte[] stale = transport.read(READ_BUFFER_SIZE, DRAIN_TIMEOUT_MS);
            if (stale.length > 0) {
                log.debug("HDLC: drained {} server-initiated bytes before SNRM",
                        stale.length);
            }
        } catch (TransportException ignored) {
            // Timeout = nothing to drain — normal for most meters
        }
    }

    // -------------------------------------------------------------------------
    // LLC helpers
    // -------------------------------------------------------------------------

    /**
     * Prepends the 3-byte LLC sub-layer header {@code E6 E6 00} to
     * {@code apdu}.
     */
    private static byte[] addLlcHeader(byte[] apdu) {
        byte[] out = new byte[LLC_HEADER.length + apdu.length];
        System.arraycopy(LLC_HEADER, 0, out, 0, LLC_HEADER.length);
        System.arraycopy(apdu, 0, out, LLC_HEADER.length, apdu.length);
        return out;
    }

    /**
     * Strips the 3-byte LLC sub-layer header from info if present,
     * and returns the raw APDU bytes.
     * * Handles both Command (E6 E6 00) and Response (E6 E7 00) SSAP variants.
     */
    private static byte[] stripLlcHeader(byte[] info) {
        if (info.length >= 3
                && (info[0] & 0xFF) == 0xE6
                // Allow both 0xE6 (Command) and 0xE7 (Response with C/R bit set)
                && ((info[1] & 0xFF) == 0xE6 || (info[1] & 0xFF) == 0xE7)
                && (info[2] & 0xFF) == 0x00) {
            return Arrays.copyOfRange(info, 3, info.length);
        }
        log.warn("HDLC: received I-frame without LLC header E6 E6 00 / E6 E7 00 — passing raw bytes");
        return info;
    }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    public HDLCSessionState          getState()            { return state; }
    public boolean                   isConnected()         { return state == HDLCSessionState.CONNECTED; }
    public HDLCNegotiationParameters getNegotiatedParams() { return negotiatedParams; }

    private void assertState(HDLCSessionState required) throws HDLCSessionException {
        if (state != required) {
            throw new HDLCSessionException(
                    "Invalid state: expected " + required + ", current " + state);
        }
    }

    private static String hex(byte[] data) {
        if (data == null) return "null";
        StringBuilder sb = new StringBuilder(data.length * 3);
        for (byte b : data) sb.append(String.format("%02X ", b));
        return sb.toString().trim();
    }
}