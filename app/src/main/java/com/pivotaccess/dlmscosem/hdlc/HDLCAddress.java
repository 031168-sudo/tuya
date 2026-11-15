package com.pivotaccess.dlmscosem.hdlc;

import java.util.Arrays;

/**
 * HDLC address as defined in IEC 62056-46.
 *
 * <h3>Encoding rules</h3>
 * <p>HDLC addresses are encoded in 1, 2, or 4 bytes. Each byte is left-shifted
 * by one bit and the LSB (bit 0) is used as an "extension" flag:
 * <ul>
 *   <li>{@code 0} — more address bytes follow</li>
 *   <li>{@code 1} — this is the last address byte</li>
 * </ul>
 *
 * <h3>DLMS/COSEM address structure</h3>
 * <p>For DLMS meters, the server (meter) address is composed of two parts:
 * <ul>
 *   <li><b>Upper HDLC address</b> — logical device address (e.g. 1 = management
 *       logical device). Encoded in the upper byte(s).</li>
 *   <li><b>Lower HDLC address</b> — physical device address (the meter's position
 *       on the RS-485 bus). Encoded in the lower byte(s).</li>
 * </ul>
 *
 * <p>The client address is always a single byte (typically {@code 0x10} = 16
 * for a public client, or {@code 0x01} for a management client).
 *
 * <h3>Examples</h3>
 * <pre>{@code
 * // Client address: 1 byte
 * HDLCAddress client = HDLCAddress.client(16);        // 0x21
 *
 * // Server address: 1 byte (small networks, physical address only)
 * HDLCAddress server = HDLCAddress.serverOneBytes(1); // 0x03
 *
 * // Server address: 2 bytes (upper=1, lower=1)
 * HDLCAddress server = HDLCAddress.serverTwoBytes(1, 1);
 *
 * // Server address: 4 bytes (large networks)
 * HDLCAddress server = HDLCAddress.serverFourBytes(1, 1);
 * }</pre>
 */
public final class HDLCAddress {

    private final byte[] encoded;   // ready-to-use encoded bytes (LSB extension already set)
    private final int    upper;     // upper (logical) address component
    private final int    lower;     // lower (physical) address component
    private final int    size;      // number of bytes: 1, 2, or 4

    // -------------------------------------------------------------------------
    // Factories
    // -------------------------------------------------------------------------

    /**
     * Creates a 1-byte client address.
     *
     * @param address client address value (e.g. 16 for public client)
     */
    public static HDLCAddress client(int address) {
        return oneByteAddress(address);
    }

    /**
     * Creates a 1-byte server address (physical address only, no upper address).
     * Suitable for simple networks with a small number of meters.
     *
     * @param physicalAddress meter address on the RS-485 bus (1–126)
     */
    public static HDLCAddress serverOneByte(int physicalAddress) {
        return oneByteAddress(physicalAddress);
    }

    /**
     * Creates a 2-byte server address (upper + lower, 1 byte each).
     *
     * @param upperAddress logical device address (e.g. 1)
     * @param lowerAddress physical device address (meter's bus position)
     */
    public static HDLCAddress serverTwoBytes(int upperAddress, int lowerAddress) {
        // Upper byte: extension bit = 0 (more follows)
        // Lower byte: extension bit = 1 (last byte)
        byte upper = (byte) ((upperAddress << 1) & 0xFE);       // bit 0 = 0
        byte lower = (byte) (((lowerAddress << 1) & 0xFE) | 1); // bit 0 = 1
        return new HDLCAddress(new byte[]{upper, lower}, upperAddress, lowerAddress, 2);
    }

    /**
     * Creates a 4-byte server address (upper 2 bytes + lower 2 bytes).
     * Used for large networks or meters with extended addressing.
     *
     * @param upperAddress logical device address
     * @param lowerAddress physical device address
     */
    public static HDLCAddress serverFourBytes(int upperAddress, int lowerAddress) {
        // Upper address: 2 bytes, extension bits = 0
        byte u1 = (byte) (((upperAddress >> 7) << 1) & 0xFE);
        byte u2 = (byte) ((upperAddress << 1) & 0xFE);

        // Lower address: 2 bytes, last byte extension bit = 1
        byte l1 = (byte) (((lowerAddress >> 7) << 1) & 0xFE);
        byte l2 = (byte) (((lowerAddress << 1) & 0xFE) | 1);

        return new HDLCAddress(new byte[]{u1, u2, l1, l2}, upperAddress, lowerAddress, 4);
    }

    // -------------------------------------------------------------------------
    // Decode from raw bytes
    // -------------------------------------------------------------------------

    /**
     * Decodes an HDLC address from a byte array starting at {@code offset}.
     *
     * @param data   raw byte array (full frame buffer)
     * @param offset position of the first address byte
     * @return decoded {@link HDLCAddress}
     * @throws HDLCFrameException if the address bytes are malformed or truncated
     */
    public static HDLCAddress decode(byte[] data, int offset) throws HDLCFrameException {
        if (offset >= data.length) {
            throw new HDLCFrameException("Address offset out of bounds: " + offset);
        }

        // Count address bytes by looking for LSB = 1 (last byte marker)
        int start  = offset;
        int length = 0;
        while (true) {
            if (offset + length >= data.length) {
                throw new HDLCFrameException("Truncated HDLC address at offset " + start);
            }
            length++;
            if ((data[offset + length - 1] & 0x01) == 1) break; // last byte
            if (length > 4) {
                throw new HDLCFrameException("HDLC address exceeds 4 bytes at offset " + start);
            }
        }

        byte[] addrBytes = Arrays.copyOfRange(data, start, start + length);

        // Decode upper and lower components
        int upper, lower;
        lower = switch (length) {
            case 1 -> {
                upper = 0;
                yield (addrBytes[0] & 0xFF) >> 1;
            }
            case 2 -> {
                upper = (addrBytes[0] & 0xFF) >> 1;
                yield (addrBytes[1] & 0xFF) >> 1;
            }
            case 4 -> {
                upper = (((addrBytes[0] & 0xFF) >> 1) << 7) | ((addrBytes[1] & 0xFF) >> 1);
                yield (((addrBytes[2] & 0xFF) >> 1) << 7) | ((addrBytes[3] & 0xFF) >> 1);
            }
            default -> throw new HDLCFrameException("Unsupported address length: " + length);
        };

        return new HDLCAddress(addrBytes, upper, lower, length);
    }

    // -------------------------------------------------------------------------
    // Constructor (private)
    // -------------------------------------------------------------------------

    private HDLCAddress(byte[] encoded, int upper, int lower, int size) {
        this.encoded = encoded;
        this.upper   = upper;
        this.lower   = lower;
        this.size    = size;
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /** Returns the encoded address bytes, ready to insert into a frame. */
    public byte[] getEncoded()    { return Arrays.copyOf(encoded, encoded.length); }

    /** Number of encoded bytes: 1, 2, or 4. */
    public int    getSize()       { return size; }

    /** Upper (logical device) address component. 0 for 1-byte addresses. */
    public int    getUpper()      { return upper; }

    /** Lower (physical device) address component. */
    public int    getLower()      { return lower; }

    @Override
    public String toString() {
        if (size == 1) return String.valueOf(lower);
        return upper + "/" + lower;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HDLCAddress)) return false;
        return Arrays.equals(encoded, ((HDLCAddress) o).encoded);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(encoded);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static HDLCAddress oneByteAddress(int address) {
        // Single byte: shift left by 1, set LSB = 1 (last byte)
        byte b = (byte) (((address << 1) & 0xFE) | 1);
        return new HDLCAddress(new byte[]{b}, 0, address, 1);
    }
}