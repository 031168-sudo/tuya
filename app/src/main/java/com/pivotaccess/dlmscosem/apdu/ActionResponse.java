package com.pivotaccess.dlmscosem.apdu;

/**
 * xDLMS ACTION-Response APDU as defined in IEC 62056-53.
 *
 * <p>Returned by the meter after an {@link ActionRequest}. Contains an
 * action-result code and an optional return value.
 *
 * <h3>Wire format — ACTION-Response-Normal</h3>
 * <pre>
 * C7          ← ACTION-Response tag
 * 01          ← ACTION-Response-Normal subtype
 * &lt;invoke-id&gt; ← 1 byte (invoke-id-and-priority)
 * &lt;result&gt;    ← 1 byte action-result (0x00 = success)
 * &lt;ret-flag&gt;  ← 1 byte: 0x00 = no return value, 0x01 = value present
 * [&lt;ret-tag&gt;] ← 0x00 = data, 0x01 = data-access-result
 * [&lt;value&gt;]   ← DataObject (when ret-flag = 0x01 and ret-tag = 0x00)
 * </pre>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * byte[] raw = session.sendReceive(actionRequest.encode());
 * ActionResponse resp = ActionResponse.decode(raw);
 * resp.assertSuccess();
 * // optionally: resp.getReturnValue()
 * }</pre>
 */
public final class ActionResponse {

    private static final int TAG_ACTION_RESPONSE   = 0xC7;
    private static final int SUBTYPE_NORMAL        = 0x01;
    private static final int ACTION_RESULT_SUCCESS = 0x00;

    // -------------------------------------------------------------------------

    private final int        invokeId;
    private final int        actionResult;
    private final DataObject returnValue;   // may be null
    private final boolean    success;

    // -------------------------------------------------------------------------
    // Private constructor
    // -------------------------------------------------------------------------

    private ActionResponse(int invokeId, int actionResult, DataObject returnValue) {
        this.invokeId     = invokeId;
        this.actionResult = actionResult;
        this.returnValue  = returnValue;
        this.success      = (actionResult == ACTION_RESULT_SUCCESS);
    }

    // -------------------------------------------------------------------------
    // Decoding
    // -------------------------------------------------------------------------

    /**
     * Decodes an ACTION-Response APDU from raw bytes.
     *
     * @param data raw bytes as returned by the HDLC session
     * @return decoded {@link ActionResponse}
     * @throws ApduException if the bytes are not a valid ACTION-Response
     */
    public static ActionResponse decode(byte[] data) throws ApduException {
        if (data == null || data.length < 4) {
            throw new ApduException("ACTION-Response too short: "
                    + (data == null ? 0 : data.length));
        }
        if ((data[0] & 0xFF) != TAG_ACTION_RESPONSE) {
            throw new ApduException(String.format(
                    "Not an ACTION-Response: expected 0x%02X, got 0x%02X",
                    TAG_ACTION_RESPONSE, data[0]));
        }

        int subtype  = data[1] & 0xFF;
        int invokeId = data[2] & 0x0F;

        if (subtype != SUBTYPE_NORMAL) {
            throw new ApduException(
                    "Unsupported ACTION-Response subtype: 0x"
                            + Integer.toHexString(subtype));
        }

        int pos          = 3;
        int actionResult = data[pos] & 0xFF;
        pos++;

        // get-data-result (optional return value)
        // 0x00 = absent, 0x01 = data present, 0x02 = data-access-result present
        DataObject returnValue = null;
        if (pos < data.length) {
            int returnFlag = data[pos] & 0xFF;
            pos++;

            if (returnFlag == 0x01 && pos + 1 < data.length) {
                // Return data tag: 0x00 = data (DataObject follows)
                int returnTag = data[pos] & 0xFF;
                pos++;
                if (returnTag == 0x00) {
                    returnValue = DataObject.decode(data, pos);
                }
                // returnTag == 0x01 means data-access-result — not a DataObject
            }
        }

        return new ActionResponse(invokeId, actionResult, returnValue);
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
                    "ACTION failed — action-result: "
                            + actionResultDescription(actionResult));
        }
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public boolean    isSuccess()      { return success; }
    public int        getInvokeId()    { return invokeId; }
    public int        getActionResult(){ return actionResult; }

    /**
     * Returns the optional return value from the meter, or {@code null} if
     * the method produced no return value.
     */
    public DataObject getReturnValue() { return returnValue; }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String actionResultDescription(int code) {
        return switch (code) {
            case 0 -> "success";
            case 1 -> "hardware-fault";
            case 2 -> "temporary-failure";
            case 3 -> "read-write-denied";
            case 4 -> "object-undefined";
            case 9 -> "object-unavailable";
            case 11 -> "type-unmatched";
            case 250 -> "other-reason";
            default -> "unknown(" + code + ")";
        };
    }
}