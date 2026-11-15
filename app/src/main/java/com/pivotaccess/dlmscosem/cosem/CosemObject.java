package com.pivotaccess.dlmscosem.cosem;

/**
 * Abstract base class for all COSEM interface objects.
 *
 * <p>Every COSEM object on a meter is identified by its class ID and OBIS code.
 * Subclasses represent specific interface classes (Register, Clock,
 * ProfileGeneric, etc.) and expose typed methods for reading and writing
 * their attributes.
 *
 * <p>COSEM objects are created and populated by the {@code CosemClient}. They
 * are plain data holders — they do not hold a connection reference or perform
 * I/O themselves.
 *
 * <h3>Attribute numbering</h3>
 * <p>All COSEM objects have at least two standard attributes:
 * <ul>
 *   <li>Attribute 1 — {@code logical_name} (OBIS code as OCTET STRING)</li>
 *   <li>Attribute 2 — class-specific value (e.g. value for Register)</li>
 * </ul>
 */
public abstract class CosemObject {

    protected final CosemClassId classId;
    protected final ObisCode     obisCode;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    protected CosemObject(CosemClassId classId, ObisCode obisCode) {
        this.classId  = classId;
        this.obisCode = obisCode;
    }

    // -------------------------------------------------------------------------
    // Common accessors
    // -------------------------------------------------------------------------

    /**
     * Returns the COSEM interface class ID of this object.
     * Used in all APDU requests.
     */
    public CosemClassId getClassId() {
        return classId;
    }

    /**
     * Returns the OBIS code that uniquely identifies this object on the meter.
     */
    public ObisCode getObisCode() {
        return obisCode;
    }

    /**
     * Returns the numeric class ID (convenience method for APDU construction).
     */
    public int getClassIdValue() {
        return classId.getId();
    }

    // -------------------------------------------------------------------------
    // Object overrides
    // -------------------------------------------------------------------------

    @Override
    public String toString() {
        return getClass().getSimpleName()
                + "{classId=" + classId.getId()
                + ", obis=" + obisCode + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CosemObject other)) return false;
        return classId == other.classId && obisCode.equals(other.obisCode);
    }

    @Override
    public int hashCode() {
        return 31 * classId.getId() + obisCode.hashCode();
    }
}