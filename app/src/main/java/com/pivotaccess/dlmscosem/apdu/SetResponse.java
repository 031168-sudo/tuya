package com.pivotaccess.dlmscosem.apdu;

/**
 * xDLMS SET-Response APDU as defined in IEC 62056-53.
 *
 * <p>Returned by the meter after a {@link SetRequest}. Contains a
 * data-access-result indicating success or the reason for failure.
 *
 * <h3>Wire format — SET-Response-Normal</h3>
 * <pre>
 * C5          ← SET-Response tag
 * 01          ← SET-Response-Normal subtype
 * &lt;invoke-id&gt; ← 1 byte (invoke-id-and-priority)
 * &lt;result&gt;    ← 1 byte data-access-result (0x00 = success)
 * </pre>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * byte[] raw = session.sendReceive(setRequest.encode());
 * SetResponse.decode(raw).assertSuccess();
 * }</pre>
 */
public final class SetResponse {

    private static final int TAG_SET_RESPONSE    = 0xC5;
    private static final int SUBTYPE_NORMAL      = 0x01;
    private static final int DATA_ACCESS_SUCCESS = 0x00;

    // -------------------------------------------------------------------------

    private final int     invokeId;
    private final int     dataAccessResult;
    private final boolean success;

    // -------------------------------------------------------------------------
    // Private constructor
    // -------------------------------------------------------------------------

    private SetResponse(int invokeId, int dataAccessResult) {
        this.invokeId         = invokeId;
        this.dataAccessResult = dataAccessResult;
        this.success          = (dataAccessResult == DATA_ACCESS_SUCCESS);
    }

    // -------------------------------------------------------------------------
    // Decoding
    // -------------------------------------------------------------------------

    /**
     * Decodes a SET-Response APDU from raw bytes.
     *
     * @param data raw bytes as returned by the HDLC session
     * @return decoded {@link SetResponse}
     * @throws ApduException if the bytes are not a valid SET-Response
     */
    public static SetResponse decode(byte[] data) throws ApduException {
        if (data == null || data.length < 4) {
            throw new ApduException("SET-Response too short: "
                    + (data == null ? 0 : data.length));
        }
        if ((data[0] & 0xFF) != TAG_SET_RESPONSE) {
            throw new ApduException(String.format(
                    "Not a SET-Response: expected 0x%02X, got 0x%02X",
                    TAG_SET_RESPONSE, data[0]));
        }

        int subtype  = data[1] & 0xFF;
        int invokeId = data[2] & 0x0F;

        if (subtype != SUBTYPE_NORMAL) {
            throw new ApduException(
                    "Unsupported SET-Response subtype: 0x"
                            + Integer.toHexString(subtype));
        }

        int dataAccessResult = data[3] & 0xFF;
        return new SetResponse(invokeId, dataAccessResult);
    }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    /**
     * Throws {@link ApduException} if the meter returned an error result.
     */
    public void assertSuccess() throws ApduException {
        if (!success) {
            throw new ApduException(
                    "SET failed — data-access-result: "
                            + accessResultDescription(dataAccessResult));
        }
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public boolean isSuccess()           { return success; }
    public int     getInvokeId()         { return invokeId; }
    public int     getDataAccessResult() { return dataAccessResult; }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String accessResultDescription(int code) {
        return switch (code) {
            case 1 -> "hardware-fault";
            case 2 -> "temporary-failure";
            case 3 -> "read-write-denied";
            case 4 -> "object-undefined";
            case 5 -> "object-class-inconsistent";
            case 9 -> "object-unavailable";
            case 11 -> "type-unmatched";
            case 12 -> "scope-of-access-violated";
            case 250 -> "other-reason";
            default -> "unknown(" + code + ")";
        };
    }
}