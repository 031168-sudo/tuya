package com.pivotaccess.dlmscosem.cosem.objects;

import com.pivotaccess.dlmscosem.apdu.ApduException;
import com.pivotaccess.dlmscosem.apdu.DataObject;
import com.pivotaccess.dlmscosem.cosem.CosemClassId;
import com.pivotaccess.dlmscosem.cosem.CosemObject;
import com.pivotaccess.dlmscosem.cosem.ObisCode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * COSEM Profile Generic object — Interface Class ID 7 (IEC 62056-62).
 *
 * <p>Stores time-series data (load profiles, event logs, billing data).
 * This is the most complex COSEM class. The profile is a table where:
 * <ul>
 *   <li>Each <b>column</b> is defined by a capture object (OBIS + class + attr)</li>
 *   <li>Each <b>row</b> (entry) is a snapshot of all capture objects at one point in time</li>
 * </ul>
 *
 * <h3>Attributes</h3>
 * <ol>
 *   <li>{@code logical_name}      — OBIS code</li>
 *   <li>{@code buffer}            — ARRAY of captured entries (the profile data)</li>
 *   <li>{@code capture_objects}   — ARRAY of capture object definitions (columns)</li>
 *   <li>{@code capture_period}    — capture interval in seconds (0 = event-driven)</li>
 *   <li>{@code sort_method}       — how entries are sorted (1=FIFO … 6=largest)</li>
 *   <li>{@code sort_object}       — capture object used for sorting</li>
 *   <li>{@code entries_in_use}    — number of valid entries currently in buffer</li>
 *   <li>{@code profile_entries}   — maximum number of entries the buffer can hold</li>
 * </ol>
 *
 * <h3>Methods</h3>
 * <ol>
 *   <li>{@code reset}   — clears the profile buffer</li>
 *   <li>{@code capture} — triggers an immediate capture</li>
 * </ol>
 *
 * <h3>Selective access</h3>
 * <p>The buffer attribute (attr 2) supports selective access, allowing the
 * client to request a time-range slice instead of the full buffer. This is
 * handled by the {@code CosemClient} using a {@link SelectiveAccessDescriptor}.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // Read all entries in load profile 1
 * ProfileGenericObject profile = (ProfileGenericObject) client.get(
 *         ObisCode.LOAD_PROFILE_1, CosemClassId.PROFILE_GENERIC);
 *
 * // Each row is a list of DataObjects, one per capture column
 * for (List<DataObject> row : profile.getBuffer()) {
 *     DataObject timestamp = row.get(0);   // first column is usually clock
 *     DataObject energy    = row.get(1);
 *     System.out.println(timestamp + " → " + energy);
 * }
 * }</pre>
 */
public final class ProfileGenericObject extends CosemObject {

    // Attribute indices
    public static final int ATTR_BUFFER          = 2;
    public static final int ATTR_CAPTURE_OBJECTS = 3;
    public static final int ATTR_CAPTURE_PERIOD  = 4;
    public static final int ATTR_SORT_METHOD     = 5;
    public static final int ATTR_SORT_OBJECT     = 6;
    public static final int ATTR_ENTRIES_IN_USE  = 7;
    public static final int ATTR_PROFILE_ENTRIES = 8;

    // Method indices
    public static final int METHOD_RESET         = 1;
    public static final int METHOD_CAPTURE       = 2;

    // -------------------------------------------------------------------------

    private List<List<DataObject>> buffer;           // decoded rows from attr 2
    private List<CaptureObjectDef> captureObjects;   // column definitions from attr 3
    private Long                   capturePeriod;    // seconds
    private Long                   entriesInUse;
    private Long                   profileEntries;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public ProfileGenericObject(ObisCode obisCode) {
        super(CosemClassId.PROFILE_GENERIC, obisCode);
    }

    // -------------------------------------------------------------------------
    // Population (called by CosemClient)
    // -------------------------------------------------------------------------

    /**
     * Decodes and stores the profile buffer from attribute 2.
     *
     * <p>The buffer is an ARRAY of STRUCTUREs. Each STRUCTURE is one row,
     * containing one DataObject per capture column.
     *
     * @param bufferArray the ARRAY DataObject from attribute 2
     * @throws ApduException if the structure is malformed
     */
    public void setBuffer(DataObject bufferArray) throws ApduException {
        List<DataObject> rows = bufferArray.getList();
        List<List<DataObject>> decoded = new ArrayList<>(rows.size());
        for (DataObject row : rows) {
            decoded.add(Collections.unmodifiableList(row.getList()));
        }
        this.buffer = Collections.unmodifiableList(decoded);
    }

