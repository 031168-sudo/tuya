package com.pivotaccess.dlmscosem.apdu;

import java.util.Arrays;

/**
 * ASN.1 Basic Encoding Rules (BER) decoding utilities.
 *
 * <p>Companion to {@link BerEncoder}. Used primarily for parsing AARQ/AARE
 * association APDUs. Data object decoding is handled by {@link DataObject}.
 */
public final class BerDecoder {

    private BerDecoder() {}

    // -------------------------------------------------------------------------
    // Length decoding
    // -------------------------------------------------------------------------

    /**
     * Result of a BER length decode: the decoded length value and the offset
     * of the first value byte immediately following the length field.
     */
    public static final class LengthResult {
        /** The decoded length value (number of value bytes that follow). */
        public final int length;
        /** Offset of the first value byte in the source array. */
        public final int nextOffset;

        LengthResult(int length, int nextOffset) {
            this.length     = length;
            this.nextOffset = nextOffset;
        }
    }

    /**
     * Decodes a BER-encoded length from {@code data} starting at {@code offset}.
     *
     * @param data   source byte array
     * @param offset position of the first length byte
     * @return {@link LengthResult} with decoded length and next offset
     * @throws ApduException if the length encoding is invalid or truncated
     */
    public static LengthResult readLength(byte[] data, int offset) throws ApduException {
        if (offset >= data.length) {
            throw new ApduException("BER length decode: offset out of bounds: " + offset);
        }

        int first = data[offset] & 0xFF;

        if (first <= 0x7F) {
            // Short form
            return new LengthResult(first, offset + 1);
        }

        // Long form: lower 7 bits = number of subsequent length bytes
        int numBytes = first & 0x7F;
        if (numBytes == 0) {
            throw new ApduException("BER indefinite length encoding not supported");
        }
        if (numBytes > 3) {
            throw new ApduException("BER length too large: " + numBytes + " bytes");
        }
        if (offset + 1 + numBytes > data.length) {
            throw new ApduException("BER length field truncated at offset " + offset);
        }

        int length = 0;
        for (int i = 0; i < numBytes; i++) {
            length = (length << 8) | (data[offset + 1 + i] & 0xFF);
        }

        return new LengthResult(length, offset + 1 + numBytes);
    }

    // -------------------------------------------------------------------------
    // TLV (Tag-Length-Value) reading
    // -------------------------------------------------------------------------

    /**
     * Reads a TLV element from {@code data} at {@code offset}.
     * Returns the value bytes (does not include tag or length bytes).
     *
     * @param data           source array
     * @param offset         position of the tag byte
     * @param expectedTag    the tag byte expected at this position
     * @return value bytes
     * @throws ApduException if the tag does not match or data is truncated
     */
    public static byte[] readTlvValue(byte[] data, int offset, int expectedTag)
            throws ApduException {
        int actualTag = data[offset] & 0xFF;
        if (actualTag != expectedTag) {
            throw new ApduException(String.format(
                    "BER tag mismatch at offset %d: expected 0x%02X, got 0x%02X",
                    offset, expectedTag, actualTag));
        }
        LengthResult lr = readLength(data, offset + 1);
        if (lr.nextOffset + lr.length > data.length) {
            throw new ApduException("BER value truncated at offset " + offset);
        }
        return Arrays.copyOfRange(data, lr.nextOffset, lr.nextOffset + lr.length);
    }

    /**
     * Reads a single-byte TLV value.
     *
     * @param data        source array
     * @param offset      position of the tag byte
     * @param expectedTag expected tag
     * @return the single value byte as an int (0–255)
     * @throws ApduException if tag mismatch, length != 1, or truncated
     */
    public static int readTlvByte(byte[] data, int offset, int expectedTag)
            throws ApduException {
        byte[] value = readTlvValue(data, offset, expectedTag);
        if (value.length != 1) {
            throw new ApduException("Expected 1-byte TLV value, got " + value.length);
        }
        return value[0] & 0xFF;
    }

    // -------------------------------------------------------------------------
    // Tag inspection helpers
    // -------------------------------------------------------------------------

    /**
     * Returns true if the byte at {@code offset} matches {@code tag}.
     * Use for optional TLV fields before attempting to read them.
     */
    public static boolean tagAt(byte[] data, int offset, int tag) {
        return offset < data.length && (data[offset] & 0xFF) == tag;
    }

    /**
     * Returns the total number of bytes consumed by a TLV element
     * (tag byte + length bytes + value bytes).
     */
    public static int tlvSize(byte[] data, int offset) throws ApduException {
        LengthResult lr = readLength(data, offset + 1);
        return (lr.nextOffset - offset) + lr.length;
    }
}