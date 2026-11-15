package com.pivotaccess.dlmscosem.cosem;

/**
 * COSEM interface class IDs as defined in the DLMS/COSEM Blue Book
 * (IEC 62056-62).
 *
 * <p>Every COSEM object has a class ID that determines its attributes and
 * methods. The class ID is included in every GET/SET/ACTION request APDU.
 *
 * <p>Only the most commonly used classes in electricity metering are listed
 * here. The full list is available in the DLMS Blue Book.
 */
public enum CosemClassId {

    // -------------------------------------------------------------------------
    // General purpose
    // -------------------------------------------------------------------------

    /** Class 1 — Data. Stores a single value of any data type. */
    DATA                (1),

    /** Class 3 — Register. Stores a measured/calculated value with scaler+unit. */
    REGISTER            (3),

    /** Class 4 — Extended Register. Register with capture time. */
    EXTENDED_REGISTER   (4),

    /** Class 5 — Demand Register. Register with averaging over an interval. */
    DEMAND_REGISTER     (5),

    /** Class 6 — Register Activation. Selects active tariff. */
    REGISTER_ACTIVATION (6),

    /** Class 7 — Profile Generic. Stores a time series of captured objects (load profile). */
    PROFILE_GENERIC     (7),

    /** Class 8 — Clock. Stores and manages the meter's real-time clock. */
    CLOCK               (8),

    /** Class 9 — Script Table. Stores scripts executed by the meter. */
    SCRIPT_TABLE        (9),

    /** Class 10 — Schedule. Defines when scripts are executed. */
    SCHEDULE            (10),

    /** Class 11 — Special Days Table. Defines special tariff days. */
    SPECIAL_DAYS_TABLE  (11),

    // -------------------------------------------------------------------------
    // Association / communication
    // -------------------------------------------------------------------------

    /** Class 12 — Notification Forwarder. */
    NOTIFICATION_FORWARDER(12),

    /** Class 15 — Association LN. Manages a logical name (LN) association. */
    ASSOCIATION_LN      (15),

    /** Class 17 — SAP Assignment. Maps SAP to logical devices. */
    SAP_ASSIGNMENT      (17),

    /** Class 18 — Image Transfer. Firmware/software update. */
    IMAGE_TRANSFER      (18),

    // -------------------------------------------------------------------------
    // IEC 62056-21 / optical port
    // -------------------------------------------------------------------------

    /** Class 19 — IEC local port setup. Configures optical/serial port. */
    IEC_LOCAL_PORT      (19),

    // -------------------------------------------------------------------------
    // Electricity specific
    // -------------------------------------------------------------------------

    /** Class 21 — Register Monitor. Triggers actions on register threshold crossing. */
    REGISTER_MONITOR    (21),

    /** Class 22 — Utility Tables. Access to ANSI tables. */
    UTILITY_TABLES      (22),

    /** Class 23 — Modem Configuration. */
    MODEM_CONFIGURATION (23),

    /** Class 40 — Push Setup. Configures push notifications to a server. */
    PUSH_SETUP          (40),

    /** Class 64 — Security Setup. Manages keys and authentication. */
    SECURITY_SETUP      (64),

    /** Class 70 — Disconnect Control. Controls a load-disconnecting relay. */
    DISCONNECT_CONTROL  (70),

    /** Class 71 — Limiter. Monitors a threshold and triggers disconnect. */
    LIMITER             (71),

    /** Class 72 — M-Bus Client. Reads M-Bus slave meters. */
    MBUS_CLIENT         (72);

    // -------------------------------------------------------------------------

    private final int id;

    CosemClassId(int id) {
        this.id = id;
    }

    /** The numeric class ID sent in DLMS APDUs. */
    public int getId() {
        return id;
    }

    /**
     * Looks up a {@link CosemClassId} by its numeric ID.
     *
     * @param id numeric class ID
     * @return matching enum constant
     * @throws IllegalArgumentException if no class matches the given ID
     */
    public static CosemClassId fromId(int id) {
        for (CosemClassId c : values()) {
            if (c.id == id) return c;
        }
        throw new IllegalArgumentException("Unknown COSEM class ID: " + id);
    }
}