    /**
     * Decodes and stores the capture objects from attribute 3.
     *
     * <p>The capture objects define the columns of the profile. Each entry is
     * a STRUCTURE: { class_id(LONG_UNSIGNED), logical_name(OCTET_STRING),
     * attribute_index(INTEGER), data_index(LONG_UNSIGNED) }
     *
     * @param captureObjectsArray the ARRAY DataObject from attribute 3
     * @throws ApduException if the structure is malformed
     */
    public void setCaptureObjects(DataObject captureObjectsArray) throws ApduException {
        List<DataObject> items = captureObjectsArray.getList();
        List<CaptureObjectDef> defs = new ArrayList<>(items.size());
        for (DataObject item : items) {
            defs.add(CaptureObjectDef.decode(item));
        }
        this.captureObjects = Collections.unmodifiableList(defs);
    }

    public void setCapturePeriod(DataObject data) throws ApduException {
        this.capturePeriod = data.getLong();
    }

    public void setEntriesInUse(DataObject data) throws ApduException {
        this.entriesInUse = data.getLong();
    }

    public void setProfileEntries(DataObject data) throws ApduException {
        this.profileEntries = data.getLong();
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /**
     * Returns the decoded profile buffer as a list of rows. Each row is a list
     * of {@link DataObject} values, one per capture column.
     *
     * @throws IllegalStateException if the buffer has not been populated
     */
    public List<List<DataObject>> getBuffer() {
        assertBufferPopulated();
        return buffer;
    }

    /**
     * Returns the number of rows in the buffer.
     *
     * @throws IllegalStateException if the buffer has not been populated
     */
    public int getRowCount() {
        assertBufferPopulated();
        return buffer.size();
    }

    /**
     * Returns the column definitions (capture objects).
     * May be {@code null} if attribute 3 was not read.
     */
    public List<CaptureObjectDef> getCaptureObjects() {
        return captureObjects;
    }

    /**
     * Returns the capture period in seconds, or {@code null} if not read.
     * 0 means event-driven capture.
     */
    public Long getCapturePeriod() {
        return capturePeriod;
    }

    /**
     * Returns the number of valid entries currently in the buffer,
     * or {@code null} if not read.
     */
    public Long getEntriesInUse() {
        return entriesInUse;
    }

    /**
     * Returns the maximum buffer capacity (profile entries),
     * or {@code null} if not read.
     */
    public Long getProfileEntries() {
        return profileEntries;
    }

    /** Returns {@code true} if the buffer has been populated. */
    public boolean isPopulated() {
        return buffer != null;
    }

    // -------------------------------------------------------------------------
    // Selective access descriptor (for time-range requests)
    // -------------------------------------------------------------------------

    /**
     * Builds a selective access descriptor for reading a time-range slice of
     * the profile buffer. Pass to {@code CosemClient.getWithSelectiveAccess()}.
     *
     * <p>The restricting object for time-range access is always the clock
     * capture object (typically the first column, class 8, attr 2).
     *
     * @param fromDateTime 12-byte COSEM date-time for range start
     * @param toDateTime   12-byte COSEM date-time for range end
     * @return encoded selective access descriptor bytes
     */
    public static byte[] buildTimeRangeAccess(
            byte[] fromDateTime, byte[] toDateTime) throws ApduException, IOException {
        // Selective access descriptor:
        // 01                   ← access-selector = 1 (range descriptor)
        // 02 04                ← STRUCTURE, 4 elements
        //   02 04              ←   restricting object: STRUCTURE, 4 elements
        //     12 00 08         ←     class-id = 8 (Clock)
        //     09 06 00000100FF ←     logical-name = 0.0.1.0.0.255
        //     0F 02            ←     attribute-index = 2
        //     12 00 00         ←     data-index = 0
        //   19 <from>          ←   from: DATE_TIME (12 bytes)
        //   19 <to>            ←   to:   DATE_TIME (12 bytes)
        //   01 00              ←   selected-values: ARRAY, 0 items = all columns

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(40);
        out.write(0x01);   // access-selector = range-descriptor

        // STRUCTURE { restricting_object, from, to, selected_values }
        out.write(0x02);   // STRUCTURE tag
        out.write(0x04);   // 4 elements

        // restricting_object: STRUCTURE { class_id, logical_name, attr_idx, data_idx }
        out.write(0x02);   // STRUCTURE
        out.write(0x04);   // 4 elements
        out.write(0x12); out.write(0x00); out.write(0x08); // class_id = LONG_UNSIGNED 8
        out.write(0x09); out.write(0x06);                  // OCTET_STRING, length 6
        out.write(new byte[]{0,0,1,0,0,(byte)255});        // OBIS 0.0.1.0.0.255 (clock)
        out.write(0x0F); out.write(0x02);                  // attr_index = INTEGER 2
        out.write(0x12); out.write(0x00); out.write(0x00); // data_index = LONG_UNSIGNED 0

        // from date-time
        out.write(0x19);   // DATE_TIME tag
        out.write(fromDateTime, 0, 12);

        // to date-time
        out.write(0x19);   // DATE_TIME tag
        out.write(toDateTime, 0, 12);

        // selected_values: empty ARRAY = all columns
        out.write(0x01);   // ARRAY tag
        out.write(0x00);   // 0 elements

        return out.toByteArray();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void assertBufferPopulated() {
        if (buffer == null) {
            throw new IllegalStateException(
                    "ProfileGenericObject " + obisCode + " buffer not populated.");
        }
    }

    @Override
    public String toString() {
        if (buffer == null) return super.toString() + " [not populated]";
        return super.toString()
                + " rows=" + buffer.size()
                + " columns=" + (captureObjects != null ? captureObjects.size() : "?");
    }

    // -------------------------------------------------------------------------
    // CaptureObjectDef — one column definition
    // -------------------------------------------------------------------------

    /**
     * Defines one column in the profile generic buffer.
     * Each capture object is a STRUCTURE:
     * { class_id(LONG_UNSIGNED), logical_name(OCTET_STRING[6]),
     *   attribute_index(INTEGER), data_index(LONG_UNSIGNED) }
     */
    public static final class CaptureObjectDef {

        private final int      classId;
        private final ObisCode logicalName;
        private final int      attributeIndex;
        private final int      dataIndex;

        private CaptureObjectDef(int classId, ObisCode logicalName,
                                 int attributeIndex, int dataIndex) {
            this.classId        = classId;
            this.logicalName    = logicalName;
            this.attributeIndex = attributeIndex;
            this.dataIndex      = dataIndex;
        }

        /**
         * Decodes a CaptureObjectDef from a STRUCTURE DataObject.
         *
         * @throws ApduException if the structure is malformed
         */
        public static CaptureObjectDef decode(DataObject structure)
                throws ApduException {
            List<DataObject> items = structure.getList();
            if (items.size() < 4) {
                throw new ApduException(
                        "CaptureObjectDef structure must have 4 elements, got: "
                                + items.size());
            }
            int      classId        = (int) items.get(0).getLong();
            byte[]   logicalNameRaw = items.get(1).getOctetString();
            int      attrIndex      = (int) items.get(2).getLong();
            int      dataIndex      = (int) items.get(3).getLong();
            ObisCode logicalName    = ObisCode.of(logicalNameRaw);
            return new CaptureObjectDef(classId, logicalName, attrIndex, dataIndex);
        }

        public int      getClassId()        { return classId; }
        public ObisCode getLogicalName()    { return logicalName; }
        public int      getAttributeIndex() { return attributeIndex; }
        public int      getDataIndex()      { return dataIndex; }

        @Override
        public String toString() {
            return "CaptureObjectDef{class=" + classId
                    + ", obis=" + logicalName
                    + ", attr=" + attributeIndex + "}";
        }
    }

    // -------------------------------------------------------------------------
    // SelectiveAccessDescriptor (fluent builder)
    // -------------------------------------------------------------------------

    /**
     * Fluent builder for selective access descriptors used when reading
     * a time-range slice of the profile buffer.
     *
     * <h3>Usage</h3>
     * <pre>{@code
     * byte[] selector = ProfileGenericObject.rangeDescriptor()
     *         .from(ClockObject.encodeDateTime(OffsetDateTime.now().minusDays(1)))
     *         .to(ClockObject.encodeDateTime(OffsetDateTime.now()))
     *         .build();
     * }</pre>
     */
    public static final class SelectiveAccessDescriptor {

        private byte[] from;
        private byte[] to;

        private SelectiveAccessDescriptor() {}

        public static SelectiveAccessDescriptor rangeDescriptor() {
            return new SelectiveAccessDescriptor();
        }

        public SelectiveAccessDescriptor from(byte[] fromDateTime) {
            this.from = fromDateTime;
            return this;
        }

        public SelectiveAccessDescriptor to(byte[] toDateTime) {
            this.to = toDateTime;
            return this;
        }

        public byte[] build() throws ApduException, IOException {
            if (from == null || to == null) {
                throw new ApduException(
                        "SelectiveAccessDescriptor requires both 'from' and 'to'");
            }
            return buildTimeRangeAccess(from, to);
        }
    }
}