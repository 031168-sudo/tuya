package com.pivotaccess.dlmscosem.cosem;

import java.util.Arrays;

/**
 * OBIS (Object Identification System) code as defined in IEC 62056-61.
 *
 * <p>An OBIS code uniquely identifies a COSEM object on a meter. It consists
 * of six value groups (A–F), each encoded as a single byte:
 *
 * <pre>
 * A . B . C . D . E . F
 * |   |   |   |   |   └─ billing period / tariff rate
 * |   |   |   |   └───── processing of values / storage
 * |   |   |   └───────── measurement type (integration, min, max...)
 * |   |   └───────────── measured quantity (active power, voltage...)
 * |   └───────────────── channel / input (L1, L2, L3, total...)
 * └───────────────────── medium (electricity=1, gas=7, water=8...)
 * </pre>
 *
 * <h3>Examples</h3>
 * <pre>{@code
 * ObisCode energy  = ObisCode.of("1.0.1.8.0.255");  // active energy import
 * ObisCode power   = ObisCode.of("1.0.1.7.0.255");  // active power
 * ObisCode voltage = ObisCode.of("1.0.32.7.0.255"); // voltage L1
 * ObisCode clock   = ObisCode.of("0.0.1.0.0.255");  // clock object
 * ObisCode serial  = ObisCode.of("0.0.96.1.0.255"); // meter serial number
 *
 * byte[] raw = energy.toBytes();  // {1, 0, 1, 8, 0, (byte)255}
 * String str = energy.toString(); // "1.0.1.8.0.255"
 * }</pre>
 */
public final class ObisCode {

    private final int a, b, c, d, e, f;

    // -------------------------------------------------------------------------
    // Common OBIS codes (electricity metering)
    // -------------------------------------------------------------------------

    /** Active energy import (+A), total. Class 3. */
    public static final ObisCode ACTIVE_ENERGY_IMPORT       = of("1.0.1.8.0.255");

    /** Active energy export (-A), total. Class 3. */
    public static final ObisCode ACTIVE_ENERGY_EXPORT       = of("1.0.2.8.0.255");

    /** Reactive energy import (+R), total. Class 3. */
    public static final ObisCode REACTIVE_ENERGY_IMPORT     = of("1.0.3.8.0.255");

    /** Reactive energy export (-R), total. Class 3. */
    public static final ObisCode REACTIVE_ENERGY_EXPORT     = of("1.0.4.8.0.255");

    /** Instantaneous active power (+P), total. Class 3. */
    public static final ObisCode ACTIVE_POWER               = of("1.0.1.7.0.255");

    /** Instantaneous active power export (-P), total. Class 3. */
    public static final ObisCode ACTIVE_POWER_EXPORT        = of("1.0.2.7.0.255");

    /** Instantaneous reactive power (+Q), total. Class 3. */
    public static final ObisCode REACTIVE_POWER             = of("1.0.3.7.0.255");

    /** Instantaneous apparent power (|S|), total. Class 3. */
    public static final ObisCode APPARENT_POWER             = of("1.0.9.7.0.255");

    /** Power factor, total. Class 3. */
    public static final ObisCode POWER_FACTOR               = of("1.0.13.7.0.255");

    /** Supply frequency. Class 3. */
    public static final ObisCode FREQUENCY                  = of("1.0.14.7.0.255");

    /** Voltage L1. Class 3. */
    public static final ObisCode VOLTAGE_L1                 = of("1.0.32.7.0.255");

    /** Voltage L2. Class 3. */
    public static final ObisCode VOLTAGE_L2                 = of("1.0.52.7.0.255");

    /** Voltage L3. Class 3. */
    public static final ObisCode VOLTAGE_L3                 = of("1.0.72.7.0.255");

    /** Current L1. Class 3. */
    public static final ObisCode CURRENT_L1                 = of("1.0.31.7.0.255");

    /** Current L2. Class 3. */
    public static final ObisCode CURRENT_L2                 = of("1.0.51.7.0.255");

