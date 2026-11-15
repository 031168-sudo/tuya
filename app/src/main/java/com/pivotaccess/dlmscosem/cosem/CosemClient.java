package com.pivotaccess.dlmscosem.cosem;

import com.pivotaccess.dlmscosem.util.MeterAddress;
import com.pivotaccess.dlmscosem.apdu.*;
import com.pivotaccess.dlmscosem.cosem.objects.*;
import com.pivotaccess.dlmscosem.session.CosemSession;
import com.pivotaccess.dlmscosem.session.CosemSessionException;
import com.pivotaccess.dlmscosem.session.HDLCSessionAdapter;
import com.pivotaccess.dlmscosem.transport.TcpTransport;
import com.pivotaccess.dlmscosem.transport.Transport;
import com.pivotaccess.dlmscosem.transport.TransportConfig;
import com.pivotaccess.dlmscosem.wrapper.WrapperSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * DLMS/COSEM client — primary entry point for communicating with a meter.
 *
 * <p>Supports three connection modes, selected automatically from
 * {@link CosemClientConfig}:
 * <ul>
 *   <li><b>RS-485 + HDLC</b> — physical serial bus</li>
 *   <li><b>TCP + HDLC</b>    — HDLC over TCP</li>
 *   <li><b>TCP + WRAPPER</b> — IEC 62056-47</li>
 * </ul>
 *
 * <h3>RS-485</h3>
 * <pre>{@code
 * CosemClientConfig config = CosemClientConfig.builder()
 *         .transport(TransportConfig.rs485("/dev/ttyUSB0", 9600))
 *         .serverAddress(MeterAddress.hdlcServer(1, 3))
 *         .clientAddress(MeterAddress.hdlcClient(16))
 *         .build();
 * }</pre>
 *
 * <h3>TCP + HDLC</h3>
 * <pre>{@code
 * CosemClientConfig config = CosemClientConfig.builder()
 *         .transport(TransportConfig.tcp("localhost", 4059))
 *         .serverAddress(MeterAddress.hdlcServer(1, 1))
 *         .clientAddress(MeterAddress.hdlcClient(16))
 *         .build();
 * }</pre>
 *
 * <h3>TCP + WRAPPER</h3>
 * <pre>{@code
 * CosemClientConfig config = CosemClientConfig.builder()
 *         .transport(TransportConfig.tcp("localhost", 4059, FramingMode.WRAPPER))
 *         .serverAddress(MeterAddress.wrapperServer(1))
 *         .clientAddress(MeterAddress.wrapperClient(16))
 *         .build();
 * }</pre>
 *
 * <h3>Reading data</h3>
 * <pre>{@code
 * try (CosemClient client = new CosemClient(config)) {
 *     client.open();
 *     RegisterObject energy = client.getRegister(ObisCode.ACTIVE_ENERGY_IMPORT);
 *     System.out.printf("%.3f %s%n", energy.getPhysicalValue(), energy.getUnitSymbol());
 * }
 * }</pre>
 */
