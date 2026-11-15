package com.pivotaccess.dlmscosem.apdu;

import java.io.ByteArrayOutputStream;

/**
 * xDLMS ACTION-Request APDU as defined in IEC 62056-53.
 *
 * <p>Invokes a method on a COSEM object. Used for operations such as
 * resetting billing registers, opening/closing a relay (disconnect/reconnect),
 * or synchronising the clock.
 *
 * <h3>Wire format — ACTION-Request-Normal</h3>
 * <pre>
 * C3          ← ACTION-Request tag
 * 01          ← ACTION-Request-Normal subtype
 * &lt;invoke-id&gt; ← 1 byte (invoke-id-and-priority)
 * &lt;class-id&gt;  ← 2 bytes (big-endian)
 * &lt;obis&gt;      ← 6 bytes OBIS code
 * &lt;method-id&gt; ← 1 byte method index
 * 01          ← method-invocation-parameters: present (0x00 = absent)
 * &lt;parameter&gt; ← DataObject (only when present flag = 0x01)
 * </pre>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // Reset billing registers — no parameter
 * byte[] req = ActionRequest.normal(1, 3,
 *         new byte[]{1,0,1,8,0,(byte)255}, 1).encode();
 *
 * // Disconnect relay — with parameter
 * byte[] req = ActionRequest.normal(1, 70,
 *         new byte[]{0,0,96,3,10,(byte)255}, 1,
 *         DataObject.ofBoolean(false)).encode();
 * }</pre>
 */
public final class ActionRequest {

    private static final int TAG_ACTION_REQUEST = 0xC3;
    private static final int SUBTYPE_NORMAL     = 0x01;

    // -------------------------------------------------------------------------

    private final int        invokeId;
    private final int        classId;
    private final byte[]     obisCode;
    private final int        methodId;
    private final DataObject parameter;   // null = method takes no parameter

    // -------------------------------------------------------------------------
    // Factories
    // -------------------------------------------------------------------------

    /**
     * Creates an ACTION-Request-Normal with a parameter.
     *
     * @param invokeId  request identifier (1–15)
     * @param classId   COSEM interface class ID
     * @param obisCode  6-byte OBIS code
     * @param methodId  method index (e.g. 1 = reset)
     * @param parameter method parameter DataObject
     */
    public static ActionRequest normal(int invokeId, int classId,
                                       byte[] obisCode, int methodId,
                                       DataObject parameter) {
        if (obisCode.length != 6) {
            throw new IllegalArgumentException("OBIS code must be exactly 6 bytes");
        }
        return new ActionRequest(invokeId, classId, obisCode, methodId, parameter);
    }

    /**
     * Creates an ACTION-Request-Normal with no parameter.
     *
     * @param invokeId request identifier (1–15)
     * @param classId  COSEM interface class ID
     * @param obisCode 6-byte OBIS code
     * @param methodId method index
     */
    public static ActionRequest normal(int invokeId, int classId,
                                       byte[] obisCode, int methodId) {
        return normal(invokeId, classId, obisCode, methodId, null);
    }

    private ActionRequest(int invokeId, int classId, byte[] obisCode,
                          int methodId, DataObject parameter) {
        this.invokeId  = invokeId;
        this.classId   = classId;
        this.obisCode  = obisCode;
        this.methodId  = methodId;
        this.parameter = parameter;
    }

    // -------------------------------------------------------------------------
    // Encoding
    // -------------------------------------------------------------------------

    /**
     * Encodes this ACTION-Request into wire-format bytes.
     *
     * @return encoded APDU bytes
     * @throws ApduException if the parameter cannot be encoded
     */
    public byte[] encode() throws ApduException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16);

        out.write(TAG_ACTION_REQUEST);
        out.write(SUBTYPE_NORMAL);
        out.write(invokeIdAndPriority());
        out.write((classId >> 8) & 0xFF);
        out.write(classId & 0xFF);
        out.write(obisCode, 0, 6);
        out.write(methodId & 0xFF);

        if (parameter != null) {
            out.write(0x01);   // method-invocation-parameters: present
            byte[] encodedParam = parameter.encode();
            out.write(encodedParam, 0, encodedParam.length);
        } else {
            out.write(0x00);   // method-invocation-parameters: absent
        }

        return out.toByteArray();
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public int        getInvokeId()  { return invokeId; }
    public int        getClassId()   { return classId; }
    public int        getMethodId()  { return methodId; }
    public DataObject getParameter() { return parameter; }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private int invokeIdAndPriority() {
        return 0xC0 | (invokeId & 0x0F);
    }
}