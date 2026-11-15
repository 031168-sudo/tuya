package com.pivotaccess.dlmscosem.apdu;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * DLMS/COSEM Association Request APDU (AARQ) as defined in IEC 62056-53.
 *
 * <p>The AARQ is the first APDU sent after the link layer is established.
 * It negotiates xDLMS parameters and optionally carries authentication.
 *
 * <h3>PDU size negotiation</h3>
 * <p>The {@code client-max-receive-pdu-size} field inside the embedded
 * xDLMS InitiateRequest tells the server the largest single APDU the client
 * can receive. The server echoes its own limit in the AARE as
 * {@code server-max-receive-pdu-size}; the client must honour that value
 * when constructing GET/SET/ACTION requests — responses larger than the
 * server's limit will be broken into multiple blocks.
 *
 * <h3>Wire structure (simplified)</h3>
 * <pre>
 * 60 &lt;len&gt;                            ← AARQ (APPLICATION 0)
 *   A1 09 06 07 &lt;app-context-oid&gt;    ← [1] application-context-name
 *   8A 02 07 80                        ← [10] sender-acse-requirements (auth only)
 *   8B 07 &lt;mech-oid&gt;                  ← [11] mechanism-name (auth only)
 *   AC &lt;len&gt; 80 &lt;len&gt; &lt;password&gt;   ← [12] calling-authentication-value (auth only)
 *   BE &lt;len&gt; 04 &lt;len&gt;               ← [30] user-information / OCTET STRING
 *     01 00                            ←   xDLMS InitiateRequest tag + dedicated-key absent
 *     06                               ←   proposed-dlms-version-number = 6
 *     5F 1F 04 00 &lt;conformance&gt;      ←   proposed-conformance (4 bytes)
 *     HH LL                            ←   client-max-receive-pdu-size (uint16)
 * </pre>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // No authentication, library default PDU size (65535)
 * AarqApdu aarq = AarqApdu.noAuth();
 *
 * // No authentication, custom PDU size
 * AarqApdu aarq = AarqApdu.noAuth(65535);
 *
 * // Password authentication
 * AarqApdu aarq = AarqApdu.lowAuth("00000001".getBytes(), 65535);
 *
 * byte[] encoded = aarq.encode();
 * }</pre>
 */
public final class AarqApdu {

    // -------------------------------------------------------------------------
    // BER / ACSE tags
    // -------------------------------------------------------------------------

    private static final int TAG_AARQ                       = 0x60;
    private static final int TAG_APP_CONTEXT_NAME           = 0xA1;
    private static final int TAG_OID                        = 0x06;
    private static final int TAG_ACSE_REQUIREMENTS          = 0x8A;
    private static final int TAG_MECHANISM_NAME             = 0x8B;
    private static final int TAG_CALLING_AUTH_VALUE         = 0xAC;
    private static final int TAG_CALLING_AUTH_VALUE_CHARSTR = 0x80;
    private static final int TAG_USER_INFO                  = 0xBE;
    private static final int TAG_OCTET_STRING               = 0x04;

    // -------------------------------------------------------------------------
    // Application context OIDs (IEC 62056-53)
    // -------------------------------------------------------------------------

    /** LN referencing, no ciphering — the most common context. */
    private static final byte[] CONTEXT_LN_NO_CIPHER =
            {0x60, (byte) 0x85, 0x74, 0x05, 0x08, 0x01, 0x01};

    /** LN referencing, with ciphering (HLS/GMAC). */
    private static final byte[] CONTEXT_LN_CIPHER =
            {0x60, (byte) 0x85, 0x74, 0x05, 0x08, 0x01, 0x03};

    // -------------------------------------------------------------------------
    // Authentication mechanism OIDs
    // -------------------------------------------------------------------------

    /** Low-level authentication (password). */
    private static final byte[] MECH_LOW =
            {(byte) 0x60, (byte) 0x85, 0x74, 0x05, 0x08, 0x02, 0x01};

    /** High-level authentication (GMAC). */
    private static final byte[] MECH_HIGH =
            {(byte) 0x60, (byte) 0x85, 0x74, 0x05, 0x08, 0x02, 0x05};

    // -------------------------------------------------------------------------
    // xDLMS InitiateRequest constants
    // -------------------------------------------------------------------------

    private static final int DLMS_VERSION = 6;

