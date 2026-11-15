package com.pivotaccess.dlmscosem.hdlc;

import java.io.ByteArrayOutputStream;

/**
 * HDLC link negotiation parameters exchanged in SNRM and UA frames,
 * as defined in IEC 62056-46 Annex A.
 *
 * <p>The master proposes parameters in the SNRM payload; the slave echoes its
 * accepted values in the UA payload.  The actual negotiated value for each
 * parameter is the <em>minimum</em> of the two proposed values.
 *
 * <h3>Parameter encoding (TLV inside a wrapper)</h3>
 * <pre>
 * 81 80 &lt;len&gt;
 *   05 01 &lt;windowTx&gt;           — max window size (transmit)
 *   06 04 &lt;maxInfoTx — 4 bytes&gt; — max I-field length (transmit)
 *   07 04 &lt;maxInfoRx — 4 bytes&gt; — max I-field length (receive)
 *   08 01 &lt;windowRx&gt;           — max window size (receive)
 * </pre>
 */
public final class HDLCNegotiationParameters {

    public static final int DEFAULT_MAX_INFO_LENGTH = 128;
    public static final int DEFAULT_WINDOW_SIZE     = 1;
    public static final int MAX_INFO_LENGTH_LIMIT   = 2030;
    public static final int MAX_WINDOW_SIZE_LIMIT   = 7;

    private static final int TAG_WINDOW_TX   = 0x07;
    private static final int TAG_MAX_INFO_TX = 0x05;
    private static final int TAG_MAX_INFO_RX = 0x06;
    private static final int TAG_WINDOW_RX   = 0x08;
    private static final int WRAPPER_TAG     = 0x81;
    private static final int WRAPPER_SUBTYPE = 0x80;

    private final int maxInfoLengthTransmit;
    private final int maxInfoLengthReceive;
    private final int windowSizeTransmit;
    private final int windowSizeReceive;

    public HDLCNegotiationParameters(int maxInfoLengthTransmit, int maxInfoLengthReceive,
                                     int windowSizeTransmit,    int windowSizeReceive) {
        this.maxInfoLengthTransmit = clamp(maxInfoLengthTransmit, 1, MAX_INFO_LENGTH_LIMIT);
        this.maxInfoLengthReceive  = clamp(maxInfoLengthReceive,  1, MAX_INFO_LENGTH_LIMIT);
        this.windowSizeTransmit    = clamp(windowSizeTransmit,    1, MAX_WINDOW_SIZE_LIMIT);
        this.windowSizeReceive     = clamp(windowSizeReceive,     1, MAX_WINDOW_SIZE_LIMIT);
    }

    public static HDLCNegotiationParameters defaults() {
        return new HDLCNegotiationParameters(
                DEFAULT_MAX_INFO_LENGTH, DEFAULT_MAX_INFO_LENGTH,
                DEFAULT_WINDOW_SIZE, DEFAULT_WINDOW_SIZE);
    }

    // -------------------------------------------------------------------------
    // Negotiation
    // -------------------------------------------------------------------------

    public static HDLCNegotiationParameters negotiate(HDLCNegotiationParameters proposed,
                                                      HDLCNegotiationParameters accepted) {
        return new HDLCNegotiationParameters(
                Math.min(proposed.maxInfoLengthTransmit, accepted.maxInfoLengthTransmit),
                Math.min(proposed.maxInfoLengthReceive,  accepted.maxInfoLengthReceive),
                Math.min(proposed.windowSizeTransmit,    accepted.windowSizeTransmit),
                Math.min(proposed.windowSizeReceive,     accepted.windowSizeReceive));
    }

    // -------------------------------------------------------------------------
    // Encoding
    // -------------------------------------------------------------------------

    public byte[] encode() {
        ByteArrayOutputStream params = new ByteArrayOutputStream(16);
        params.write(TAG_WINDOW_TX);  params.write(0x01); params.write(windowSizeTransmit & 0xFF);
        params.write(TAG_MAX_INFO_TX); params.write(0x04); writeInt32(params, maxInfoLengthTransmit);
        params.write(TAG_MAX_INFO_RX); params.write(0x04); writeInt32(params, maxInfoLengthReceive);
        params.write(TAG_WINDOW_RX);  params.write(0x01); params.write(windowSizeReceive & 0xFF);
        byte[] inner = params.toByteArray();

        ByteArrayOutputStream out = new ByteArrayOutputStream(inner.length + 3);
        out.write(WRAPPER_TAG);
        out.write(WRAPPER_SUBTYPE);
        out.write(inner.length & 0xFF);
        out.write(inner, 0, inner.length);
        return out.toByteArray();
    }

    // -------------------------------------------------------------------------
    // Decoding
    // -------------------------------------------------------------------------

    /**
     * Decodes negotiation parameters from a SNRM or UA payload.
     *
     * <p>Tolerates 1-byte, 2-byte, and 4-byte encodings of the max-info-length
     * fields — some implementations encode them in 1 byte.
     */
    public static HDLCNegotiationParameters decode(byte[] payload) {
        if (payload == null || payload.length < 3) return defaults();
        if ((payload[0] & 0xFF) != WRAPPER_TAG || (payload[1] & 0xFF) != WRAPPER_SUBTYPE)
            return defaults();

        int len = payload[2] & 0xFF;
        int end = Math.min(3 + len, payload.length);
        int maxInfoTx = DEFAULT_MAX_INFO_LENGTH;
        int maxInfoRx = DEFAULT_MAX_INFO_LENGTH;
        int windowTx  = DEFAULT_WINDOW_SIZE;
        int windowRx  = DEFAULT_WINDOW_SIZE;

        int pos = 3;
        while (pos + 1 < end) {
            int tag    = payload[pos]     & 0xFF;
            int length = payload[pos + 1] & 0xFF;
            pos += 2;
            if (pos + length > end) break;

            switch (tag) {
                case TAG_WINDOW_TX:   windowTx  = payload[pos] & 0xFF;             break;
                case TAG_MAX_INFO_TX: maxInfoTx = readInt(payload, pos, length);    break;
                case TAG_MAX_INFO_RX: maxInfoRx = readInt(payload, pos, length);    break;
                case TAG_WINDOW_RX:   windowRx  = payload[pos] & 0xFF;             break;
                default: break;
            }
            pos += length;
        }
        return new HDLCNegotiationParameters(maxInfoTx, maxInfoRx, windowTx, windowRx);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public int getMaxInfoLengthTransmit() { return maxInfoLengthTransmit; }
    public int getMaxInfoLengthReceive()  { return maxInfoLengthReceive; }
    public int getWindowSizeTransmit()    { return windowSizeTransmit; }
    public int getWindowSizeReceive()     { return windowSizeReceive; }

    @Override
    public String toString() {
        return String.format("HDLCNegotiationParameters{maxInfoTx=%d, maxInfoRx=%d, windowTx=%d, windowRx=%d}",
                maxInfoLengthTransmit, maxInfoLengthReceive, windowSizeTransmit, windowSizeReceive);
    }

    private static void writeInt32(ByteArrayOutputStream out, int value) {
        out.write((value >> 24) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >>  8) & 0xFF);
        out.write( value        & 0xFF);
    }

    private static int readInt(byte[] data, int offset, int length) {
        int value = 0;
        for (int i = 0; i < length && i < 4; i++) {
            value = (value << 8) | (data[offset + i] & 0xFF);
        }
        return value;
    }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
}