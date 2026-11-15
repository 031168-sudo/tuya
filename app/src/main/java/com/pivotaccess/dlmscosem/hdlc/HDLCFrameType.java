package com.pivotaccess.dlmscosem.hdlc;

/**
 * HDLC frame types as defined in IEC 62056-46.
 *
 * <p>HDLC defines three frame categories:
 * <ul>
 *   <li><b>U-frames</b> (Unnumbered) — connection setup and teardown.
 *       Control byte bits 0–1 and 5–7 are {@code 11}.</li>
 *   <li><b>S-frames</b> (Supervisory) — flow control and acknowledgement.
 *       Control byte bit 0 is {@code 1}, bit 1 is {@code 0} (i.e. {@code x01}).</li>
 *   <li><b>I-frames</b> (Information) — data transfer.
 *       Control byte bit 0 is {@code 0}.</li>
 * </ul>
 *
 * <p>Each enum value carries the raw control field bit-pattern (masking out
 * the P/F bit and sequence numbers) used to identify the frame type during
 * decoding.
 */
public enum HDLCFrameType {

    // -------------------------------------------------------------------------
    // U-frames (Unnumbered)
    // -------------------------------------------------------------------------

    /**
     * Set Normal Response Mode — sent by master to open an HDLC link.
     * The SNRM frame payload carries negotiation parameters (max frame size,
     * window size). Control = 0x93.
     */
    SNRM(0x83, Category.U),

    /**
     * Unnumbered Acknowledgement — sent by slave in response to SNRM or DISC.
     * UA in response to SNRM also echoes the negotiated parameters.
     * Control = 0x73.
     */
    UA(0x63, Category.U),

    /**
     * Disconnect — sent by master to close the HDLC link.
     * Control = 0x53.
     */
    DISC(0x43, Category.U),

    /**
     * Disconnected Mode — sent by slave when it is not ready or rejects SNRM.
     * Control = 0x1F.
     */
    DM(0x0F, Category.U),

    /**
     * Frame Reject — sent when a frame is received that cannot be processed
     * (e.g. invalid N(S), unsupported control field).
     * Control = 0x97.
     */
    FRMR(0x87, Category.U),

    // -------------------------------------------------------------------------
    // S-frames (Supervisory)
    // -------------------------------------------------------------------------

    /**
     * Receive Ready — acknowledges all I-frames up to N(R)−1.
     * Also used as a standalone poll/final with no data.
     * Control lower nibble = 0x01.
     */
    RR(0x01, Category.S),

    /**
     * Receive Not Ready — indicates the receiver is temporarily busy.
     * Control lower nibble = 0x05.
     */
    RNR(0x05, Category.S),

    /**
     * Reject — requests retransmission of I-frames from N(R) onward.
     * Control lower nibble = 0x09.
     */
    REJ(0x09, Category.S),

    // -------------------------------------------------------------------------
    // I-frames (Information)
    // -------------------------------------------------------------------------

    /**
     * Information frame — carries an APDU payload.
     * Bit 0 of the control byte is always 0 for I-frames.
     * The full control byte also encodes N(S) and N(R) sequence numbers
     * and the P/F bit.
     */
    I(0x00, Category.I);

    // -------------------------------------------------------------------------

    /** Frame category: U, S, or I. */
    public enum Category { U, S, I }

    private final int      controlMask;
    private final Category category;

    HDLCFrameType(int controlMask, Category category) {
        this.controlMask = controlMask;
        this.category    = category;
    }

    /** Raw control byte bit-pattern for this frame type (P/F and seq numbers masked out). */
    public int getControlMask() { return controlMask; }

    /** Whether this is a U-frame, S-frame, or I-frame. */
    public Category getCategory() { return category; }

    // -------------------------------------------------------------------------
    // Decode
    // -------------------------------------------------------------------------

    /**
     * Derives the frame type from a raw control byte.
     *
     * <p>Detection order per IEC 62056-46:
     * <ol>
     *   <li>I-frame: bit 0 == 0</li>
     *   <li>S-frame: bits 0–1 == 01</li>
     *   <li>U-frame: bits 0–1 == 11</li>
     * </ol>
     *
     * @param control raw control byte from an HDLC frame
     * @return the matching {@link HDLCFrameType}
     * @throws HDLCFrameException if the control byte does not match any known type
     */
    public static HDLCFrameType decode(int control) throws HDLCFrameException {
        // I-frame: bit 0 = 0
        if ((control & 0x01) == 0x00) {
            return I;
        }

        // S-frame: bits 1-0 = 01
        if ((control & 0x03) == 0x01) {
            int sType = control & 0x0F; // mask out P/F and N(R)
            for (HDLCFrameType t : values()) {
                if (t.category == Category.S && t.controlMask == sType) return t;
            }
            throw new HDLCFrameException(
                    String.format("Unknown S-frame control: 0x%02X", control));
        }

        // U-frame: bits 1-0 = 11
        if ((control & 0x03) == 0x03) {
            // Mask out the P/F bit (bit 4) to get the type identifier
            int uType = control & ~0x10;
            for (HDLCFrameType t : values()) {
                if (t.category == Category.U && t.controlMask == uType) return t;
            }
            throw new HDLCFrameException(
                    String.format("Unknown U-frame control: 0x%02X", control));
        }

        throw new HDLCFrameException(
                String.format("Cannot decode control byte: 0x%02X", control));
    }
}