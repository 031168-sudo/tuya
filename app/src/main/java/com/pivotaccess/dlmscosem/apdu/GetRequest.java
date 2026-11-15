package com.pivotaccess.dlmscosem.apdu;

import java.io.ByteArrayOutputStream;

/**
 * xDLMS GET-Request APDU as defined in IEC 62056-53.
 *
 * <p>Supports:
 * <ul>
 *   <li>{@link #normal} — GET-Request-Normal: reads a single attribute</li>
 *   <li>{@link #next}   — GET-Request-Next: retrieves the next block of a
 *       segmented response</li>
 * </ul>
 *
 * <h3>Wire format — GET-Request-Normal</h3>
 * <pre>
 * C0          ← GET-Request tag
 * 01          ← GET-Request-Normal subtype
 * &lt;invoke-id&gt; ← 1 byte (invoke-id-and-priority)
 * &lt;class-id&gt;  ← 2 bytes (big-endian)
 * &lt;obis&gt;      ← 6 bytes OBIS code
 * &lt;attr-id&gt;   ← 1 byte attribute index
 * 00          ← access-selection: absent
 * </pre>
 */
public final class GetRequest {

    private static final int TAG_GET_REQUEST   = 0xC0;
    private static final int SUBTYPE_NORMAL    = 0x01;
    private static final int SUBTYPE_NEXT      = 0x02;

    // -------------------------------------------------------------------------

    private final int    subtype;
    private final int    invokeId;
    private final byte[] encodedBody;

    private GetRequest(int subtype, int invokeId, byte[] encodedBody) {
        this.subtype     = subtype;
        this.invokeId    = invokeId;
        this.encodedBody = encodedBody;
    }

    /**
     * Public constructor for pre-encoded requests (e.g. selective access).
     * The {@code preEncoded} array is the complete wire-format APDU — {@link #encode()}
     * returns it as-is.
     *
     * @param invokeId   invoke-id carried in this request
     * @param preEncoded complete encoded APDU bytes
     */
    public GetRequest(int invokeId, byte[] preEncoded) {
        this.subtype     = 0x01;     // NORMAL — metadata only
        this.invokeId    = invokeId;
        this.encodedBody = preEncoded; // store full APDU; encode() returns it directly
    }

    // -------------------------------------------------------------------------
    // Factories
    // -------------------------------------------------------------------------

    /**
     * Creates a GET-Request-Normal for a single COSEM attribute.
     *
     * @param invokeId request identifier (1–15); echoed back in the response
     * @param classId  COSEM interface class ID (e.g. 3 for Register)
     * @param obisCode 6-byte OBIS code
     * @param attrId   attribute index (e.g. 2 = value)
     */
    public static GetRequest normal(int invokeId, int classId,
                                    byte[] obisCode, int attrId) {
        if (obisCode.length != 6) {
            throw new IllegalArgumentException("OBIS code must be exactly 6 bytes");
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream(12);
        body.write((classId >> 8) & 0xFF);
        body.write(classId & 0xFF);
        body.write(obisCode, 0, 6);
        body.write(attrId & 0xFF);
        body.write(0x00);   // access-selection: absent
        return new GetRequest(SUBTYPE_NORMAL, invokeId, body.toByteArray());
    }

    /**
     * Creates a GET-Request-Next to retrieve the next block of a segmented
     * response (used when GET-Response-With-Datablock arrives with lastBlock=false).
     *
     * @param invokeId    same invoke-id as the original GET-Request-Normal
     * @param blockNumber block number from the GET-Response-With-Datablock
     */
    public static GetRequest next(int invokeId, long blockNumber) {
        ByteArrayOutputStream body = new ByteArrayOutputStream(4);
        body.write((int) ((blockNumber >> 24) & 0xFF));
        body.write((int) ((blockNumber >> 16) & 0xFF));
        body.write((int) ((blockNumber >>  8) & 0xFF));
        body.write((int) (blockNumber         & 0xFF));
        return new GetRequest(SUBTYPE_NEXT, invokeId, body.toByteArray());
    }

    // -------------------------------------------------------------------------
    // Encoding
    // -------------------------------------------------------------------------

    /**
     * Encodes this GET-Request into wire-format bytes.
     *
     * <p>When this instance was created via the pre-encoded constructor
     * (for selective access), the stored bytes are returned directly.
     *
     * @return encoded APDU bytes
     */
    public byte[] encode() {
        // Pre-encoded path (selective access): encodedBody IS the full APDU
        if (subtype == 0x01 && encodedBody.length > 3
                && (encodedBody[0] & 0xFF) == TAG_GET_REQUEST) {
            return java.util.Arrays.copyOf(encodedBody, encodedBody.length);
        }
        ByteArrayOutputStream out =
                new ByteArrayOutputStream(encodedBody.length + 3);
        out.write(TAG_GET_REQUEST);
        out.write(subtype);
        out.write(invokeIdAndPriority());
        out.write(encodedBody, 0, encodedBody.length);
        return out.toByteArray();
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public int getInvokeId() { return invokeId; }
    public int getSubtype()  { return subtype; }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Invoke-id-and-priority byte:
     * bits 7–6 = 11 (confirmed service class)
     * bits 5–4 = 00 (normal priority)
     * bits 3–0 = invoke-id
     */
    private int invokeIdAndPriority() {
        return 0xC0 | (invokeId & 0x0F);
    }
}