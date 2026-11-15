package com.pivotaccess.dlmscosem.apdu;

import java.util.Arrays;

/**
 * DLMS/COSEM Association Response APDU (AARE) as defined in IEC 62056-53.
 *
 * <p>The AARE is returned by the meter in response to an AARQ. It carries the
 * association result, diagnostic information on rejection, and the server's
 * xDLMS negotiation parameters.
 *
 * <h3>Key field: server-max-receive-pdu-size</h3>
 * <p>Exposed via {@link #getServerMaxReceivePduSize()}, this is the largest
 * single APDU payload the server can receive in one go.  The client must
 * respect this limit when encoding GET/SET/ACTION requests: if a request APDU
 * would exceed this size, the request must be split using the multi-block
 * (GET-Request-Next) mechanism.
 *
 * <h3>InitiateResponse wire layout (inside the BE user-information)</h3>
 * <pre>
 * 08              ← xDLMS PDU choice: Initiate.response
 * [01 XX]         ← negotiated-quality-of-service (OPTIONAL [0] IMPLICIT Integer8)
 * 06              ← negotiated-dlms-version-number = 6
 * 5F 1F 04        ← conformance tag + length (4 bytes)
 * 00 XX XX XX     ← unused-bits + 3 conformance bytes
 * SS SS           ← server-max-receive-pdu-size (big-endian uint16)
 * 00 07           ← vaa-name = 7 (LN referencing)
 * </pre>
 *
 * <h3>Result codes</h3>
 * <ul>
 *   <li>{@code 0} — accepted</li>
 *   <li>{@code 1} — rejected-permanent</li>
 *   <li>{@code 2} — rejected-transient</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * AareApdu aare = AareApdu.decode(aareBytes);
 * aare.assertAccepted();
 *
 * int serverMaxPdu = aare.getServerMaxReceivePduSize();
 * log.info("Server max receive PDU: {} bytes", serverMaxPdu);
 * }</pre>
 */
public final class AareApdu {

    // -------------------------------------------------------------------------
    // BER / ACSE tags
    // -------------------------------------------------------------------------

    private static final int TAG_AARE             = 0x61;  // APPLICATION 1, constructed
    private static final int TAG_APP_CONTEXT_NAME = 0xA1;
    private static final int TAG_RESULT           = 0xA2;
    private static final int TAG_RESULT_SOURCE    = 0xA3;
    private static final int TAG_USER_INFO        = 0xBE;
    private static final int TAG_OCTET_STRING     = 0x04;
    private static final int TAG_AUTH_VALUE       = 0xA8;  // [8] CONSTRUCTED (Explicit)
    private static final int TAG_AUTH_CHARSTR     = 0x80;

    /** xDLMS PDU tag for Initiate.response. */
    private static final int XDLMS_INITIATE_RESPONSE_TAG = 0x08;

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final int     result;
    private final int     resultSourceDiag;

    /**
     * The {@code server-max-receive-pdu-size} from the xDLMS InitiateResponse.
     * This is the largest APDU payload the server can accept from the client.
     */
    private final int     serverMaxReceivePduSize;
    private final byte[]  serverChallenge;
    private final boolean accepted;

    // -------------------------------------------------------------------------
    // Private constructor
    // -------------------------------------------------------------------------

    private AareApdu(int result, int resultSourceDiag,
                     int serverMaxReceivePduSize, byte[] serverChallenge) {
        this.result                  = result;
        this.resultSourceDiag        = resultSourceDiag;
        this.serverMaxReceivePduSize = serverMaxReceivePduSize;
        this.serverChallenge         = serverChallenge;
        this.accepted                = (result == 0);
    }

    // -------------------------------------------------------------------------
    // Decoding
    // -------------------------------------------------------------------------

