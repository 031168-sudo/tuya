package com.pivotaccess.dlmscosem.apdu;

import java.util.Arrays;

/**
 * xDLMS GET-Response APDU as defined in IEC 62056-53.
 *
 * <p>Handles three subtypes returned by the meter:
 * <ul>
 *   <li>{@code 0x01} GET-Response-Normal — single attribute value</li>
 *   <li>{@code 0x02} GET-Response-With-Datablock — one block of a segmented
 *       response; use {@link GetRequest#next} to retrieve subsequent blocks</li>
 *   <li>{@code 0x03} GET-Response-With-List — multiple values (not yet
 *       implemented; extend as needed)</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * byte[] raw = session.sendReceive(getRequest.encode());
 * GetResponse resp = GetResponse.decode(raw);
 * resp.assertSuccess();
 * DataObject value = resp.getValue();
 * }</pre>
 */
public final class GetResponse {

    private static final int TAG_GET_RESPONSE          = 0xC4;
    private static final int SUBTYPE_NORMAL            = 0x01;
    private static final int SUBTYPE_WITH_DATABLOCK    = 0x02;
    private static final int DATA_ACCESS_SUCCESS       = 0x00;

    // -------------------------------------------------------------------------

    private final int        invokeId;
    private final int        subtype;
    private final DataObject value;          // non-null for SUBTYPE_NORMAL success
    private final long       blockNumber;    // for SUBTYPE_WITH_DATABLOCK
    private final boolean    lastBlock;      // true = no more blocks
    private final byte[]     rawBlockData;   // raw bytes for SUBTYPE_WITH_DATABLOCK
    private final int        accessResult;   // 0 = success
    private final boolean    success;

    // -------------------------------------------------------------------------
    // Private constructor
    // -------------------------------------------------------------------------

    private GetResponse(int invokeId, int subtype, DataObject value,
                        long blockNumber, boolean lastBlock, byte[] rawBlockData,
                        int accessResult) {
        this.invokeId     = invokeId;
        this.subtype      = subtype;
        this.value        = value;
        this.blockNumber  = blockNumber;
        this.lastBlock    = lastBlock;
        this.rawBlockData = rawBlockData;
        this.accessResult = accessResult;
        this.success      = (accessResult == DATA_ACCESS_SUCCESS);
    }

    // -------------------------------------------------------------------------
    // Decoding
    // -------------------------------------------------------------------------

    /**
     * Decodes a GET-Response APDU from raw bytes.
     *
     * @param data raw bytes as returned by the HDLC session
     * @return decoded {@link GetResponse}
     * @throws ApduException if the bytes are not a valid GET-Response
     */
    public static GetResponse decode(byte[] data) throws ApduException {
        if (data == null || data.length < 4) {
            throw new ApduException("GET-Response too short: "
                    + (data == null ? 0 : data.length));
        }
        if ((data[0] & 0xFF) != TAG_GET_RESPONSE) {
            throw new ApduException(String.format(
                    "Not a GET-Response: expected 0x%02X, got 0x%02X",
                    TAG_GET_RESPONSE, data[0]));
        }

        int subtype  = data[1] & 0xFF;
        int invokeId = data[2] & 0x0F;
        int pos      = 3;

        switch (subtype) {
            case SUBTYPE_NORMAL:
                return decodeNormal(data, pos, invokeId);
            case SUBTYPE_WITH_DATABLOCK:
                return decodeDatablock(data, pos, invokeId);
            default:
                throw new ApduException(
                        "Unsupported GET-Response subtype: 0x"
                                + Integer.toHexString(subtype));
        }
    }

    // -------------------------------------------------------------------------
    // Subtype decoders
    // -------------------------------------------------------------------------

    private static GetResponse decodeNormal(byte[] data, int pos, int invokeId)
            throws ApduException {
        if (pos >= data.length) {
            throw new ApduException("GET-Response-Normal: truncated at choice tag");
        }

        int choice = data[pos] & 0xFF;
        pos++;

        if (choice == 0x01) {
            // 0x01 means data-access-result follows
            if (pos >= data.length) throw new ApduException("Missing data-access-result code");
            int accessResult = data[pos] & 0xFF;
            return new GetResponse(invokeId, SUBTYPE_NORMAL,
                    null, 0, true, null, accessResult);
        } else if (choice == 0x00) {
            // 0x00 means data follows
            DataObject value = DataObject.decode(data, pos);
            return new GetResponse(invokeId, SUBTYPE_NORMAL,
                    value, 0, true, null, DATA_ACCESS_SUCCESS);
        } else {
            throw new ApduException("Unknown GetDataResult choice tag: " + choice);
        }
    }

    private static GetResponse decodeDatablock(byte[] data, int pos, int invokeId)
            throws ApduException {
        if (pos + 5 > data.length) {
            throw new ApduException("GET-Response-With-Datablock: truncated");
        }

        // last-block flag (0x00 = more blocks, 0x01 = last block)
        boolean lastBlock = (data[pos] & 0xFF) == 0x01;
        pos++;

        // block-number (uint32, big-endian)
        long blockNumber = ((data[pos]     & 0xFFL) << 24)
                | ((data[pos + 1] & 0xFFL) << 16)
                | ((data[pos + 2] & 0xFFL) << 8)
                |  (data[pos + 3] & 0xFFL);
        pos += 4;

        // raw-data: OCTET STRING (tag 0x09 + BER length + bytes)
        if ((data[pos] & 0xFF) != 0x09) {
            throw new ApduException(String.format(
                    "GET-Response-With-Datablock: expected OCTET STRING tag 0x09, got 0x%02X",
                    data[pos]));
        }
        BerDecoder.LengthResult lr = BerDecoder.readLength(data, pos + 1);
        byte[] rawData = Arrays.copyOfRange(data,
                lr.nextOffset, lr.nextOffset + lr.length);

        return new GetResponse(invokeId, SUBTYPE_WITH_DATABLOCK,
                null, blockNumber, lastBlock, rawData, DATA_ACCESS_SUCCESS);
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
                    "GET failed — data-access-result: "
                            + accessResultDescription(accessResult));
        }
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /** The decoded attribute value. Only valid after a successful Normal response. */
    public DataObject getValue()       { return value; }

    /** True if the meter returned a success result. */
    public boolean    isSuccess()      { return success; }

    /** The data-access-result code (0 = success). */
    public int        getAccessResult(){ return accessResult; }

    /** Invoke-id echoed from the request. */
    public int        getInvokeId()    { return invokeId; }

    /** Response subtype (0x01 = Normal, 0x02 = WithDatablock). */
    public int        getSubtype()     { return subtype; }

    /** Block number for a With-Datablock response. */
    public long       getBlockNumber() { return blockNumber; }

    /** True if no more datablock responses will follow. */
    public boolean    isLastBlock()    { return lastBlock; }

    /** Raw block bytes for a With-Datablock response. */
    public byte[]     getRawBlockData(){
        return rawBlockData == null ? null
                : Arrays.copyOf(rawBlockData, rawBlockData.length);
    }

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
            case 13 -> "data-block-unavailable";
            case 14 -> "long-get-aborted";
            case 15 -> "no-long-get-in-progress";
            case 250 -> "other-reason";
            default -> "unknown(" + code + ")";
        };
    }
}