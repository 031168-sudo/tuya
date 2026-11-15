package com.pivotaccess.dlmscosem.apdu;

import java.io.ByteArrayOutputStream;

/**
 * ASN.1 Basic Encoding Rules (BER) encoding utilities.
 *
 * <p>DLMS/COSEM APDUs use BER encoding for association (AARQ/AARE) structures.
 * Data objects inside GET/SET/ACTION use a simpler DLMS-specific encoding
 * handled by {@link DataObject} itself.
 *
 * <h3>BER length encoding</h3>
 * <pre>
 * Short form (0–127):  1 byte  — 0xxxxxxx
 * Long form  (128+):   n bytes — 1000nnnn followed by n bytes of length
 * </pre>
 *
 * <h3>BER tag encoding</h3>
 * <pre>
 * Single byte tag:  tag &lt; 0x1F
 * Multi-byte tag:   first byte = 0x1F, subsequent bytes have MSB=1 until last
 * </pre>
 */
public final class BerEncoder {

    private BerEncoder() {}

    // -------------------------------------------------------------------------
    // Length encoding
    // -------------------------------------------------------------------------

    /**
     * Encodes a BER length value into the output stream.
     *
     * @param out    target stream
     * @param length value to encode (must be non-negative)
     */
    public static void writeLength(ByteArrayOutputStream out, int length) {
        if (length < 0) {
            throw new IllegalArgumentException("Length must be non-negative: " + length);
        }
        if (length <= 127) {
            // Short form
            out.write(length);
        } else if (length <= 0xFF) {
            // Long form — 1 extra byte
            out.write(0x81);
            out.write(length & 0xFF);
        } else if (length <= 0xFFFF) {
            // Long form — 2 extra bytes
            out.write(0x82);
            out.write((length >> 8) & 0xFF);
            out.write(length & 0xFF);
        } else {
            // Long form — 3 extra bytes (handles up to ~16 MB)
            out.write(0x83);
            out.write((length >> 16) & 0xFF);
            out.write((length >> 8)  & 0xFF);
            out.write(length         & 0xFF);
        }
    }

    /**
     * Returns the encoded length as a byte array (convenience method).
     */
    public static byte[] encodeLength(int length) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(4);
        writeLength(out, length);
        return out.toByteArray();
    }

    // -------------------------------------------------------------------------
    // TLV (Tag-Length-Value) helpers
    // -------------------------------------------------------------------------

    /**
     * Writes a complete TLV element: tag byte + BER length + value bytes.
     *
     * @param out   target stream
     * @param tag   BER tag byte
     * @param value value bytes
     */
    public static void writeTlv(ByteArrayOutputStream out, int tag, byte[] value) {
        out.write(tag & 0xFF);
        writeLength(out, value.length);
        out.write(value, 0, value.length);
    }

    /**
     * Writes a TLV with a single-byte value.
     */
    public static void writeTlvByte(ByteArrayOutputStream out, int tag, int value) {
        out.write(tag & 0xFF);
        out.write(0x01);                // length = 1
        out.write(value & 0xFF);
    }

    /**
     * Writes a constructed TLV element (a tag wrapping already-encoded content).
     * The tag's constructed bit (bit 5) should be set by the caller if needed.
     *
     * @param out         target stream
     * @param tag         BER tag byte (e.g. 0x61 for AARE constructed application tag 1)
     * @param innerBytes  already-encoded inner content
     */
    public static void writeConstructed(ByteArrayOutputStream out, int tag, byte[] innerBytes) {
        writeTlv(out, tag, innerBytes);
    }

    // -------------------------------------------------------------------------
    // Common AARQ/AARE BER structure helpers
    // -------------------------------------------------------------------------

    /**
     * Encodes an APPLICATION tag (class = 01, constructed = 1 or 0).
     * Application tags 0–30 fit in a single byte: 0b01pc_tttt where
     * p = primitive/constructed, c = 0 (application class), t = tag number.
     *
     * @param tagNumber   application tag number (0–30)
     * @param constructed true for constructed encoding
     * @return encoded tag byte
     */
    public static int applicationTag(int tagNumber, boolean constructed) {
        return 0x40 | (constructed ? 0x20 : 0x00) | (tagNumber & 0x1F);
    }

    /**
     * Encodes a CONTEXT-SPECIFIC tag.
     * Context tags: 0b10pc_tttt
     *
     * @param tagNumber   context tag number (0–30)
     * @param constructed true for constructed encoding
     * @return encoded tag byte
     */
    public static int contextTag(int tagNumber, boolean constructed) {
        return 0x80 | (constructed ? 0x20 : 0x00) | (tagNumber & 0x1F);
    }
}