    /**
     * Decodes an AARE APDU from raw bytes.
     *
     * @param data raw APDU bytes starting with {@code 0x61}
     * @return decoded {@link AareApdu}
     * @throws ApduException if the bytes are malformed or too short
     */
    public static AareApdu decode(byte[] data) throws ApduException {
        if (data == null || data.length < 4) {
            throw new ApduException(
                    "AARE too short: " + (data == null ? 0 : data.length));
        }
        if ((data[0] & 0xFF) != TAG_AARE) {
            throw new ApduException(String.format(
                    "Not an AARE: expected 0x%02X, got 0x%02X",
                    TAG_AARE, data[0]));
        }

        BerDecoder.LengthResult outerLen = BerDecoder.readLength(data, 1);
        int pos = outerLen.nextOffset;
        int end = pos + outerLen.length;

        int    result            = 0;
        int    resultSourceDiag  = 0;
        int    serverMaxPdu      = 0xFFFF;  // permissive default if not present
        byte[] serverChallenge   = null;

        while (pos < end) {
            int tag = data[pos] & 0xFF;

            switch (tag) {

                case TAG_APP_CONTEXT_NAME: {
                    // Skip — we trust the meter returned a valid context
                    BerDecoder.LengthResult lr = BerDecoder.readLength(data, pos + 1);
                    pos = lr.nextOffset + lr.length;
                    break;
                }

                case TAG_RESULT: {
                    // [2] result: CONTEXT constructed, contains INTEGER
                    BerDecoder.LengthResult lr = BerDecoder.readLength(data, pos + 1);
                    int innerStart = lr.nextOffset;
                    // inner: 02 01 <value>  (BER INTEGER, 1 byte)
                    if (innerStart + 2 < data.length
                            && (data[innerStart] & 0xFF) == 0x02
                            && (data[innerStart + 1] & 0xFF) == 0x01) {
                        result = data[innerStart + 2] & 0xFF;
                    }
                    pos = lr.nextOffset + lr.length;
                    break;
                }

                case TAG_RESULT_SOURCE: {
                    // [3] result-source-diagnostic
                    BerDecoder.LengthResult lr = BerDecoder.readLength(data, pos + 1);
                    int diagStart = lr.nextOffset;
                    // CHOICE: [1] acse-service-user or [2] acse-service-provider
                    if (diagStart + 1 < data.length) {
                        BerDecoder.LengthResult innerLen =
                                BerDecoder.readLength(data, diagStart + 1);
                        // inner: 02 01 <value>
                        int vStart = innerLen.nextOffset;
                        if (vStart + 2 < data.length
                                && (data[vStart] & 0xFF) == 0x02
                                && (data[vStart + 1] & 0xFF) == 0x01) {
                            resultSourceDiag = data[vStart + 2] & 0xFF;
                        }
                    }
                    pos = lr.nextOffset + lr.length;
                    break;
                }

                case TAG_AUTH_VALUE: {
                    // [8] responding-authentication-value (HIGH auth challenge)
                    BerDecoder.LengthResult lr = BerDecoder.readLength(data, pos + 1);
                    int innerStart = lr.nextOffset;
                    if (innerStart < data.length
                            && (data[innerStart] & 0xFF) == TAG_AUTH_CHARSTR) {
                        BerDecoder.LengthResult cLen =
                                BerDecoder.readLength(data, innerStart + 1);
                        serverChallenge = Arrays.copyOfRange(data,
                                cLen.nextOffset, cLen.nextOffset + cLen.length);
                    }
                    pos = lr.nextOffset + lr.length;
                    break;
                }

                case TAG_USER_INFO: {
                    // [30] user-information wraps InitiateResponse as OCTET STRING
                    BerDecoder.LengthResult lr = BerDecoder.readLength(data, pos + 1);
                    int uiStart = lr.nextOffset;
                    if (uiStart < data.length
                            && (data[uiStart] & 0xFF) == TAG_OCTET_STRING) {
                        BerDecoder.LengthResult octetLen =
                                BerDecoder.readLength(data, uiStart + 1);
                        byte[] initResp = Arrays.copyOfRange(data,
                                octetLen.nextOffset, octetLen.nextOffset + octetLen.length);
                        serverMaxPdu = decodeInitiateResponse(initResp);
                    }
                    pos = lr.nextOffset + lr.length;
                    break;
                }

                default: {
                    // Unknown/optional field — skip safely
                    if (pos + 1 < end) {
                        try {
                            BerDecoder.LengthResult lr =
                                    BerDecoder.readLength(data, pos + 1);
                            pos = lr.nextOffset + lr.length;
                        } catch (ApduException e) {
                            pos++;  // can't parse length — advance one byte
                        }
                    } else {
                        pos++;
                    }
                    break;
                }
            }
        }

        return new AareApdu(result, resultSourceDiag, serverMaxPdu, serverChallenge);
    }

