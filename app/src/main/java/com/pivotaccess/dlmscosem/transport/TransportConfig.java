package com.pivotaccess.dlmscosem.transport;

/**
 * Immutable configuration for a transport channel.
 *
 * <p>Use the static factory methods {@link #rs485} and {@link #tcp} to
 * construct instances, then optionally refine with the wither methods.
 *
 * <pre>{@code
 * // RS-485 — always HDLC framing
 * TransportConfig cfg = TransportConfig.rs485("/dev/ttyUSB0", 9600)
 *         .withReadTimeoutMs(5000)
 *         .withRs485RtsDelayMs(2);
 *
 * // TCP + HDLC
 * TransportConfig cfg = TransportConfig.tcp("localhost", 4059);
 *
 * // TCP + WRAPPER
 * TransportConfig cfg = TransportConfig.tcp("localhost", 4059, FramingMode.WRAPPER);
 * }</pre>
 */
public final class TransportConfig {

    // -------------------------------------------------------------------------
    // Enums
    // -------------------------------------------------------------------------

    /** Transport medium type. */
    public enum Type { RS485, TCP }

    /**
     * Framing mode applied on top of a TCP connection.
     *
     * <ul>
     *   <li>{@link #HDLC}    — full HDLC link layer (SNRM/UA/I-frames) tunnelled over
     *       TCP.
     *   <li>{@link #WRAPPER} — IEC 62056-47 WRAPPER: 8-byte header wraps each APDU
     *       directly over TCP.
     * </ul>
     *
     * <p>RS-485 transports always use HDLC — this setting only applies to TCP.
     */
    public enum FramingMode { HDLC, WRAPPER }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final Type        type;
    private final int         readTimeoutMs;

    // RS-485 specific
    private final String portName;
    private final int    baudRate;
    private final int    dataBits;
    private final int    stopBits;
    private final int    parity;
    private final int    rtsDelayMs;

    // TCP specific
    private final String      host;
    private final int         tcpPort;
    private final int         connectTimeoutMs;
    private final FramingMode framingMode;

    // -------------------------------------------------------------------------
    // Private constructor
    // -------------------------------------------------------------------------

    private TransportConfig(
            Type type, int readTimeoutMs,
            String portName, int baudRate, int dataBits, int stopBits,
            int parity, int rtsDelayMs,
            String host, int tcpPort, int connectTimeoutMs,
            FramingMode framingMode) {
        this.type             = type;
        this.readTimeoutMs    = readTimeoutMs;
        this.portName         = portName;
        this.baudRate         = baudRate;
        this.dataBits         = dataBits;
        this.stopBits         = stopBits;
        this.parity           = parity;
        this.rtsDelayMs       = rtsDelayMs;
        this.host             = host;
        this.tcpPort          = tcpPort;
        this.connectTimeoutMs = connectTimeoutMs;
        this.framingMode      = framingMode;
    }

    // -------------------------------------------------------------------------
    // Static factories
    // -------------------------------------------------------------------------

    /**
     * Creates an RS-485 config.
     *
     * <p>Defaults: 8 data bits, 1 stop bit, no parity, 5 000 ms read timeout,
     * 2 ms RTS delay. RS-485 always uses HDLC framing.
     *
     * @param portName serial port (e.g. {@code "/dev/ttyUSB0"} or {@code "COM3"})
     * @param baudRate baud rate (e.g. 9600)
     */
    public static TransportConfig rs485(String portName, int baudRate) {
        return new TransportConfig(
                Type.RS485, 5_000,
                portName, baudRate, 8, 1, 0, 2,
                null, 0, 0,
                FramingMode.HDLC);
    }

    /**
     * Creates a TCP config with HDLC framing (default).
     *
     * <p>Use when connecting to a meter without wrapper mode.
     *
     * @param host    meter IP address or hostname
     * @param tcpPort TCP port (standard DLMS port is 4059)
     */
    public static TransportConfig tcp(String host, int tcpPort) {
        return tcp(host, tcpPort, FramingMode.HDLC);
    }

