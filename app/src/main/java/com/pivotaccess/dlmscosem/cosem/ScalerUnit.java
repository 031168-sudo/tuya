package com.pivotaccess.dlmscosem.cosem;

import com.pivotaccess.dlmscosem.apdu.ApduException;
import com.pivotaccess.dlmscosem.apdu.DataObject;

/**
 * COSEM scaler-unit pair, as defined in the DLMS Blue Book (IEC 62056-62).
 *
 * <p>Register objects store a raw integer value together with a scaler and a
 * unit. The actual physical value is computed as:
 *
 * <pre>
 *   physicalValue = rawValue × 10^scaler
 * </pre>
 *
 * <p>For example, if {@code rawValue = 143520}, {@code scaler = -1},
 * {@code unit = Wh}, then the physical value is {@code 14352.0 Wh = 14.352 kWh}.
 *
 * <h3>Decoding</h3>
 * <p>The scaler-unit attribute (attr 3 of a Register) is a STRUCTURE containing
 * two elements: INTEGER(scaler) and ENUM(unit).
 */
public final class ScalerUnit {

    private final int    scaler;   // signed 8-bit (-128 to +127); power of 10
    private final Unit   unit;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public ScalerUnit(int scaler, Unit unit) {
        this.scaler = scaler;
        this.unit   = unit;
    }

    // -------------------------------------------------------------------------
    // Decoding from DataObject (STRUCTURE)
    // -------------------------------------------------------------------------

    /**
     * Decodes a scaler-unit pair from a COSEM STRUCTURE DataObject (attribute 3
     * of Register, ExtendedRegister, or DemandRegister).
     *
     * <p>Expected structure: {@code STRUCTURE { INTEGER scaler, ENUM unit }}
     *
     * @param structure the scaler-unit STRUCTURE DataObject
     * @return decoded {@link ScalerUnit}
     * @throws ApduException if the structure is malformed
     */
    public static ScalerUnit decode(DataObject structure) throws ApduException {
        java.util.List<DataObject> items = structure.getList();
        if (items.size() < 2) {
            throw new ApduException(
                    "ScalerUnit structure must have 2 elements, got: " + items.size());
        }
        int  scaler  = (int) items.get(0).getLong();
        int  unitTag = (int) items.get(1).getLong();
        Unit unit    = Unit.fromCode(unitTag);
        return new ScalerUnit(scaler, unit);
    }

    // -------------------------------------------------------------------------
    // Value scaling
    // -------------------------------------------------------------------------

    /**
     * Applies the scaler to a raw register value, returning the physical value.
     *
     * @param rawValue raw integer value from the meter
     * @return physical value = rawValue × 10^scaler
     */
    public double applyTo(long rawValue) {
        if (scaler == 0) return (double) rawValue;
        return rawValue * Math.pow(10, scaler);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /** Scaler exponent — physical value = raw × 10^scaler. */
    public int  getScaler() { return scaler; }

    /** Physical unit. */
    public Unit getUnit()   { return unit; }

    @Override
    public String toString() {
        return "×10^" + scaler + " " + unit.getSymbol();
    }

    // -------------------------------------------------------------------------
    // Unit enum — COSEM unit codes per IEC 62056-62 Table 4
    // -------------------------------------------------------------------------

    /**
     * COSEM unit codes as defined in IEC 62056-62 Table 4 / DLMS Blue Book.
     */
    public enum Unit {

        UNDEFINED           (0,   ""),
        YEAR                (1,   "a"),
        MONTH               (2,   "mo"),
        WEEK                (3,   "wk"),
        DAY                 (4,   "d"),
        HOUR                (5,   "h"),
        MINUTE              (6,   "min"),
        SECOND              (7,   "s"),
        DEGREES             (8,   "°"),
        DEGREES_CELSIUS     (9,   "°C"),
        CURRENCY            (10,  "currency"),
        METRES              (11,  "m"),
        METRES_PER_SEC      (12,  "m/s"),
        CUBIC_METRES        (13,  "m³"),
        CUBIC_METRES_CORR   (14,  "m³ corr"),
        CUBIC_METRES_PER_H  (15,  "m³/h"),
        CUBIC_METRES_PER_H_CORR (16, "m³/h corr"),
        CUBIC_METRES_PER_DAY(17,  "m³/d"),
        CUBIC_METRES_PER_DAY_CORR(18, "m³/d corr"),
        LITRES              (19,  "l"),
        KG                  (20,  "kg"),
        NEWTONS             (21,  "N"),
        NEWTONS_METRES      (22,  "Nm"),
        PASCAL              (23,  "Pa"),
        BAR                 (24,  "bar"),
        JOULE               (25,  "J"),
        JOULE_PER_H         (26,  "J/h"),
        WATT                (27,  "W"),
        VOLT_AMPERE         (28,  "VA"),
        VAR                 (29,  "var"),
        WATT_HOUR           (30,  "Wh"),
        VOLT_AMPERE_HOUR    (31,  "VAh"),
        VAR_HOUR            (32,  "varh"),
        AMPERE              (33,  "A"),
        COULOMB             (34,  "C"),
        VOLT                (35,  "V"),
        VOLT_PER_METRE      (36,  "V/m"),
        FARAD               (37,  "F"),
        OHM                 (38,  "Ω"),
        OHM_METRES          (39,  "Ωm²/m"),
        WEBER               (40,  "Wb"),
        TESLA               (41,  "T"),
        AMPERE_PER_METRE    (42,  "A/m"),
        HENRY               (43,  "H"),
        HERTZ               (44,  "Hz"),
        ACTIVE_ENERGY       (45,  "Wh"),  // alias
        REACTIVE_ENERGY     (46,  "varh"),// alias
        APPARENT_ENERGY     (47,  "VAh"), // alias
        VOLT_SQUARED_HOUR   (48,  "V²h"),
        AMPERE_SQUARED_HOUR (49,  "A²h"),
        KG_PER_SEC          (50,  "kg/s"),
        SIEMENS             (51,  "S"),
        KELVIN              (52,  "K"),
        VOLT_SQUARED        (53,  "V²"),
        AMPERE_SQUARED      (54,  "A²"),
        KG_PER_M3           (55,  "kg/m³"),
        JOULE_PER_KG        (56,  "J/kg"),
        PERCENTAGE          (98,  "%"),
        AMPERE_HOURS        (99,  "Ah"),
        ENERGY_PER_VOLUME   (60,  "Wh/m³"),
        CALORIFIC_VALUE     (61,  "J/m³"),
        MOLE_PERCENT        (62,  "mol%"),
        MASS_DENSITY        (63,  "g/m³"),
        PASCAL_SECONDS      (64,  "Pas"),
        JOULE_PER_KELVIN    (65,  "J/K"),
        COUNT               (255, "");

        private final int    code;
        private final String symbol;

        Unit(int code, String symbol) {
            this.code   = code;
            this.symbol = symbol;
        }

        public int    getCode()   { return code; }
        public String getSymbol() { return symbol; }

        @Override
        public String toString()  { return symbol.isEmpty() ? name() : symbol; }

        /**
         * Looks up a unit by its COSEM unit code.
         * Returns {@link #UNDEFINED} for unknown codes rather than throwing.
         */
        public static Unit fromCode(int code) {
            for (Unit u : values()) {
                if (u.code == code) return u;
            }
            return UNDEFINED;
        }
    }
}