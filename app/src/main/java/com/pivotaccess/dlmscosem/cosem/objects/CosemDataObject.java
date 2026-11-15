package com.pivotaccess.dlmscosem.cosem.objects;

import com.pivotaccess.dlmscosem.apdu.ApduException;
import com.pivotaccess.dlmscosem.apdu.DataObject;
import com.pivotaccess.dlmscosem.cosem.CosemClassId;
import com.pivotaccess.dlmscosem.cosem.CosemObject;
import com.pivotaccess.dlmscosem.cosem.ObisCode;

/**
 * COSEM Data object — Interface Class ID 1 (IEC 62056-62).
 *
 * <p>The Data class stores a single value of any DLMS data type. It is used
 * for meter parameters, configuration values, and identifiers such as:
 * <ul>
 *   <li>Meter serial number ({@code 0.0.96.1.0.255})</li>
 *   <li>Firmware version ({@code 1.0.0.2.0.255})</li>
 *   <li>Meter type ({@code 0.0.96.1.1.255})</li>
 *   <li>Programmable parameters</li>
 * </ul>
 *
 * <h3>Attributes</h3>
 * <ol>
 *   <li>{@code logical_name} — OBIS code as OCTET STRING (read-only)</li>
 *   <li>{@code value}        — the stored value, any DLMS data type</li>
 * </ol>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * CosemDataObject serial = (CosemDataObject) client.get(
 *         ObisCode.SERIAL_NUMBER, CosemClassId.DATA);
 * System.out.println(serial.getStringValue()); // e.g. "12345678"
 * }</pre>
 */
public final class CosemDataObject extends CosemObject {

    /** Attribute index for {@code value}. */
    public static final int ATTR_VALUE = 2;

    // -------------------------------------------------------------------------

    private DataObject value;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    public CosemDataObject(ObisCode obisCode) {
        super(CosemClassId.DATA, obisCode);
    }

    public CosemDataObject(ObisCode obisCode, DataObject value) {
        super(CosemClassId.DATA, obisCode);
        this.value = value;
    }

    // -------------------------------------------------------------------------
    // Population (called by CosemClient after a GET)
    // -------------------------------------------------------------------------

    /**
     * Sets the raw {@link DataObject} value. Called by the client after reading
     * attribute 2 from the meter.
     */
    public void setValue(DataObject value) {
        this.value = value;
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /**
     * Returns the raw {@link DataObject} value.
     *
     * @throws IllegalStateException if the value has not been populated yet
     */
    public DataObject getValue() {
        assertPopulated();
        return value;
    }

    /**
     * Convenience method — returns the value as a string.
     * Works for VISIBLE_STRING and OCTET_STRING (decoded as Latin-1).
     *
     * @throws ApduException         if the value cannot be returned as a string
     * @throws IllegalStateException if not yet populated
     */
    public String getStringValue() throws ApduException {
        assertPopulated();
        return value.getString();
    }

    /**
     * Convenience method — returns the value as a long.
     *
     * @throws ApduException         if the value is not a numeric type
     * @throws IllegalStateException if not yet populated
     */
    public long getLongValue() throws ApduException {
        assertPopulated();
        return value.getLong();
    }

    /**
     * Returns {@code true} if the value attribute has been populated.
     */
    public boolean isPopulated() {
        return value != null;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void assertPopulated() {
        if (value == null) {
            throw new IllegalStateException(
                    "CosemDataObject " + obisCode + " has not been populated. "
                            + "Call client.get() first.");
        }
    }
}