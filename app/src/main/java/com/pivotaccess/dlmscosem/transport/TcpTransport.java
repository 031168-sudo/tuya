package com.pivotaccess.dlmscosem.transport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;

/**
 * TCP transport for DLMS/COSEM over IP networks (WRAPPER mode).
 *
 * <p>DLMS WRAPPER framing (IEC 62056-47) wraps APDUs in a simple 8-byte
 * header instead of HDLC. This transport handles only the raw byte stream —
 * WRAPPER framing is handled by the session layer above.
 *
 * <p>The standard DLMS TCP port is {@code 4059}.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * TransportConfig cfg = TransportConfig.tcp("192.168.1.100", 4059)
 *         .withReadTimeoutMs(10_000)
 *         .withConnectTimeoutMs(5_000);
 *
 * try (Transport t = new TcpTransport(cfg)) {
 *     t.open();
 *     t.write(apduBytes);
 *     byte[] response = t.read(1024, 5000);
 * }
 * }</pre>
 */
public class TcpTransport implements Transport {

    private static final Logger log = LoggerFactory.getLogger(TcpTransport.class);

    /** Standard DLMS TCP port as per IEC 62056-47. */
    public static final int DEFAULT_DLMS_PORT = 4059;

    private final TransportConfig config;
    private Socket                socket;
    private InputStream           in;
    private OutputStream          out;

    public TcpTransport(TransportConfig config) {
        if (config.getType() != TransportConfig.Type.TCP) {
            throw new IllegalArgumentException(
                    "TransportConfig type must be TCP, got: " + config.getType());
        }
        this.config = config;
    }

    // -------------------------------------------------------------------------
    // Transport
    // -------------------------------------------------------------------------

    @Override
    public void open() throws TransportException {
        if (isOpen()) {
            log.warn("Transport already open: {}", describe());
            return;
        }

        log.debug("Connecting TCP transport: {}", describe());

        socket = new Socket();
        try {
            socket.connect(
                    new InetSocketAddress(config.getHost(), config.getTcpPort()),
                    config.getConnectTimeoutMs());

            socket.setSoTimeout(config.getReadTimeoutMs());
            socket.setTcpNoDelay(true);     // disable Nagle — metering is low-volume
            socket.setKeepAlive(true);

            in  = socket.getInputStream();
            out = socket.getOutputStream();

        } catch (IOException e) {
            close();
            throw new TransportException(
                    "Failed to connect to " + describe(), e);
        }

        log.debug("TCP transport connected: {}", describe());
    }

    @Override
    public void close() {
        in  = null;
        out = null;
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) {}
            socket = null;
            log.debug("TCP transport closed: {}", describe());
        }
    }

    @Override
    public void write(byte[] data) throws TransportException {
        assertOpen();

        if (log.isTraceEnabled()) {
            log.trace("TX [{}] → {} bytes: {}", describe(), data.length, hex(data));
        }

        try {
            out.write(data);
            out.flush();
        } catch (IOException e) {
            throw new TransportException(
                    "Write failed on " + describe(), e);
        }
    }

    @Override
    public byte[] read(int maxBytes, int timeoutMs) throws TransportException {
        assertOpen();

        try {
            // Adjust socket read timeout if different from the current setting
            socket.setSoTimeout(timeoutMs);

            byte[] buffer = new byte[maxBytes];

            // Natively blocks until at least 1 byte is available or timeout hits.
            int bytesRead = in.read(buffer, 0, maxBytes);

            if (bytesRead == -1) {
                // Remote closed connection
                throw new TransportException(
                        "Connection closed by remote: " + describe());
            }

            byte[] result = Arrays.copyOf(buffer, bytesRead);

            if (log.isTraceEnabled() && bytesRead > 0) {
                log.trace("RX [{}] ← {} bytes: {}", describe(), bytesRead, hex(result));
            }

            return result;

        } catch (java.net.SocketTimeoutException e) {
            log.debug("Read timeout on: {}", describe());
            return new byte[0];
        } catch (IOException e) {
            throw new TransportException("Read failed on " + describe(), e);
        }
    }

    @Override
    public boolean isOpen() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    @Override
    public String describe() {
        return config.toString();   // e.g. "192.168.1.100:4059"
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void assertOpen() throws TransportException {
        if (!isOpen()) {
            throw new TransportException("Transport is not open: " + describe());
        }
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 3);
        for (byte b : data) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }
}