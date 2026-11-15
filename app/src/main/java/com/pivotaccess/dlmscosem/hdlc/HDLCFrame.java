package com.pivotaccess.dlmscosem.hdlc;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * An HDLC frame as defined in IEC 62056-46.
 *
 * <h3>Frame structure</h3>
 * <pre>
 * +------+--------+---------+---------+---------+-------+-------------+-------+------+
 * | Flag | Format | Dst Addr| Src Addr| Control | HCS   | Information |  FCS  | Flag |
 * | 0x7E | 2 bytes| 1/2/4 B | 1/2/4 B |  1 byte | 2 B   |   n bytes   |  2 B  | 0x7E |
 * +------+--------+---------+---------+---------+-------+-------------+-------+------+
 * </pre>
 *
 * <h3>FCS presence rule</h3>
 * <p>Frames <em>without</em> an information field (pure S-frames such as RR,
 * and some U-frames such as DISC) do not carry a separate FCS.  The HCS at
 * the end of the header already covers all frame bytes and serves as the only
 * integrity check.  This is reflected in the {@link #decode} logic:
 * <ul>
 *   <li>body length == header length + 2  → control frame, HCS only, no FCS</li>
 *   <li>body length  > header length + 4  → information frame, HCS + info + FCS</li>
 * </ul>
 *
 * <h3>Byte stuffing</h3>
 * <p>After CRC calculation, the flag byte ({@code 0x7E}) and escape byte
 * ({@code 0x7D}) are replaced:
 * <ul>
 *   <li>{@code 0x7E} → {@code 0x7D 0x5E}</li>
 *   <li>{@code 0x7D} → {@code 0x7D 0x5D}</li>
 * </ul>
 */
public final class HDLCFrame {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    public static final byte   FLAG           = 0x7E;
    public static final byte   ESCAPE         = 0x7D;
    public static final byte   STUFFED_FLAG   = 0x5E;
    public static final byte   STUFFED_ESCAPE = 0x5D;

    private static final int FRAME_FORMAT_TYPE = 0xA000;
    private static final int SEGMENTATION_BIT  = 0x0800;

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final HDLCFrameType type;
    private final HDLCAddress   dstAddress;
    private final HDLCAddress   srcAddress;
    private final int           sendSeq;
    private final int           recvSeq;
    private final boolean       poll;
    private final boolean       segmented;
    private final byte[]        payload;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    HDLCFrame(HDLCFrameType type, HDLCAddress dstAddress, HDLCAddress srcAddress,
              int sendSeq, int recvSeq, boolean poll, boolean segmented, byte[] payload) {
        this.type       = type;
        this.dstAddress = dstAddress;
        this.srcAddress = srcAddress;
        this.sendSeq    = sendSeq;
        this.recvSeq    = recvSeq;
        this.poll       = poll;
        this.segmented  = segmented;
        this.payload    = payload == null ? new byte[0] : Arrays.copyOf(payload, payload.length);
    }

    // -------------------------------------------------------------------------
    // Static factories
    // -------------------------------------------------------------------------

    public static HDLCFrame snrm(HDLCAddress dst, HDLCAddress src, byte[] parameters) {
        return new HDLCFrame(HDLCFrameType.SNRM, dst, src, 0, 0, true, false, parameters);
    }

    public static HDLCFrame ua(HDLCAddress dst, HDLCAddress src, byte[] parameters) {
        return new HDLCFrame(HDLCFrameType.UA, dst, src, 0, 0, false, false, parameters);
    }

    public static HDLCFrame disc(HDLCAddress dst, HDLCAddress src) {
        return new HDLCFrame(HDLCFrameType.DISC, dst, src, 0, 0, true, false, null);
    }

    public static HDLCFrame iFrame(HDLCAddress dst, HDLCAddress src,
                                   int sendSeq, int recvSeq,
                                   boolean poll, boolean segmented, byte[] payload) {
        return new HDLCFrame(HDLCFrameType.I, dst, src, sendSeq, recvSeq, poll, segmented, payload);
    }

    public static HDLCFrame rr(HDLCAddress dst, HDLCAddress src, int recvSeq, boolean poll) {
        return new HDLCFrame(HDLCFrameType.RR, dst, src, 0, recvSeq, poll, false, null);
    }

    // -------------------------------------------------------------------------
    // Encoding
    // -------------------------------------------------------------------------

    /**
     * Encodes this frame into a byte array, including opening/closing flags,
     * byte stuffing, HCS, and FCS (FCS omitted for control frames with no payload).
     */
    // Replace your encode() method in HDLCFrame.java with this:
    public byte[] encode() {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();

        // 1. Calculate frame length (excluding flags, before stuffing)
        int len = 2 + dstAddress.getSize() + srcAddress.getSize() + 1 + 2; // header
        if (payload != null && payload.length > 0) {
            len += payload.length + 2; // payload + FCS
        }

        // 2. Write format, addresses, and control
        frame.write(0xA0 | ((len >> 8) & 0x07));
        frame.write(len & 0xFF);
        frame.write(dstAddress.getEncoded(), 0, dstAddress.getSize());
        frame.write(srcAddress.getEncoded(), 0, srcAddress.getSize());
        frame.write(buildControl());

        // 3. Calculate and append HCS (Header Check Sequence)
        byte[] header = frame.toByteArray();
        int hcs = calculateFcs16(header);
        frame.write(hcs & 0xFF);         // LSB first
        frame.write((hcs >> 8) & 0xFF);  // MSB second

        // 4. Write payload and calculate final FCS
        if (payload != null && payload.length > 0) {
            frame.write(payload, 0, payload.length);
            byte[] headerAndPayload = frame.toByteArray();
            int fcs = calculateFcs16(headerAndPayload);
            frame.write(fcs & 0xFF);         // LSB first
            frame.write((fcs >> 8) & 0xFF);  // MSB second
        }

        // 5. Apply byte stuffing to everything between the flags
        ByteArrayOutputStream stuffed = new ByteArrayOutputStream();
        stuffed.write(0x7E); // Opening flag
        for (byte b : frame.toByteArray()) {
            int v = b & 0xFF;
            if (v == 0x7E) {
                stuffed.write(0x7D); stuffed.write(0x5E);
            } else if (v == 0x7D) {
                stuffed.write(0x7D); stuffed.write(0x5D);
            } else {
                stuffed.write(v);
            }
        }
        stuffed.write(0x7E); // Closing flag
        return stuffed.toByteArray();
    }

    /** Algorithmic implementation of PPP FCS-16 (RFC 1662) */
    private static int calculateFcs16(byte[] data) {
        int fcs = 0xFFFF;
        for (byte b : data) {
            fcs ^= (b & 0xFF);
            for (int i = 0; i < 8; i++) {
                if ((fcs & 1) != 0) {
                    fcs = (fcs >> 1) ^ 0x8408;
                } else {
                    fcs >>= 1;
                }
            }
        }
        return (~fcs) & 0xFFFF;
    }

    private byte[] buildBody() {
        byte   control    = buildControl();
        byte[] dstEncoded = dstAddress.getEncoded();
        byte[] srcEncoded = srcAddress.getEncoded();

        // Header: Format(2) + DstAddr + SrcAddr + Control
        int headerLen = 2 + dstEncoded.length + srcEncoded.length + 1;

        boolean hasPayload = (payload.length > 0);

        // Frame length = header + HCS(2) + [payload + FCS(2)]
        int frameLen = headerLen + 2 + (hasPayload ? payload.length + 2 : 0);

        int formatField = FRAME_FORMAT_TYPE
                | (segmented ? SEGMENTATION_BIT : 0)
                | (frameLen & 0x07FF);

        ByteArrayOutputStream buf = new ByteArrayOutputStream(frameLen);

        // Format field (big-endian)
        buf.write((formatField >> 8) & 0xFF);
        buf.write(formatField & 0xFF);

        // Addresses
        buf.write(dstEncoded, 0, dstEncoded.length);
        buf.write(srcEncoded, 0, srcEncoded.length);

        // Control
        buf.write(control & 0xFF);

        // HCS — over Format + DstAddr + SrcAddr + Control
        byte[] header = buf.toByteArray();
        int hcs = crc16(header, 0, header.length);
        buf.write(hcs & 0xFF);
        buf.write((hcs >> 8) & 0xFF);

        if (hasPayload) {
            // Information field
            buf.write(payload, 0, payload.length);

            // FCS — over everything from Format through end of Information
            byte[] forFcs = buf.toByteArray();
            int fcs = crc16(forFcs, 0, forFcs.length);
            buf.write(fcs & 0xFF);
            buf.write((fcs >> 8) & 0xFF);
        }
        // No FCS for control frames (no information field)

        return buf.toByteArray();
    }

    private byte buildControl() {
        int c;
        switch (type) {
            case I:
                c = ((sendSeq & 0x07) << 1)
                        | ((poll ? 1 : 0) << 4)
                        | ((recvSeq & 0x07) << 5);
                break;
            case RR: case RNR: case REJ:
                c = (type.getControlMask() & 0x0F)
                        | ((poll ? 1 : 0) << 4)
                        | ((recvSeq & 0x07) << 5);
                break;
            default:
                // U-frame: fixed control + P/F bit
                c = type.getControlMask() | ((poll ? 1 : 0) << 4);
                break;
        }
        return (byte) c;
    }

    // -------------------------------------------------------------------------
    // Decoding
    // -------------------------------------------------------------------------

    /**
     * Decodes an HDLC frame from a raw byte array starting with {@code 0x7E}.
     *
     * <p>Handles both information frames (with FCS) and pure control frames
     * (without FCS, such as RR and DISC).
     */
    public static HDLCFrame decode(byte[] raw) throws HDLCFrameException {
        if (raw == null || raw.length < 2) {
            throw new HDLCFrameException("Frame too short: " + (raw == null ? 0 : raw.length));
        }
        if ((raw[0] & 0xFF) != 0x7E) {
            throw new HDLCFrameException(
                    String.format("Missing opening flag, got: 0x%02X", raw[0]));
        }

        // Find closing flag
        int closingIdx = -1;
        for (int i = raw.length - 1; i > 0; i--) {
            if ((raw[i] & 0xFF) == 0x7E) {
                closingIdx = i;
                break;
            }
        }
        if (closingIdx <= 0) {
            throw new HDLCFrameException("Missing closing flag");
        }

        // Un-stuff body
        byte[] stuffed = Arrays.copyOfRange(raw, 1, closingIdx);
        byte[] body    = unstuff(stuffed);

        // Minimum: Format(2) + DstAddr(1) + SrcAddr(1) + Control(1) + HCS(2) = 7
        if (body.length < 7) {
            throw new HDLCFrameException("Frame body too short: " + body.length);
        }

        int pos = 0;

        // --- Frame format (2 bytes) ---
        int formatField = ((body[pos] & 0xFF) << 8) | (body[pos + 1] & 0xFF);
        pos += 2;

        if ((formatField & 0xF000) != 0xA000) {
            throw new HDLCFrameException(
                    String.format("Invalid format type: 0x%04X", formatField));
        }
        boolean segmented = (formatField & SEGMENTATION_BIT) != 0;

        // --- Destination address ---
        HDLCAddress dstAddress = HDLCAddress.decode(body, pos);
        pos += dstAddress.getSize();

        // --- Source address ---
        HDLCAddress srcAddress = HDLCAddress.decode(body, pos);
        pos += srcAddress.getSize();

        // --- Control byte ---
        int controlByte = body[pos] & 0xFF;
        pos++;

        // --- Header length (up to and including control) ---
        int headerLen = pos; // Format(2) + DstAddr + SrcAddr + Control

        // --- HCS ---
        if (pos + 1 >= body.length) {
            throw new HDLCFrameException("Frame too short to contain HCS");
        }
        int computedHcs = crc16(body, 0, headerLen);
        int receivedHcs = (body[pos] & 0xFF) | ((body[pos + 1] & 0xFF) << 8);
        if (computedHcs != receivedHcs) {
            throw new HDLCFrameException(String.format(
                    "HCS mismatch: computed=0x%04X received=0x%04X",
                    computedHcs, receivedHcs));
        }
        pos += 2; // skip HCS

        // --- Determine if there is an information field ---
        // Control frame (no info): body ends right after HCS
        // Information frame: body has info bytes followed by 2-byte FCS
        byte[] payload;

        if (pos >= body.length) {
            // Pure control frame — no info field, no FCS
            // HCS already validated above; nothing more to check
            payload = new byte[0];
        } else {
            // Information frame — last 2 bytes are FCS
            int fcsStart    = body.length - 2;
            int computedFcs = crc16(body, 0, fcsStart);
            int receivedFcs = (body[fcsStart] & 0xFF) | ((body[fcsStart + 1] & 0xFF) << 8);
            if (computedFcs != receivedFcs) {
                throw new HDLCFrameException(String.format(
                        "FCS mismatch: computed=0x%04X received=0x%04X",
                        computedFcs, receivedFcs));
            }
            payload = (pos < fcsStart)
                    ? Arrays.copyOfRange(body, pos, fcsStart)
                    : new byte[0];
        }

        // --- Decode control byte ---
        HDLCFrameType type = HDLCFrameType.decode(controlByte);

        int     sendSeq = 0, recvSeq = 0;
        boolean poll    = false;

        switch (type.getCategory()) {
            case I:
                sendSeq = (controlByte >> 1) & 0x07;
                poll    = ((controlByte >> 4) & 1) == 1;
                recvSeq = (controlByte >> 5) & 0x07;
                break;
            case S:
                poll    = ((controlByte >> 4) & 1) == 1;
                recvSeq = (controlByte >> 5) & 0x07;
                break;
            case U:
                poll = ((controlByte >> 4) & 1) == 1;
                break;
        }

        return new HDLCFrame(type, dstAddress, srcAddress,
                sendSeq, recvSeq, poll, segmented, payload);
    }

    // -------------------------------------------------------------------------
    // CRC-16/CCITT (poly=0x8408 reflected, init=0xFFFF, finalXOR=0xFFFF)
    // -------------------------------------------------------------------------

    static int crc16(byte[] data, int offset, int length) {
        int crc = 0xFFFF;
        for (int i = offset; i < offset + length; i++) {
            crc ^= (data[i] & 0xFF);
            for (int j = 0; j < 8; j++) {
                if ((crc & 0x0001) != 0) crc = (crc >> 1) ^ 0x8408;
                else                      crc >>= 1;
            }
        }
        return crc ^ 0xFFFF;
    }

    // -------------------------------------------------------------------------
    // Byte stuffing
    // -------------------------------------------------------------------------

    static byte[] stuff(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + 4);
        for (byte b : data) {
            int v = b & 0xFF;
            if      (v == 0x7E) { out.write(ESCAPE); out.write(STUFFED_FLAG);   }
            else if (v == 0x7D) { out.write(ESCAPE); out.write(STUFFED_ESCAPE); }
            else                { out.write(v); }
        }
        return out.toByteArray();
    }

    static byte[] unstuff(byte[] data) throws HDLCFrameException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        int i = 0;
        while (i < data.length) {
            int v = data[i] & 0xFF;
            if (v == 0x7D) {
                i++;
                if (i >= data.length) {
                    throw new HDLCFrameException("Escape byte at end of frame");
                }
                int next = data[i] & 0xFF;
                if      (next == STUFFED_FLAG)   out.write(0x7E);
                else if (next == STUFFED_ESCAPE) out.write(0x7D);
                else throw new HDLCFrameException(
                            String.format("Invalid stuffed byte: 0x%02X", next));
            } else {
                out.write(v);
            }
            i++;
        }
        return out.toByteArray();
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public HDLCFrameType getType()        { return type; }
    public HDLCAddress   getDstAddress()  { return dstAddress; }
    public HDLCAddress   getSrcAddress()  { return srcAddress; }
    public int           getSendSeq()     { return sendSeq; }
    public int           getRecvSeq()     { return recvSeq; }
    public boolean       isPoll()         { return poll; }
    public boolean       isSegmented()    { return segmented; }
    public byte[]        getPayload()     { return Arrays.copyOf(payload, payload.length); }
    public boolean       hasPayload()     { return payload.length > 0; }

    @Override
    public String toString() {
        return String.format(
                "HDLCFrame{type=%s, dst=%s, src=%s, N(S)=%d, N(R)=%d, P/F=%b, seg=%b, payloadLen=%d}",
                type, dstAddress, srcAddress, sendSeq, recvSeq, poll, segmented, payload.length);
    }
}