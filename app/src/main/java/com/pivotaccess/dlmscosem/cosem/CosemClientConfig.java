package com.pivotaccess.dlmscosem.cosem;

import com.pivotaccess.dlmscosem.util.MeterAddress;
import com.pivotaccess.dlmscosem.apdu.AarqApdu;
import com.pivotaccess.dlmscosem.hdlc.HDLCNegotiationParameters;
import com.pivotaccess.dlmscosem.transport.TransportConfig;

/**
 * Immutable configuration for a {@link CosemClient} connection to a single meter.
 *
 * <p>The framing mode (HDLC or WRAPPER) is determined by {@link TransportConfig}.
 * Both {@code serverAddress} and {@code clientAddress} accept a {@link MeterAddress},
 * which carries either an HDLC address or a WRAPPER wPort — use the matching
 * factory on {@link MeterAddress} for your framing mode.
 *
 * <h3>RS-485 (always HDLC)</h3>
 * <pre>{@code
 * CosemClientConfig config = CosemClientConfig.builder()
 *         .transport(TransportConfig.rs485("/dev/ttyUSB0", 9600))
 *         .serverAddress(MeterAddress.hdlcServer(1, 3))
 *         .clientAddress(MeterAddress.hdlcClient(16))
 *         .build();
 * }</pre>
 *
 * <h3>TCP + HDLC</h3>
 * <pre>{@code
 * CosemClientConfig config = CosemClientConfig.builder()
 *         .transport(TransportConfig.tcp("localhost", 4059))
 *         .serverAddress(MeterAddress.hdlcServer(1, 1))
 *         .clientAddress(MeterAddress.hdlcClient(16))
 *         .build();
 * }</pre>
 *
 * <h3>TCP + WRAPPER</h3>
 * <pre>{@code
 * CosemClientConfig config = CosemClientConfig.builder()
 *         .transport(TransportConfig.tcp("localhost", 4059, FramingMode.WRAPPER))
 *         .serverAddress(MeterAddress.wrapperServer(1))
 *         .clientAddress(MeterAddress.wrapperClient(16))
 *         .build();
 * }</pre>
 */
public final class CosemClientConfig {

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final TransportConfig              transportConfig;
    private final MeterAddress                 serverAddress;
    private final MeterAddress                 clientAddress;
    private final HDLCNegotiationParameters    hdlcParams;
    private final AarqApdu.AuthenticationLevel authLevel;
    private final byte[]                       authValue;
    private final int                          timeoutMs;
    private final int                          maxRetries;
    private final int                          invokeIdSeed;
    /**
     * Whether to enable strict HDLC FRAME sequence control (reject out-of-order frames).
     * Only relevant for HDLC sessions.
     * <br/>
     * Default: true.
     */
    private final boolean                      strictSequenceControl;

    /**
     * The {@code client-max-receive-pdu-size} sent in the AARQ xDLMS
     * InitiateRequest. This tells the server the largest single APDU payload
     * this client can receive.
     *
     * <p>The negotiated limit that the client must apply when sending to the
     * server is {@code server-max-receive-pdu-size} from the AARE — see
     * {@link com.pivotaccess.dlmscosem.apdu.AareApdu#getServerMaxReceivePduSize()}.
     *
     * <p>Default: {@link com.pivotaccess.dlmscosem.apdu.AarqApdu#DEFAULT_MAX_RECEIVE_PDU_SIZE}
     * (65535).
     */
    private final int                          maxReceivePduSize;

    // -------------------------------------------------------------------------
    // Private constructor
    // -------------------------------------------------------------------------

