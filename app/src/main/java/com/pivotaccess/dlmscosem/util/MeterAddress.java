package com.pivotaccess.dlmscosem.util;

import com.pivotaccess.dlmscosem.hdlc.HDLCAddress;

/**
 * Unified meter address that works for both HDLC and WRAPPER framing modes.
 *
 * <p>When using HDLC (RS-485 or TCP+HDLC), meters are addressed via a
 * multibyte {@link HDLCAddress}. When using WRAPPER (TCP+WRAPPER), meters
 * are addressed via a 16-bit wPort integer defined in IEC 62056-47.
 *
 * <p>{@link com.pivotaccess.dlmscosem.cosem.CosemClientConfig} accepts
 * {@code MeterAddress} for both {@code serverAddress()} and
 * {@code clientAddress()}, so you never need to know which addressing scheme
 * is in use at the call site.
 *
 * <h3>HDLC examples</h3>
 * <pre>{@code
 * // Client (public client, 1-byte address)
 * MeterAddress client = MeterAddress.hdlcClient(16);
 *
 * // Server — 1-byte physical address (small networks)
 * MeterAddress server = MeterAddress.hdlcServer(3);
 *
 * // Server — 2-byte address (upper=logical device, lower=physical)
 * MeterAddress server = MeterAddress.hdlcServer(1, 3);
 *
 * // From an existing HDLCAddress
 * MeterAddress server = MeterAddress.hdlc(HDLCAddress.serverTwoBytes(1, 3));
 * }</pre>
 *
 * <h3>WRAPPER examples</h3>
 * <pre>{@code
 * MeterAddress client = MeterAddress.wrapperClient(16);  // source wPort
 * MeterAddress server = MeterAddress.wrapperServer(1);   // destination wPort
 * }</pre>
 */
public final class MeterAddress {

    /** The addressing mode carried by this instance. */
    public enum Mode { HDLC, WRAPPER }

    // -------------------------------------------------------------------------

    private final Mode        mode;
    private final HDLCAddress hdlcAddress;  // non-null when mode == HDLC
    private final int         wPort;        // meaningful when mode == WRAPPER

    // -------------------------------------------------------------------------
    // Private constructor
    // -------------------------------------------------------------------------

    private MeterAddress(Mode mode, HDLCAddress hdlcAddress, int wPort) {
        this.mode        = mode;
        this.hdlcAddress = hdlcAddress;
        this.wPort       = wPort;
    }

    // -------------------------------------------------------------------------
    // HDLC factories
    // -------------------------------------------------------------------------

    /**
     * Creates an HDLC-mode address from an existing {@link HDLCAddress}.
     */
    public static MeterAddress hdlc(HDLCAddress address) {
        if (address == null) throw new IllegalArgumentException("HDLCAddress must not be null");
        return new MeterAddress(Mode.HDLC, address, 0);
    }

    /**
     * Creates a 1-byte HDLC client address (e.g. {@code 16} for public client).
     *
     * @param address client address value
     */
    public static MeterAddress hdlcClient(int address) {
        return hdlc(HDLCAddress.client(address));
    }

    /**
     * Creates a 1-byte HDLC server address (physical address only).
     * Suitable for small networks where the meter has a single-byte bus address.
     *
     * @param physicalAddress meter's RS-485 bus address (1–126)
     */
    public static MeterAddress hdlcServer(int physicalAddress) {
        return hdlc(HDLCAddress.serverOneByte(physicalAddress));
    }

    /**
     * Creates a 2-byte HDLC server address (upper logical + lower physical).
     *
     * @param upperAddress logical device address (e.g. 1 = management logical device)
     * @param lowerAddress physical device address (meter's bus position)
     */
    public static MeterAddress hdlcServer(int upperAddress, int lowerAddress) {
        return hdlc(HDLCAddress.serverTwoBytes(upperAddress, lowerAddress));
    }

    /**
     * Creates a 4-byte HDLC server address for large networks.
     *
     * @param upperAddress logical device address
     * @param lowerAddress physical device address
     */
    public static MeterAddress hdlcServerExtended(int upperAddress, int lowerAddress) {
        return hdlc(HDLCAddress.serverFourBytes(upperAddress, lowerAddress));
    }

    // -------------------------------------------------------------------------
    // WRAPPER factories
    // -------------------------------------------------------------------------

    /**
     * Creates a WRAPPER-mode client address (source wPort).
     *
     * @param wPort client logical address / source wPort (e.g. {@code 16})
     */
    public static MeterAddress wrapperClient(int wPort) {
        return new MeterAddress(Mode.WRAPPER, null, wPort);
    }

    /**
     * Creates a WRAPPER-mode server address (destination wPort).
     *
     * @param wPort server logical address / destination wPort (e.g. {@code 1})
     */
    public static MeterAddress wrapperServer(int wPort) {
        return new MeterAddress(Mode.WRAPPER, null, wPort);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /** Returns the addressing mode ({@link Mode#HDLC} or {@link Mode#WRAPPER}). */
    public Mode getMode() { return mode; }

    /** Returns {@code true} if this is an HDLC address. */
    public boolean isHdlc() { return mode == Mode.HDLC; }

    /** Returns {@code true} if this is a WRAPPER wPort address. */
    public boolean isWrapper() { return mode == Mode.WRAPPER; }

    /**
     * Returns the underlying {@link HDLCAddress}.
     *
     * @throws IllegalStateException if this is a WRAPPER address
     */
    public HDLCAddress asHdlcAddress() {
        if (mode != Mode.HDLC) {
            throw new IllegalStateException(
                    "Cannot get HDLCAddress from a WRAPPER MeterAddress");
        }
        return hdlcAddress;
    }

    /**
     * Returns the WRAPPER wPort value.
     *
     * @throws IllegalStateException if this is an HDLC address
     */
    public int asWrapperPort() {
        if (mode != Mode.WRAPPER) {
            throw new IllegalStateException(
                    "Cannot get wPort from an HDLC MeterAddress");
        }
        return wPort;
    }

    // -------------------------------------------------------------------------
    // Object overrides
    // -------------------------------------------------------------------------

    @Override
    public String toString() {
        if (mode == Mode.HDLC) {
            return "MeterAddress[HDLC:" + hdlcAddress + "]";
        }
        return "MeterAddress[WRAPPER:wPort=" + wPort + "]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MeterAddress)) return false;
        MeterAddress other = (MeterAddress) o;
        if (mode != other.mode) return false;
        if (mode == Mode.HDLC) return hdlcAddress.equals(other.hdlcAddress);
        return wPort == other.wPort;
    }

    @Override
    public int hashCode() {
        if (mode == Mode.HDLC) return hdlcAddress.hashCode();
        return Integer.hashCode(wPort);
    }
}