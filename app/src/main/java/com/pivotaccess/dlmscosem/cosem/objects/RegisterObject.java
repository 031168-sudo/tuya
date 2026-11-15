package com.pivotaccess.dlmscosem.cosem.objects;

import com.pivotaccess.dlmscosem.apdu.ApduException;
import com.pivotaccess.dlmscosem.apdu.DataObject;
import com.pivotaccess.dlmscosem.cosem.CosemClassId;
import com.pivotaccess.dlmscosem.cosem.CosemObject;
import com.pivotaccess.dlmscosem.cosem.ObisCode;
import com.pivotaccess.dlmscosem.cosem.ScalerUnit;

/**
 * COSEM Register object — Interface Class ID 3 (IEC 62056-62).
 *
 * <p>The most commonly read object in electricity metering. Stores a single
 * measured or calculated value (energy, power, voltage, current, etc.) with
 * a scaler and unit.
 *
 * <h3>Attributes</h3>
 * <ol>
 *   <li>{@code logical_name} — OBIS code as OCTET STRING (read-only)</li>
 *   <li>{@code value}        — the register value (INTEGER, LONG, DOUBLE_LONG, etc.)</li>
 *   <li>{@code scaler_unit}  — STRUCTURE { INTEGER scaler, ENUM unit }</li>
 * </ol>
 *
 * <h3>Methods</h3>
 * <ol>
 *   <li>{@code reset} — resets the register value to its default (usually 0)</li>
 * </ol>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * RegisterObject reg = (RegisterObject) client.get(
 *         ObisCode.ACTIVE_ENERGY_IMPORT, CosemClassId.REGISTER);
 *
 * double kWh = reg.getPhysicalValue() / 1000.0;
 * System.out.printf("%.3f kWh (raw=%d, scaler=%d, unit=%s)%n",
 *         kWh, reg.getRawValue(), reg.getScalerUnit().getScaler(),
 *         reg.getScalerUnit().getUnit().getSymbol());
 * }</pre>
 */
public final class RegisterObject extends CosemObject {

    /** Attribute index for {@code value}. */
    public static final int ATTR_VALUE       = 2;

    /** Attribute index for {@code scaler_unit}. */
    public static final int ATTR_SCALER_UNIT = 3;

    /** Method index for {@code reset}. */
    public static final int METHOD_RESET     = 1;

    // -------------------------------------------------------------------------

    private DataObject  rawValueObject;
    private ScalerUnit  scalerUnit;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public RegisterObject(ObisCode obisCode) {
        super(CosemClassId.REGISTER, obisCode);
    }

    // -------------------------------------------------------------------------
    // Population (called by CosemClient)
    // -------------------------------------------------------------------------

    /**
     * Sets the raw value DataObject (attribute 2). Called by the client.
     */
    public void setRawValue(DataObject rawValue) {
        this.rawValueObject = rawValue;
    }

    /**
     * Sets the scaler-unit (attribute 3). Called by the client.
     *
     * @param scalerUnitStructure the STRUCTURE DataObject from attribute 3
     * @throws ApduException if the structure is malformed
     */
    public void setScalerUnit(DataObject scalerUnitStructure) throws ApduException {
        this.scalerUnit = ScalerUnit.decode(scalerUnitStructure);
    }

    /**
     * Directly sets the {@link ScalerUnit} (e.g. when already decoded).
     */
    public void setScalerUnit(ScalerUnit scalerUnit) {
        this.scalerUnit = scalerUnit;
    }

    // -------------------------------------------------------------------------
    // Value accessors
    // -------------------------------------------------------------------------

    /**
     * Returns the raw integer value from the meter (before scaler is applied).
     *
     * @throws ApduException         if the value DataObject is not numeric
     * @throws IllegalStateException if value has not been populated
     */
    public long getRawValue() throws ApduException {
        assertValuePopulated();
        return rawValueObject.getLong();
    }

    /**
     * Returns the physical value with scaler applied:
     * {@code physicalValue = rawValue × 10^scaler}.
     *
     * <p>If the scaler-unit has not been read (e.g. client only read attr 2),
     * the raw value is returned as a double unchanged.
     *
     * @throws ApduException         if the value cannot be read
     * @throws IllegalStateException if value has not been populated
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
     * Convenience method — returns the physical value, or {@code null} if it cannot be decoded.
     * @return the physical value, or {@code null} if it cannot be decoded (e.g. non-numeric or not populated)
     */
    public Double getPhysicalValueOrNull() {
        assertValuePopulated();

        try {
            return getPhysicalValue();
        } catch (ApduException e) {
            return null;
        }
    }

    /**
     * Returns the raw {@link DataObject} value as received from the meter.
     *
     * @throws IllegalStateException if value has not been populated
     */
    public DataObject getRawValueObject() {
        assertValuePopulated();
        return rawValueObject;
    }

    /**
     * Returns the {@link ScalerUnit}, or {@code null} if attribute 3 was not read.
     */
    public ScalerUnit getScalerUnit() {
        return scalerUnit;
    }

    /**
     * Convenience — returns the unit symbol string (e.g. "Wh", "W", "V").
     * Returns an empty string if scaler-unit was not read.
     */
    public String getUnitSymbol() {
        return scalerUnit != null ? scalerUnit.getUnit().getSymbol() : "";
    }

    /**
     * Returns {@code true} if the value attribute has been populated.
     */
    public boolean isPopulated() {
        return rawValueObject != null;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void assertValuePopulated() {
        if (rawValueObject == null) {
            throw new IllegalStateException(
                    "RegisterObject " + obisCode + " value not populated. "
                            + "Call client.get() first.");
        }
    }

    @Override
    public String toString() {
        if (rawValueObject == null) return super.toString() + " [not populated]";
        try {
            String unit = scalerUnit != null ? " " + scalerUnit.getUnit().getSymbol() : "";
            return super.toString()
                    + " value=" + (scalerUnit != null ? getPhysicalValue() : getRawValue())
                    + unit;
        } catch (ApduException e) {
            return super.toString() + " [decode error: " + e.getMessage() + "]";
        }
    }
}