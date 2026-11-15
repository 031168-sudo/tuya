package com.pivotaccess.dlmscosem.apdu;

/**
 * DLMS/COSEM data type tags as defined in IEC 62056-62 Table 2.
 *
 * <p>Each type has a unique tag byte that appears as the first byte of
 * an encoded {@link DataObject} in a DLMS APDU.
 */
public enum DlmsDataType {

    /** No value. Tag: 0x00 */
    NULL_DATA           (0x00),

    /** Array of DataObjects, all the same type. Tag: 0x01 */
    ARRAY               (0x01),

    /** Structure of DataObjects, potentially different types. Tag: 0x02 */
    STRUCTURE           (0x02),

    /** Boolean — true or false. Tag: 0x03 */
    BOOLEAN             (0x03),

    /** Bit string. Tag: 0x04 */
    BIT_STRING          (0x04),

    /** 32-bit signed integer. Tag: 0x05 */
    DOUBLE_LONG         (0x05),

    /** 32-bit unsigned integer. Tag: 0x06 */
    DOUBLE_LONG_UNSIGNED(0x06),

    /** Octet string (raw bytes). Tag: 0x09 */
    OCTET_STRING        (0x09),

    /** Visible string (ASCII). Tag: 0x0A */
    VISIBLE_STRING      (0x0A),

    /** UTF-8 string. Tag: 0x0C */
    UTF8_STRING         (0x0C),

    /** BCD — binary coded decimal. Tag: 0x0D */
    BCD                 (0x0D),

    /** 8-bit signed integer. Tag: 0x0F */
    INTEGER             (0x0F),

    /** 16-bit signed integer. Tag: 0x10 */
    LONG                (0x10),

    /** 8-bit unsigned integer. Tag: 0x11 */
    UNSIGNED            (0x11),

    /** 16-bit unsigned integer. Tag: 0x12 */
    LONG_UNSIGNED       (0x12),

    /** Compact array (type description + array of values). Tag: 0x13 */
    COMPACT_ARRAY       (0x13),

    /** 64-bit signed integer. Tag: 0x14 */
    LONG64              (0x14),

    /** 64-bit unsigned integer. Tag: 0x15 */
    LONG64_UNSIGNED     (0x15),

    /** Enumeration (8-bit unsigned, with semantic meaning). Tag: 0x16 */
    ENUM                (0x16),

    /** 32-bit IEEE 754 float. Tag: 0x17 */
    FLOAT32             (0x17),

    /** 64-bit IEEE 754 float. Tag: 0x18 */
    FLOAT64             (0x18),

    /** Date-time: 12 bytes (year, month, day, weekday, hour, min, sec, hundredths, deviation, status). Tag: 0x19 */
    DATE_TIME           (0x19),

    /** Date: 5 bytes (year, month, day, weekday). Tag: 0x1A */
    DATE                (0x1A),

    /** Time: 4 bytes (hour, min, sec, hundredths). Tag: 0x1B */
    TIME                (0x1B),

    /** Don't-care value — used in selective access. Tag: 0xFF */
    DONT_CARE           (0xFF);

    // -------------------------------------------------------------------------

    private final int tag;

    DlmsDataType(int tag) {
        this.tag = tag;
    }

    /** The raw tag byte for this type. */
    public int getTag() {
        return tag;
    }

    /**
     * Looks up a {@link DlmsDataType} by its raw tag byte.
     *
     * @param tag raw type tag
     * @return matching type
     * @throws ApduException if no type matches the tag
     */
    public static DlmsDataType fromTag(int tag) throws ApduException {
        for (DlmsDataType t : values()) {
            if (t.tag == tag) return t;
        }
        throw new ApduException(String.format("Unknown DLMS data type tag: 0x%02X", tag));
    }
}