    /**
     * Proposed conformance bit field.
     * {@code 0x007A1F} = GET + SET + ACTION + multiple-references.
     * Written as 3 bytes here; the conformance field prepends one unused-bits
     * byte (0x00) making it 4 bytes total ({@code 5F 1F 04 00 XX XX XX}).
     * TODO: Refactor to enable "Action with Block Transfer" bit i.e { (byte)0x00, (byte)0x7E, (byte)0x1F }
     */
    private static final byte[] CONFORMANCE_BYTES = new byte[] { (byte)0x00, (byte)0x7A, (byte)0x1F }; // { (byte)0x00, (byte)0x7A, (byte)0x1F }

    /**
     * Default {@code client-max-receive-pdu-size} when none is specified.
     * 65535 (0xFFFF) is the maximum allowed by the DLMS standard and is
     * the most permissive value — the server's own limit (from the AARE)
     * takes effect in practice.
     */
    public static final int DEFAULT_MAX_RECEIVE_PDU_SIZE = 0xFFFF;

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final byte[]              applicationContextOid;
    private final AuthenticationLevel authLevel;
    private final byte[]              authValue;
    private final boolean             ciphered;

    /**
     * The {@code client-max-receive-pdu-size} encoded into the xDLMS
     * InitiateRequest — the largest APDU payload this client can receive.
     */
    private final int clientMaxReceivePduSize;

    // -------------------------------------------------------------------------
    // Authentication level
    // -------------------------------------------------------------------------

    public enum AuthenticationLevel { NONE, LOW, HIGH }

    // -------------------------------------------------------------------------
    // Factories — no authentication
    // -------------------------------------------------------------------------

    /** No authentication, default max PDU size (65535). */
    public static AarqApdu noAuth() {
        return noAuth(DEFAULT_MAX_RECEIVE_PDU_SIZE);
    }

    /**
     * No authentication with an explicit max PDU size.
     *
     * @param clientMaxReceivePduSize largest APDU payload this client can receive (1–65535)
     */
    public static AarqApdu noAuth(int clientMaxReceivePduSize) {
        return new AarqApdu(CONTEXT_LN_NO_CIPHER, AuthenticationLevel.NONE,
                null, false, clientMaxReceivePduSize);
    }

    // -------------------------------------------------------------------------
    // Factories — low-level authentication (password)
    // -------------------------------------------------------------------------

    /** Low-level authentication, default max PDU size. */
    public static AarqApdu lowAuth(byte[] password) {
        return lowAuth(password, DEFAULT_MAX_RECEIVE_PDU_SIZE);
    }

    /**
     * Low-level authentication with an explicit max PDU size.
     *
     * @param password              meter password bytes
     * @param clientMaxReceivePduSize largest APDU payload this client can receive
     */
    public static AarqApdu lowAuth(byte[] password, int clientMaxReceivePduSize) {
        return new AarqApdu(CONTEXT_LN_NO_CIPHER, AuthenticationLevel.LOW,
                Arrays.copyOf(password, password.length), false,
                clientMaxReceivePduSize);
    }

    // -------------------------------------------------------------------------
    // Factories — high-level authentication (challenge-response / GMAC)
    // -------------------------------------------------------------------------

    /** High-level authentication, default max PDU size. */
    public static AarqApdu highAuth(byte[] clientChallenge) {
        return highAuth(clientChallenge, DEFAULT_MAX_RECEIVE_PDU_SIZE);
    }

    /**
     * High-level authentication with an explicit max PDU size.
     *
     * @param clientChallenge       client challenge bytes (8–64 bytes)
     * @param clientMaxReceivePduSize largest APDU payload this client can receive
     */
    public static AarqApdu highAuth(byte[] clientChallenge, int clientMaxReceivePduSize) {
        return new AarqApdu(CONTEXT_LN_CIPHER, AuthenticationLevel.HIGH,
                Arrays.copyOf(clientChallenge, clientChallenge.length), true,
                clientMaxReceivePduSize);
    }

    // -------------------------------------------------------------------------
    // Private constructor
    // -------------------------------------------------------------------------

    private AarqApdu(byte[] contextOid, AuthenticationLevel authLevel,
                     byte[] authValue, boolean ciphered,
                     int clientMaxReceivePduSize) {
        this.applicationContextOid   = contextOid;
        this.authLevel               = authLevel;
        this.authValue               = authValue;
        this.ciphered                = ciphered;
        this.clientMaxReceivePduSize = Math.max(1, Math.min(clientMaxReceivePduSize, 0xFFFF));
    }

    // -------------------------------------------------------------------------
    // Encoding
    // -------------------------------------------------------------------------

