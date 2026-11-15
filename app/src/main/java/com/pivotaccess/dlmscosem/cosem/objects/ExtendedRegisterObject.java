package com.pivotaccess.dlmscosem.cosem.objects;

import com.pivotaccess.dlmscosem.apdu.ApduException;
import com.pivotaccess.dlmscosem.apdu.DataObject;
import com.pivotaccess.dlmscosem.cosem.CosemClassId;
import com.pivotaccess.dlmscosem.cosem.CosemObject;
import com.pivotaccess.dlmscosem.cosem.ObisCode;
import com.pivotaccess.dlmscosem.cosem.ScalerUnit;

import java.time.ZonedDateTime;

/**
 * COSEM Extended Register object — Interface Class ID 4 (IEC 62056-62).
 *
 * <p>Extends the Register class (ID 3) with a capture time — the timestamp
 * at which the value was last recorded. Used for maximum demand registers
 * and other time-stamped measurements.
 *
 * <h3>Attributes</h3>
 * <ol>
 *   <li>{@code logical_name}  — OBIS code as OCTET STRING (read-only)</li>
 *   <li>{@code value}         — the register value</li>
 *   <li>{@code scaler_unit}   — STRUCTURE { INTEGER scaler, ENUM unit }</li>
 *   <li>{@code status}        — status of the value (implementation-dependent)</li>
 *   <li>{@code capture_time}  — date-time when value was last captured</li>
 * </ol>
 *
 * <h3>Methods</h3>
 * <ol>
 *   <li>{@code reset}   — resets value and capture time</li>
 *   <li>{@code capture} — triggers an immediate capture</li>
 * </ol>
 */
public final class ExtendedRegisterObject extends CosemObject {

    /** Attribute index for {@code value}. */
    public static final int ATTR_VALUE        = 2;

    /** Attribute index for {@code scaler_unit}. */
    public static final int ATTR_SCALER_UNIT  = 3;

    /** Attribute index for {@code status}. */
    public static final int ATTR_STATUS       = 4;

    /** Attribute index for {@code capture_time}. */
    public static final int ATTR_CAPTURE_TIME = 5;

    /** Method index for {@code reset}. */
    public static final int METHOD_RESET      = 1;

    /** Method index for {@code capture}. */
    public static final int METHOD_CAPTURE    = 2;

    // -------------------------------------------------------------------------

    private DataObject rawValueObject;
    private ScalerUnit scalerUnit;
    private DataObject status;
    private DataObject captureTimeRaw;    // DATE_TIME DataObject (12 bytes)

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public ExtendedRegisterObject(ObisCode obisCode) {
        super(CosemClassId.EXTENDED_REGISTER, obisCode);
    }

    // -------------------------------------------------------------------------
    // Population (called by CosemClient)
    // -------------------------------------------------------------------------

    public void setRawValue(DataObject rawValue) {
        this.rawValueObject = rawValue;
    }

    public void setScalerUnit(DataObject scalerUnitStructure) throws ApduException {
        this.scalerUnit = ScalerUnit.decode(scalerUnitStructure);
    }

    public void setScalerUnit(ScalerUnit scalerUnit) {
        this.scalerUnit = scalerUnit;
    }

    public void setStatus(DataObject status) {
        this.status = status;
    }

    public void setCaptureTime(DataObject captureTime) {
        this.captureTimeRaw = captureTime;
    }

    // -------------------------------------------------------------------------
    // Value accessors
    // -------------------------------------------------------------------------

    /**
     * Returns the raw integer value from the meter (before scaler applied).
     *
     * @throws ApduException         if the value is not numeric
     * @throws IllegalStateException if not yet populated
     */
    public long getRawValue() throws ApduException {
        assertValuePopulated();
        return rawValueObject.getLong();
    }

    /**
     * Returns the physical value with scaler applied.
     *
     * @throws ApduException         if the value cannot be read
     * @throws IllegalStateException if not yet populated
     */
    public double getPhysicalValue() throws ApduException {
        assertValuePopulated();

        // Extract as double to safely support FLOAT32/FLOAT64 registers
        double raw = rawValueObject.getDouble();

        if (scalerUnit != null) {
            // If the meter provided a scaler, apply it using powers of 10
            // (Even if raw is a float, the scaler math remains the same)
            return raw * Math.pow(10, scalerUnit.getScaler());
        }
        return raw;
    }

    /**
     * Returns the raw value {@link DataObject}.
     */
    public DataObject getRawValueObject() {
        assertValuePopulated();
        return rawValueObject;
    }

    /**
     * Returns the status {@link DataObject}, or {@code null} if not read.
     */
    public DataObject getStatus() {
        return status;
    }

    /**
     * Returns the capture time as a raw {@link DataObject} (DATE_TIME, 12 bytes),
     * or {@code null} if attribute 5 was not read.
     *
     * <p>Use a {@code CosemDateTimeConverter} (or your own parser) to convert
     * the 12-byte COSEM date-time to a {@link ZonedDateTime}.
     */
    public DataObject getCaptureTimeRaw() {
        return captureTimeRaw;
    }

    public ScalerUnit getScalerUnit() {
        return scalerUnit;
    }

    public String getUnitSymbol() {
        return scalerUnit != null ? scalerUnit.getUnit().getSymbol() : "";
    }

    public boolean isPopulated() {
        return rawValueObject != null;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void assertValuePopulated() {
        if (rawValueObject == null) {
            throw new IllegalStateException(
                    "ExtendedRegisterObject " + obisCode + " not populated.");
        }
    }
}