    private CosemClientConfig(Builder b) {
        this.transportConfig  = b.transportConfig;
        this.serverAddress    = b.serverAddress;
        this.clientAddress    = b.clientAddress;
        this.hdlcParams       = b.hdlcParams;
        this.authLevel        = b.authLevel;
        this.authValue        = b.authValue;
        this.timeoutMs        = b.timeoutMs;
        this.maxRetries       = b.maxRetries;
        this.invokeIdSeed     = b.invokeIdSeed;
        this.maxReceivePduSize = b.maxReceivePduSize;
        this.strictSequenceControl = b.strictSequenceControl;
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public TransportConfig              getTransportConfig()  { return transportConfig; }
    public MeterAddress                 getServerAddress()    { return serverAddress; }
    public MeterAddress                 getClientAddress()    { return clientAddress; }
    public HDLCNegotiationParameters    getHdlcParams()       { return hdlcParams; }
    public AarqApdu.AuthenticationLevel getAuthLevel()        { return authLevel; }
    public byte[]                       getAuthValue()        { return authValue; }
    public int                          getTimeoutMs()        { return timeoutMs; }
    public int                          getMaxRetries()       { return maxRetries; }
    public int                          getInvokeIdSeed()     { return invokeIdSeed; }
    /**
     * Whether strict HDLC FRAME sequence control (reject out-of-order frames) is enabled.
     * Only relevant for HDLC sessions.
     * <br/>
     * Default: true.
     */
    public boolean                      isStrictSequenceControlEnabled() { return strictSequenceControl; }

    /**
     * The client-max-receive-pdu-size sent in the AARQ (what we claim we can receive).
     * The server's own limit is read from the AARE and stored at runtime by
     * {@link CosemClient}.
     */
    public int                          getMaxReceivePduSize(){ return maxReceivePduSize; }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    public static Builder builder() { return new Builder(); }

    public static final class Builder {

        private TransportConfig              transportConfig;
        private MeterAddress                 serverAddress;
        private MeterAddress                 clientAddress  = MeterAddress.hdlcClient(16);
        private HDLCNegotiationParameters    hdlcParams     = HDLCNegotiationParameters.defaults();
        private AarqApdu.AuthenticationLevel authLevel      = AarqApdu.AuthenticationLevel.NONE;
        private byte[]                       authValue      = null;
        private int                          timeoutMs         = 10_000;
        private int                          maxRetries        = 3;
        private int                          invokeIdSeed      = 1;
        private int                          maxReceivePduSize = AarqApdu.DEFAULT_MAX_RECEIVE_PDU_SIZE;
        private boolean                      strictSequenceControl = true;

        private Builder() {}

        /** Sets the transport (RS-485 or TCP). Required. */
        public Builder transport(TransportConfig transportConfig) {
            this.transportConfig = transportConfig;
            return this;
        }

        /**
         * Sets the server (meter) address.
         *
         * <p>Use {@link MeterAddress#hdlcServer(int, int)} /
         * {@link MeterAddress#hdlcServer(int)} for HDLC, or
         * {@link MeterAddress#wrapperServer(int)} for WRAPPER.
         * Required.
         */
        public Builder serverAddress(MeterAddress serverAddress) {
            this.serverAddress = serverAddress;
            return this;
        }

        /**
         * Sets the client address.
         *
         * <p>Use {@link MeterAddress#hdlcClient(int)} for HDLC, or
         * {@link MeterAddress#wrapperClient(int)} for WRAPPER.
         * Default: {@code MeterAddress.hdlcClient(16)}.
         */
        public Builder clientAddress(MeterAddress clientAddress) {
            this.clientAddress = clientAddress;
            return this;
        }

        /**
         * Sets HDLC link negotiation parameters (max frame size, window size).
         * Only relevant for HDLC sessions.
         * Default: {@link HDLCNegotiationParameters#defaults()}.
         */
        public Builder hdlcParams(HDLCNegotiationParameters hdlcParams) {
            this.hdlcParams = hdlcParams;
            return this;
        }

        /**
         * Sets authentication level and credential.
         *
         * @param level     {@code NONE}, {@code LOW} (password), or {@code HIGH} (GMAC)
         * @param authValue password bytes for LOW; client challenge for HIGH
         */
        public Builder authentication(AarqApdu.AuthenticationLevel level,
                                      byte[] authValue) {
            this.authLevel  = level;
            this.authValue  = authValue;
            return this;
        }

        /** Response timeout in milliseconds. Default: 10 000. */
        public Builder timeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
            return this;
        }

        /** Retransmission attempts on timeout (HDLC only). Default: 3. */
        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        /** Starting invoke-id for APDU requests (1–15). Default: 1. */
        public Builder invokeIdSeed(int invokeIdSeed) {
            this.invokeIdSeed = invokeIdSeed;
            return this;
        }

        /**
         * Sets the {@code client-max-receive-pdu-size} sent in the AARQ.
         *
         * <p>This tells the server the maximum APDU payload size the client can
         * handle per response.  The server's own limit (from the AARE) may be
         * smaller and is used automatically by {@link CosemClient} to decide
         * whether multi-block GET is needed.
         *
         * <p>Default: 65535 (the maximum allowed by the DLMS standard).
         *
         * @param maxReceivePduSize max APDU payload in bytes (1–65535)
         */
        public Builder maxReceivePduSize(int maxReceivePduSize) {
            this.maxReceivePduSize = Math.max(1, Math.min(maxReceivePduSize, 0xFFFF));
            return this;
        }

        /**
         * Whether strict HDLC FRAME sequence control (reject out-of-order frames) is enabled.
         * Only relevant for HDLC sessions.
         * <br/>
         * Default: true.
         */
        public Builder enableStrictSequenceControl(boolean enabled) {
            this.strictSequenceControl = enabled;
            return this;
        }

        /**
         * Validates and builds the config.
         *
         * @throws IllegalStateException if required fields are missing or the
         *         address mode does not match the transport framing mode
         */
        public CosemClientConfig build() {
            if (transportConfig == null) {
                throw new IllegalStateException("transportConfig is required");
            }
            if (serverAddress == null) {
                throw new IllegalStateException("serverAddress is required");
            }
            if (authLevel != AarqApdu.AuthenticationLevel.NONE && authValue == null) {
                throw new IllegalStateException(
                        "authValue is required when authLevel is not NONE");
            }

            // Enforce address mode <-> framing mode consistency at build time
            boolean wrapperTransport = transportConfig.isWrapper();
            if (wrapperTransport && serverAddress.isHdlc()) {
                throw new IllegalStateException(
                        "WRAPPER transport requires MeterAddress.wrapperServer(...). "
                                + "Got: " + serverAddress);
            }
            if (!wrapperTransport && serverAddress.isWrapper()) {
                throw new IllegalStateException(
                        "HDLC transport requires MeterAddress.hdlcServer(...). "
                                + "Got: " + serverAddress);
            }
            if (wrapperTransport && clientAddress.isHdlc()) {
                throw new IllegalStateException(
                        "WRAPPER transport requires MeterAddress.wrapperClient(...). "
                                + "Got: " + clientAddress);
            }
            if (!wrapperTransport && clientAddress.isWrapper()) {
                throw new IllegalStateException(
                        "HDLC transport requires MeterAddress.hdlcClient(...). "
                                + "Got: " + clientAddress);
            }

            return new CosemClientConfig(this);
        }
    }
}