    /**
     * Encodes this AARQ into wire-format bytes (starts with {@code 0x60}).
     */
    public byte[] encode() {
        ByteArrayOutputStream inner = new ByteArrayOutputStream(80);

        // [1] Application context name
        ByteArrayOutputStream ctxContent = new ByteArrayOutputStream();
        BerEncoder.writeTlv(ctxContent, TAG_OID, applicationContextOid);
        BerEncoder.writeConstructed(inner, TAG_APP_CONTEXT_NAME, ctxContent.toByteArray());

        // [10] + [11] + [12] — authentication (omitted when no-auth)
        if (authLevel != AuthenticationLevel.NONE && authValue != null) {
            // [10] sender-acse-requirements: authentication bit set (0x07 0x80)
            inner.write(TAG_ACSE_REQUIREMENTS);
            inner.write(0x02);
            inner.write(0x07);
            inner.write(0x80);

            // [11] mechanism-name OID
            byte[] mechOid = (authLevel == AuthenticationLevel.LOW) ? MECH_LOW : MECH_HIGH;
            BerEncoder.writeTlv(inner, TAG_MECHANISM_NAME, mechOid);

            // [12] calling-authentication-value (CHARSTRING wrapped in CONTEXT[0])
            ByteArrayOutputStream authContent = new ByteArrayOutputStream();
            BerEncoder.writeTlv(authContent, TAG_CALLING_AUTH_VALUE_CHARSTR, authValue);
            BerEncoder.writeConstructed(inner, TAG_CALLING_AUTH_VALUE, authContent.toByteArray());
        }

        // [30] user-information → OCTET STRING wrapping the xDLMS InitiateRequest
        byte[] initiateRequest = buildInitiateRequest();
        ByteArrayOutputStream userInfo = new ByteArrayOutputStream();
        BerEncoder.writeTlv(userInfo, TAG_OCTET_STRING, initiateRequest);
        BerEncoder.writeConstructed(inner, TAG_USER_INFO, userInfo.toByteArray());

        // Outer AARQ envelope: APPLICATION 0, constructed = tag 0x60
        ByteArrayOutputStream out = new ByteArrayOutputStream(inner.size() + 4);
        BerEncoder.writeConstructed(out, TAG_AARQ, inner.toByteArray());
        return out.toByteArray();
    }

    /**
     * Builds the xDLMS InitiateRequest binary structure embedded in the
     * user-information OCTET STRING.
     *
     * <h3>Wire layout</h3>
     * <pre>
     * 01 00           ← InitiateRequest tag + dedicated-key: absent
     * 06              ← proposed-dlms-version-number = 6
     * 5F 1F 04        ← conformance tag (CONTEXT[31]) + length
     * 00 XX XX XX     ← unused-bits byte + 3 conformance bytes
     * HH LL           ← client-max-receive-pdu-size (big-endian uint16)
     * </pre>
     *
     * <p>Note: {@code proposed-quality-of-service} and {@code response-allowed}
     * are both absent (their DEFAULT values apply).
     */
    private byte[] buildInitiateRequest() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16);

        out.write(0x01);  // xDLMS PDU choice: Initiate.request

        // Legacy A-XDR Compatibility:
        // Instead of packing the presence flags into a single bit-string byte,
        // we explicitly write one byte per optional field.
        out.write(0x00);  // dedicated-key: absent
        out.write(0x00);  // response-allowed: default applies
        out.write(0x00);  // proposed-quality-of-service: absent

        out.write(DLMS_VERSION & 0xFF);  // proposed-dlms-version-number = 6

        // proposed-conformance: tag 5F 1F + length 04 + unused-bits 00 + 3 bytes
        out.write(0x5F);
        out.write(0x1F);
        out.write(0x04);
        out.write(0x00);  // unused bits
        out.write(CONFORMANCE_BYTES, 0, CONFORMANCE_BYTES.length);

        // client-max-receive-pdu-size (big-endian uint16)
        out.write((clientMaxReceivePduSize >> 8) & 0xFF);
        out.write(clientMaxReceivePduSize        & 0xFF);

        return out.toByteArray();
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public AuthenticationLevel getAuthLevel()              { return authLevel; }
    public boolean             isCiphered()                { return ciphered; }

    /**
     * The {@code client-max-receive-pdu-size} embedded in this AARQ.
     * This is what we tell the server the client can receive per APDU.
     */
    public int                 getClientMaxReceivePduSize(){ return clientMaxReceivePduSize; }
}