    /** Current L3. Class 3. */
    public static final ObisCode CURRENT_L3                 = of("1.0.71.7.0.255");

    /** Clock object. Class 8. */
    public static final ObisCode CLOCK                      = of("0.0.1.0.0.255");

    /** Meter serial number. Class 1. */
    public static final ObisCode SERIAL_NUMBER              = of("0.0.96.1.0.255");

    /** Meter firmware version. Class 1. */
    public static final ObisCode FIRMWARE_VERSION           = of("1.0.0.2.0.255");

    /** Load profile 1 (standard interval profile). Class 7. */
    public static final ObisCode LOAD_PROFILE_1             = of("1.0.99.1.0.255");

    /** Load profile 2 (daily profile). Class 7. */
    public static final ObisCode LOAD_PROFILE_2             = of("1.0.99.2.0.255");

    /** Billing profile. Class 7. */
    public static final ObisCode BILLING_PROFILE            = of("1.0.98.1.0.255");

    /** Disconnect control object. Class 70. */
    public static final ObisCode DISCONNECT_CONTROL         = of("0.0.96.3.10.255");

    // -------------------------------------------------------------------------
    // Constructor (private)
    // -------------------------------------------------------------------------

    private ObisCode(int a, int b, int c, int d, int e, int f) {
        this.a = a; this.b = b; this.c = c;
        this.d = d; this.e = e; this.f = f;
    }

    // -------------------------------------------------------------------------
    // Factories
    // -------------------------------------------------------------------------

    /**
     * Parses an OBIS code from its dot-separated string representation.
     *
     * @param obis string in the form {@code "A.B.C.D.E.F"} (e.g. "1.0.1.8.0.255")
     * @return parsed {@link ObisCode}
     * @throws IllegalArgumentException if the string is malformed
     */
    public static ObisCode of(String obis) {
        String[] parts = obis.split("\\.");
        if (parts.length != 6) {
            throw new IllegalArgumentException(
                    "OBIS code must have 6 parts separated by '.': " + obis);
        }
        try {
            return new ObisCode(
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim()),
                    Integer.parseInt(parts[3].trim()),
                    Integer.parseInt(parts[4].trim()),
                    Integer.parseInt(parts[5].trim()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid OBIS code: " + obis, e);
        }
    }

    /**
     * Creates an OBIS code from a 6-byte array.
     *
     * @param bytes 6-byte array where bytes[0]=A ... bytes[5]=F
     * @return parsed {@link ObisCode}
     */
    public static ObisCode of(byte[] bytes) {
        if (bytes.length != 6) {
            throw new IllegalArgumentException(
                    "OBIS byte array must be exactly 6 bytes, got: " + bytes.length);
        }
        return new ObisCode(
                bytes[0] & 0xFF, bytes[1] & 0xFF, bytes[2] & 0xFF,
                bytes[3] & 0xFF, bytes[4] & 0xFF, bytes[5] & 0xFF);
    }

    /**
     * Creates an OBIS code from six integer values.
     */
    public static ObisCode of(int a, int b, int c, int d, int e, int f) {
        return new ObisCode(a, b, c, d, e, f);
    }

    // -------------------------------------------------------------------------
    // Encoding
    // -------------------------------------------------------------------------

    /**
     * Returns the OBIS code as a 6-byte array suitable for embedding
     * in a DLMS APDU.
     */
    public byte[] toBytes() {
        return new byte[]{
                (byte) a, (byte) b, (byte) c,
                (byte) d, (byte) e, (byte) f
        };
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public int getA() { return a; }
    public int getB() { return b; }
    public int getC() { return c; }
    public int getD() { return d; }
    public int getE() { return e; }
    public int getF() { return f; }

    // -------------------------------------------------------------------------
    // Object overrides
    // -------------------------------------------------------------------------

    @Override
    public String toString() {
        return a + "." + b + "." + c + "." + d + "." + e + "." + f;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ObisCode)) return false;
        ObisCode other = (ObisCode) o;
        return a == other.a && b == other.b && c == other.c
                && d == other.d && e == other.e && f == other.f;
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(toBytes());
    }
}