public final class CosemClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CosemClient.class);
    private static final int    MAX_BLOCKS = 256;

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final CosemClientConfig config;
    private Transport               transport;
    private CosemSession            session;
    private boolean                 associationOpen = false;
    private int                     invokeId;

    /**
     * The {@code server-max-receive-pdu-size} negotiated during association.
     * Populated after {@link #open()} succeeds. This limits the size of APDU
     * payloads we send to the server in GET/SET/ACTION requests.
     */
    private int serverMaxReceivePduSize = AarqApdu.DEFAULT_MAX_RECEIVE_PDU_SIZE;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public CosemClient(CosemClientConfig config) {
        this.config   = config;
        this.invokeId = config.getInvokeIdSeed();
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Opens the transport, establishes the session (HDLC or WRAPPER), and
     * opens a COSEM association (AARQ / AARE).
     *
     * @throws CosemClientException if any step fails
     */
    public void open() throws CosemClientException {
        TransportConfig tc = config.getTransportConfig();

        transport = createTransport(tc);
        session   = createSession(tc, transport);

        try {
            session.connect();
        } catch (CosemSessionException e) {
            throw new CosemClientException("Session connection failed", e);
        }

        log.debug("Session established [{}], opening COSEM association", tc.getFramingMode());

        // Send AARQ — include our configured client-max-receive-pdu-size
        byte[] aarqBytes = buildAarq(config.getMaxReceivePduSize()).encode();
        log.debug("AARQ length: {}, bytes: {}", aarqBytes.length, hex(aarqBytes));
        byte[] aareBytes;
        try {
            aareBytes = session.sendReceive(aarqBytes);
            log.debug("AARE length: {}, bytes: {}", aareBytes.length, hex(aareBytes));
        } catch (CosemSessionException e) {
            throw new CosemClientException("AARQ/AARE exchange failed", e);
        }

        // Decode and validate AARE; extract server's PDU size limit
        try {
            AareApdu aare = AareApdu.decode(aareBytes);
            aare.assertAccepted();
            serverMaxReceivePduSize = aare.getServerMaxReceivePduSize();
            log.debug("COSEM association opened — server max receive PDU: {} bytes",
                    serverMaxReceivePduSize);
        } catch (ApduException e) {
            throw new CosemClientException("Association rejected by meter", e);
        }

        associationOpen = true;
        log.debug("COSEM association opened — {}", tc);
    }

    /**
     * Releases the COSEM association and closes the session.
     * Safe to call even if {@link #open()} was never called.
     */
    @Override
    public void close() {
        if (session != null) {
            session.disconnect();
            session = null;
        }
        if (transport != null) {
            transport.close();
            transport = null;
        }
        associationOpen = false;
        log.debug("COSEM client closed");
    }

    // -------------------------------------------------------------------------
    // GET
    // -------------------------------------------------------------------------

    /** Reads a single COSEM attribute, returns the raw {@link DataObject}. */
    public DataObject get(ObisCode obisCode, CosemClassId classId, int attrId)
            throws CosemClientException {
        return get(obisCode, classId, attrId, null);
    }

    /** Reads a single COSEM attribute with optional selective access. */
    public DataObject get(ObisCode obisCode, CosemClassId classId,
                          int attrId, byte[] selectiveAccess)
            throws CosemClientException {
        assertOpen();
        log.debug("GET {}/{} attr={}", obisCode, classId, attrId);

        int    id       = nextInvokeId();
        byte[] apdu     = encodeGetRequest(id, obisCode, classId, attrId, selectiveAccess);
        byte[] response = exchangeWithBlocking(apdu, id);

        try {
            GetResponse resp = GetResponse.decode(response);
            resp.assertSuccess();
            return resp.getValue();
        } catch (ApduException e) {
            throw new CosemClientException("GET response decode failed for " + obisCode, e);
        }
    }

    /** Reads a {@link RegisterObject} (class 3) — value and scaler-unit. */
    public RegisterObject getRegister(ObisCode obisCode) throws CosemClientException {
        RegisterObject reg = new RegisterObject(obisCode);
        reg.setRawValue(get(obisCode, CosemClassId.REGISTER, RegisterObject.ATTR_VALUE));
        try {
            reg.setScalerUnit(get(obisCode, CosemClassId.REGISTER,
                    RegisterObject.ATTR_SCALER_UNIT));
        } catch (CosemClientException e) {
            log.warn("Could not read scaler-unit for {}: {}", obisCode, e.getMessage());
        } catch (ApduException e) {
            log.warn("Scaler-unit decode error for {}: {}", obisCode, e.getMessage());
        }
        return reg;
    }

    /** Reads the meter clock ({@code 0.0.1.0.0.255}). */
    public ClockObject getClock() throws CosemClientException {
        return getClock(ObisCode.CLOCK);
    }

    /** Reads a clock object at a custom OBIS code. */
    public ClockObject getClock(ObisCode obisCode) throws CosemClientException {
        ClockObject clock = new ClockObject(obisCode);
        try {
            clock.setTimeRaw(get(obisCode, CosemClassId.CLOCK, ClockObject.ATTR_TIME));
        } catch (ApduException e) {
            throw new CosemClientException("Clock decode failed", e);
        }
        return clock;
    }

    /** Reads a {@link CosemDataObject} (class 1). */
    public CosemDataObject getData(ObisCode obisCode) throws CosemClientException {
        return new CosemDataObject(obisCode,
                get(obisCode, CosemClassId.DATA, CosemDataObject.ATTR_VALUE));
    }

    /** Reads the full {@link ProfileGenericObject} buffer (class 7). */
    public ProfileGenericObject getProfile(ObisCode obisCode)
            throws CosemClientException {
        return getProfile(obisCode, null);
    }

    /**
     * Reads a {@link ProfileGenericObject} with optional selective access
     * (time-range slice).
     */
    public ProfileGenericObject getProfile(ObisCode obisCode, byte[] selectiveAccess)
            throws CosemClientException {
        ProfileGenericObject profile = new ProfileGenericObject(obisCode);
        try {
            profile.setCaptureObjects(get(obisCode, CosemClassId.PROFILE_GENERIC,
                    ProfileGenericObject.ATTR_CAPTURE_OBJECTS));
        } catch (CosemClientException | ApduException e) {
            log.warn("Could not read capture objects for {}: {}", obisCode, e.getMessage());
        }
        try {
            profile.setBuffer(get(obisCode, CosemClassId.PROFILE_GENERIC,
                    ProfileGenericObject.ATTR_BUFFER, selectiveAccess));
        } catch (ApduException e) {
            throw new CosemClientException("Profile buffer decode failed for " + obisCode, e);
        }
        return profile;
    }

    /**
     * Reads multiple attributes, returning raw {@link DataObject} values in order.
     */
    public List<DataObject> getList(List<GetAttributeDescriptor> requests)
            throws CosemClientException {
        assertOpen();
        List<DataObject> results = new ArrayList<>(requests.size());
        for (GetAttributeDescriptor d : requests) {
            results.add(get(d.obisCode, d.classId, d.attrId));
        }
        return results;
    }

    // -------------------------------------------------------------------------
    // SET
    // -------------------------------------------------------------------------

    /** Writes a value to a COSEM attribute. */
    public void set(ObisCode obisCode, CosemClassId classId,
                    int attrId, DataObject value)
            throws CosemClientException {
        assertOpen();
        log.debug("SET {}/{} attr={}", obisCode, classId, attrId);

        int id = nextInvokeId();
        byte[] apdu;
        try {
            apdu = SetRequest.normal(id, classId.getId(),
                    obisCode.toBytes(), attrId, value).encode();
        } catch (ApduException e) {
            throw new CosemClientException("SET encoding failed", e);
        }

        byte[] response;
        try {
            response = session.sendReceive(apdu);
        } catch (CosemSessionException e) {
            throw new CosemClientException("SET exchange failed for " + obisCode, e);
        }

        try {
            SetResponse.decode(response).assertSuccess();
        } catch (ApduException e) {
            throw new CosemClientException("SET failed for " + obisCode, e);
        }
    }

    // -------------------------------------------------------------------------
    // ACTION
    // -------------------------------------------------------------------------

    /** Invokes a COSEM method with no parameter. */
    public DataObject action(ObisCode obisCode, CosemClassId classId,
                             int methodId) throws CosemClientException {
        return action(obisCode, classId, methodId, null);
    }

    /** Invokes a COSEM method with a parameter. */
    public DataObject action(ObisCode obisCode, CosemClassId classId,
                             int methodId, DataObject parameter)
            throws CosemClientException {
        assertOpen();
        log.debug("ACTION {}/{} method={}", obisCode, classId, methodId);

        int id = nextInvokeId();
        byte[] apdu;
        try {
            apdu = ActionRequest.normal(id, classId.getId(),
                    obisCode.toBytes(), methodId, parameter).encode();
        } catch (ApduException e) {
            throw new CosemClientException("ACTION encoding failed", e);
        }

        byte[] response;
        try {
            response = session.sendReceive(apdu);
        } catch (CosemSessionException e) {
            throw new CosemClientException("ACTION exchange failed for " + obisCode, e);
        }

        try {
            ActionResponse resp = ActionResponse.decode(response);
            resp.assertSuccess();
            return resp.getReturnValue();
        } catch (ApduException e) {
            throw new CosemClientException(
                    "ACTION failed for " + obisCode + " method=" + methodId, e);
        }
    }

    // -------------------------------------------------------------------------
    // Convenience shortcuts
    // -------------------------------------------------------------------------

    /** Reads the meter serial number ({@code 0.0.96.1.0.255}). */
    public String readSerialNumber() throws CosemClientException {
        try { return getData(ObisCode.SERIAL_NUMBER).getStringValue(); }
        catch (ApduException e) { throw new CosemClientException("Failed to read serial number", e); }
    }

    /** Reads the meter firmware version ({@code 1.0.0.2.0.255}). */
    public String readFirmwareVersion() throws CosemClientException {
        try { return getData(ObisCode.FIRMWARE_VERSION).getStringValue(); }
        catch (ApduException e) { throw new CosemClientException("Failed to read firmware version", e); }
    }

    /** Resets a register by invoking method 1. */
    public void resetRegister(ObisCode obisCode) throws CosemClientException {
        action(obisCode, CosemClassId.REGISTER, RegisterObject.METHOD_RESET);
    }

    /**
     * Sets the meter clock.
     *
     * @param dateTimeBytes 12-byte COSEM date-time (use {@link ClockObject#encodeDateTime})
     */
    public void setClock(byte[] dateTimeBytes) throws CosemClientException {
        set(ObisCode.CLOCK, CosemClassId.CLOCK,
                ClockObject.ATTR_TIME, DataObject.ofDateTime(dateTimeBytes));
    }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    /** Returns {@code true} if the COSEM association is open. */
    public boolean isOpen() { return associationOpen; }

    // -------------------------------------------------------------------------
    // Session factory — picks HDLC or WRAPPER from TransportConfig
    // -------------------------------------------------------------------------

    private CosemSession createSession(TransportConfig tc, Transport transport) {
        MeterAddress server = config.getServerAddress();
        MeterAddress client = config.getClientAddress();

        if (tc.isWrapper()) {
            // WRAPPER: addresses are wPorts
            return new WrapperSession(
                    transport,
                    client.asWrapperPort(),
                    server.asWrapperPort(),
                    tc.getReadTimeoutMs());
        }

        // HDLC (RS-485 or TCP+HDLC): addresses are HDLCAddress values
        return new HDLCSessionAdapter(
                transport,
                client.asHdlcAddress(),
                server.asHdlcAddress(),
                config.getHdlcParams(),
                config.getTimeoutMs(),
                config.getMaxRetries(),
                config.isStrictSequenceControlEnabled()
                );
    }

    private Transport createTransport(TransportConfig tc) {
        // В мобильном приложении используется только TCP-мост к Bluetooth
        return new TcpTransport(tc);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private byte[] exchangeWithBlocking(byte[] apdu, int invokeId)
            throws CosemClientException {
        byte[] response;
        try {
            response = session.sendReceive(apdu);
        } catch (CosemSessionException e) {
            throw new CosemClientException("APDU exchange failed", e);
        }

        // Detect GET-Response-With-Datablock (subtype 0x02)
        if (response.length > 1 && (response[1] & 0xFF) == 0x02) {
            return reassembleDatablocks(response, invokeId);
        }
        return response;
    }

    private byte[] reassembleDatablocks(byte[] firstBlock, int invokeId)
            throws CosemClientException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        byte[] current    = firstBlock;
        int    blockCount = 0;

        while (true) {
            GetResponse block;
            try {
                block = GetResponse.decode(current);
            } catch (ApduException e) {
                throw new CosemClientException("Failed to decode datablock", e);
            }

            byte[] data = block.getRawBlockData();
            if (data != null) raw.write(data, 0, data.length);
            if (block.isLastBlock()) break;

            if (++blockCount > MAX_BLOCKS) {
                throw new CosemClientException("Exceeded max block count: " + MAX_BLOCKS);
            }

            try {
                byte[] next = GetRequest.next(invokeId, block.getBlockNumber()).encode();
                current = session.sendReceive(next);
            } catch (CosemSessionException e) {
                throw new CosemClientException(
                        "GET-Request-Next failed at block " + blockCount, e);
            }
        }

        // Wrap as synthetic GET-Response-Normal
        byte[] payload = raw.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + 6);
        out.write(0xC4);
        out.write(0x01);
        out.write(0xC0 | (invokeId & 0x0F));
        out.write(0x00);
        out.write(payload, 0, payload.length);
        return out.toByteArray();
    }

    private byte[] encodeGetRequest(int id, ObisCode obisCode,
                                    CosemClassId classId, int attrId,
                                    byte[] selectiveAccess) {
        if (selectiveAccess == null) {
            return GetRequest.normal(id, classId.getId(),
                    obisCode.toBytes(), attrId).encode();
        }
        // GET-Request-Normal with access-selection present
        ByteArrayOutputStream body = new ByteArrayOutputStream(32);
        body.write((classId.getId() >> 8) & 0xFF);
        body.write(classId.getId()        & 0xFF);
        body.write(obisCode.toBytes(), 0, 6);
        body.write(attrId & 0xFF);
        body.write(0x01);   // access-selection present
        body.write(selectiveAccess, 0, selectiveAccess.length);
        byte[] bodyBytes = body.toByteArray();

        ByteArrayOutputStream out = new ByteArrayOutputStream(bodyBytes.length + 3);
        out.write(0xC0);
        out.write(0x01);
        out.write(0xC0 | (id & 0x0F));
        out.write(bodyBytes, 0, bodyBytes.length);
        return out.toByteArray();
    }

    private AarqApdu buildAarq(int maxReceivePduSize) {
        switch (config.getAuthLevel()) {
            case LOW:  return AarqApdu.lowAuth(config.getAuthValue(), maxReceivePduSize);
            case HIGH: return AarqApdu.highAuth(config.getAuthValue(), maxReceivePduSize);
            default:   return AarqApdu.noAuth(maxReceivePduSize);
        }
    }

    /**
     * Returns the {@code server-max-receive-pdu-size} negotiated in the last
     * {@link #open()} call. This is the maximum APDU payload the server can
     * receive in a single request. Returns 65535 before {@code open()} is
     * called.
     */
    public int getServerMaxReceivePduSize() { return serverMaxReceivePduSize; }

    private void assertOpen() throws CosemClientException {
        if (!associationOpen) {
            throw new CosemClientException("Client is not open. Call open() first.");
        }
    }

    private int nextInvokeId() {
        int id = invokeId;
        invokeId = (invokeId % 15) + 1;
        return id;
    }

    // -------------------------------------------------------------------------
    // GetAttributeDescriptor
    // -------------------------------------------------------------------------

    /** Describes one attribute to read in a {@link #getList} call. */
    public static final class GetAttributeDescriptor {

        public final ObisCode     obisCode;
        public final CosemClassId classId;
        public final int          attrId;

        public GetAttributeDescriptor(ObisCode obisCode,
                                      CosemClassId classId, int attrId) {
            this.obisCode = obisCode;
            this.classId  = classId;
            this.attrId   = attrId;
        }

        public static GetAttributeDescriptor of(ObisCode obis,
                                                CosemClassId cls, int attr) {
            return new GetAttributeDescriptor(obis, cls, attr);
        }
    }

    private static String hex(byte[] data) {
        if (data == null) return "null";
        StringBuilder sb = new StringBuilder(data.length * 3);
        for (byte b : data) sb.append(String.format("%02X ", b));
        return sb.toString().trim();
    }
}