    /**
     * Extracts {@code server-max-receive-pdu-size} from the xDLMS
     * InitiateResponse PDU bytes (the content of the user-information OCTET STRING).
     *
     * <h3>InitiateResponse fixed-tail structure</h3>
     * <p>Regardless of whether the optional {@code negotiated-quality-of-service}
     * field is present, the last four bytes of the InitiateResponse always have
     * the same layout:
     * <pre>
     * ...
     * SS SS  ← server-max-receive-pdu-size (big-endian uint16)
     * 00 07  ← vaa-name = 7 (LN referencing, always present for LN associations)
     * </pre>
     *
     * <p>Reading bytes {@code [len-4]} and {@code [len-3]} therefore gives us the
     * server-max-receive-pdu-size reliably without needing to fully parse the
     * optional quality-of-service field at the front.
     *
     * @param data InitiateResponse bytes (starting with xDLMS tag 0x08)
     * @return server-max-receive-pdu-size, or {@code 0xFFFF} if the PDU is too short
     */
    private static int decodeInitiateResponse(byte[] data) {
        // Minimum length: tag(1) + version(1) + conformance(7) + maxPdu(2) + vaaName(2) = 13
        if (data.length < 13) {
            return 0xFFFF;  // malformed or absent — use permissive default
        }

        // The vaa-name occupies the last 2 bytes (always 0x00 0x07 for LN).
        // server-max-receive-pdu-size occupies the 2 bytes immediately before that.
        int offset = data.length - 4;
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    /**
     * Throws {@link ApduException} with a human-readable message if the
     * association was rejected.
     */
    public void assertAccepted() throws ApduException {
        if (!accepted) {
            throw new ApduException(String.format(
                    "Association rejected: result=%d (%s), diagnostic=%d (%s)",
                    result, resultDescription(result),
                    resultSourceDiag, diagnosticDescription(resultSourceDiag)));
        }
    }

    private static String resultDescription(int code) {
        switch (code) {
            case 0: return "accepted";
            case 1: return "rejected-permanent";
            case 2: return "rejected-transient";
            default: return "unknown(" + code + ")";
        }
    }

    private static String diagnosticDescription(int code) {
        switch (code) {
            case 0:  return "null";
            case 1:  return "no-reason-given";
            case 2:  return "application-context-name-not-supported";
            case 11: return "authentication-mechanism-name-not-recognised";
            case 12: return "authentication-mechanism-name-required";
            case 13: return "authentication-failure";
            case 14: return "authentication-required";
            default: return "unknown(" + code + ")";
        }
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /** {@code true} if the meter accepted the association (result == 0). */
    public boolean isAccepted()    { return accepted; }

    /** Raw AARE result code (0 = accepted, 1 = rejected-permanent, 2 = rejected-transient). */
    public int     getResult()     { return result; }

    /** Diagnostic code returned on rejection. */
    public int     getDiagnostic() { return resultSourceDiag; }

    /**
     * The {@code server-max-receive-pdu-size} from the xDLMS InitiateResponse.
     *
     * <p>This is the maximum APDU payload the server can accept per request.
     * The client must not send GET/SET/ACTION APDUs larger than this value;
     * doing so requires using the multi-block GET mechanism (GET-Request-Next).
     *
     * <p>Returns {@code 65535} if the AARE did not contain a user-information
     * field (some meters omit it for public associations).
     */
    public int     getServerMaxReceivePduSize() { return serverMaxReceivePduSize; }

    /**
     * The server challenge for HIGH-level authentication, or {@code null}
     * if not present.
     */
    public byte[]  getServerChallenge() {
        return serverChallenge == null ? null
                : Arrays.copyOf(serverChallenge, serverChallenge.length);
    }
}