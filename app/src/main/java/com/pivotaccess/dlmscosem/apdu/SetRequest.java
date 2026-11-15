package com.pivotaccess.dlmscosem.apdu;

import java.io.ByteArrayOutputStream;

/**
 * xDLMS SET-Request APDU as defined in IEC 62056-53.
 *
 * <p>Writes a {@link DataObject} value to a COSEM attribute on the meter.
 *
 * <h3>Wire format — SET-Request-Normal</h3>
 * <pre>
 * C1          ← SET-Request tag
 * 01          ← SET-Request-Normal subtype
 * &lt;invoke-id&gt; ← 1 byte (invoke-id-and-priority)
 * &lt;class-id&gt;  ← 2 bytes (big-endian)
 * &lt;obis&gt;      ← 6 bytes OBIS code
 * &lt;attr-id&gt;   ← 1 byte attribute index
 * 00          ← access-selection: absent
 * &lt;value&gt;     ← DataObject (type tag + length + value bytes)
 * </pre>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * DataObject newTime = DataObject.ofDateTime(timeBytes);
 * byte[] encoded = SetRequest.normal(1, 8,
 *         new byte[]{0,0,1,0,0,(byte)255}, 2, newTime).encode();
 * }</pre>
 */
public final class SetRequest {

    private static final int TAG_SET_REQUEST = 0xC1;
    private static final int SUBTYPE_NORMAL  = 0x01;

    // -------------------------------------------------------------------------

    private final int        invokeId;
    private final int        classId;
    private final byte[]     obisCode;
    private final int        attrId;
    private final DataObject value;

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    /**
     * Creates a SET-Request-Normal.
     *
     * @param invokeId request identifier (1–15)
     * @param classId  COSEM interface class ID
     * @param obisCode 6-byte OBIS code
     * @param attrId   attribute index
     * @param value    the value to write
     */
    public static SetRequest normal(int invokeId, int classId,
                                    byte[] obisCode, int attrId,
                                    DataObject value) {
        if (obisCode.length != 6) {
            throw new IllegalArgumentException("OBIS code must be exactly 6 bytes");
        }
        return new SetRequest(invokeId, classId, obisCode, attrId, value);
    }

    private SetRequest(int invokeId, int classId, byte[] obisCode,
                       int attrId, DataObject value) {
        this.invokeId = invokeId;
        this.classId  = classId;
        this.obisCode = obisCode;
        this.attrId   = attrId;
        this.value    = value;
    }

    // -------------------------------------------------------------------------
    // Encoding
    // -------------------------------------------------------------------------

    /**
     * Encodes this SET-Request into wire-format bytes.
     *
     * @return encoded APDU bytes
     * @throws ApduException if the value cannot be encoded
     */
    public byte[] encode() throws ApduException {
        byte[] encodedValue = value.encode();

        ByteArrayOutputStream out =
                new ByteArrayOutputStream(12 + encodedValue.length);
        out.write(TAG_SET_REQUEST);
        out.write(SUBTYPE_NORMAL);
        out.write(invokeIdAndPriority());
        out.write((classId >> 8) & 0xFF);
        out.write(classId & 0xFF);
        out.write(obisCode, 0, 6);
        out.write(attrId & 0xFF);
        out.write(0x00);   // access-selection: absent
        out.write(encodedValue, 0, encodedValue.length);

        return out.toByteArray();
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public int        getInvokeId() { return invokeId; }
    public int        getClassId()  { return classId; }
    public int        getAttrId()   { return attrId; }
    public DataObject getValue()    { return value; }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private int invokeIdAndPriority() {
        return 0xC0 | (invokeId & 0x0F);
    }
}