    /**
     * Creates a TCP config with an explicit framing mode.
     *
     * <pre>{@code
     * TCP/HDLC
     * TransportConfig.tcp("localhost", 4059, FramingMode.HDLC);
     *
     * TCP/WRAPPER
     * TransportConfig.tcp("localhost", 4059, FramingMode.WRAPPER);
     * }</pre>
     *
     * @param host        meter IP address or hostname
     * @param tcpPort     TCP port
     * @param framingMode {@link FramingMode#HDLC} or {@link FramingMode#WRAPPER}
     */
    public static TransportConfig tcp(String host, int tcpPort, FramingMode framingMode) {
        return new TransportConfig(
                Type.TCP, 10_000,
                null, 0, 0, 0, 0, 0,
                host, tcpPort, 5_000,
                framingMode);
    }

    // -------------------------------------------------------------------------
    // Withers (return new instance — immutable)
    // -------------------------------------------------------------------------

    public TransportConfig withReadTimeoutMs(int readTimeoutMs) {
        return new TransportConfig(type, readTimeoutMs,
                portName, baudRate, dataBits, stopBits, parity, rtsDelayMs,
                host, tcpPort, connectTimeoutMs, framingMode);
    }

    public TransportConfig withRs485RtsDelayMs(int rtsDelayMs) {
        return new TransportConfig(type, readTimeoutMs,
                portName, baudRate, dataBits, stopBits, parity, rtsDelayMs,
                host, tcpPort, connectTimeoutMs, framingMode);
    }

    public TransportConfig withConnectTimeoutMs(int connectTimeoutMs) {
        return new TransportConfig(type, readTimeoutMs,
                portName, baudRate, dataBits, stopBits, parity, rtsDelayMs,
                host, tcpPort, connectTimeoutMs, framingMode);
    }

    public TransportConfig withFramingMode(FramingMode framingMode) {
        return new TransportConfig(type, readTimeoutMs,
                portName, baudRate, dataBits, stopBits, parity, rtsDelayMs,
                host, tcpPort, connectTimeoutMs, framingMode);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /** Transport medium: RS-485 or TCP. */
    public Type        getType()             { return type; }

    /** Read timeout in milliseconds. */
    public int         getReadTimeoutMs()    { return readTimeoutMs; }

    /**
     * Framing mode: HDLC or WRAPPER.
     *
     * <p>RS-485 always returns {@link FramingMode#HDLC}.
     * TCP returns whichever mode was specified at construction.
     */
    public FramingMode getFramingMode()      { return framingMode; }

    // RS-485 accessors
    public String getPortName()              { return portName; }
    public int    getBaudRate()              { return baudRate; }
    public int    getDataBits()              { return dataBits; }
    public int    getStopBits()              { return stopBits; }
    public int    getParity()                { return parity; }
    public int    getRtsDelayMs()            { return rtsDelayMs; }

    // TCP accessors
    public String getHost()                  { return host; }
    public int    getTcpPort()               { return tcpPort; }
    public int    getConnectTimeoutMs()      { return connectTimeoutMs; }

    // -------------------------------------------------------------------------
    // Convenience checks
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} when this is a TCP connection using WRAPPER framing.
     *
     * <p>Used by {@link com.pivotaccess.dlmscosem.cosem.CosemClient} to decide
     * whether to create a
     * {@link com.pivotaccess.dlmscosem.wrapper.WrapperSession} or an
     * {@link com.pivotaccess.dlmscosem.session.HDLCSessionAdapter}.
     */
    public boolean isWrapper() {
        return type == Type.TCP && framingMode == FramingMode.WRAPPER;
    }

    /**
     * Returns {@code true} when HDLC framing is in use (RS-485 or TCP+HDLC).
     */
    public boolean isHdlc() {
        return framingMode == FramingMode.HDLC;
    }

    @Override
    public String toString() {
        if (type == Type.RS485) {
            return portName + "@" + baudRate + " [HDLC]";
        }
        return host + ":" + tcpPort + " [" + framingMode + "]";
    }
}