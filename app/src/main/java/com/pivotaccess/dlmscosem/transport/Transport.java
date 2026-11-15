package com.pivotaccess.dlmscosem.transport;

/**
 * Abstraction over a physical or network communication channel.
 *
 * <p>Implementations must be thread-safe with respect to open/close, but
 * read/write are expected to be called from a single thread per session
 * (the HDLC layer above serializes access per bus).
 */
public interface Transport extends AutoCloseable {

    /**
     * Opens the communication channel.
     *
     * @throws TransportException if the port/socket cannot be opened
     */
    void open() throws TransportException;

    /**
     * Closes the communication channel, releasing all resources.
     * Calling close on an already-closed transport is a no-op.
     */
    @Override
    void close();

    /**
     * Writes all bytes in {@code data} to the channel.
     *
     * @param data bytes to send
     * @throws TransportException if the write fails or the channel is not open
     */
    void write(byte[] data) throws TransportException;

    /**
     * Reads up to {@code maxBytes} from the channel, blocking until at least
     * one byte is available or {@code timeoutMs} elapses.
     *
     * @param maxBytes  maximum number of bytes to read
     * @param timeoutMs read timeout in milliseconds; 0 means block indefinitely
     * @return bytes read — may be fewer than {@code maxBytes}; never null;
     *         empty array indicates timeout with no data received
     * @throws TransportException if a read error occurs or the channel is not open
     */
    byte[] read(int maxBytes, int timeoutMs) throws TransportException;

    /**
     * Returns {@code true} if the channel is currently open.
     */
    boolean isOpen();

    /**
     * Returns a human-readable description of this transport's target,
     * e.g. {@code "/dev/ttyUSB0@9600"} or {@code "192.168.1.100:4059"}.
     * Used in log messages and exceptions.
     */
    String describe();
}