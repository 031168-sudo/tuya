package com.pivotaccess.dlmscosem.cosem.objects;

import com.pivotaccess.dlmscosem.apdu.ApduException;
import com.pivotaccess.dlmscosem.apdu.DataObject;
import com.pivotaccess.dlmscosem.cosem.CosemClassId;
import com.pivotaccess.dlmscosem.cosem.CosemObject;
import com.pivotaccess.dlmscosem.cosem.ObisCode;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * COSEM Clock object — Interface Class ID 8 (IEC 62056-62).
 *
 * <p>Manages the meter's real-time clock. The standard OBIS code is
 * {@link ObisCode#CLOCK} ({@code 0.0.1.0.0.255}).
 *
 * <h3>Attributes</h3>
 * <ol>
 *   <li>{@code logical_name}       — OBIS code</li>
 *   <li>{@code time}               — current date-time (12-byte OCTET STRING)</li>
 *   <li>{@code time_zone}          — offset from UTC in minutes (INTEGER)</li>
 *   <li>{@code status}             — clock status byte</li>
 *   <li>{@code daylight_savings_begin} — DST begin time</li>
 *   <li>{@code daylight_savings_end}   — DST end time</li>
 *   <li>{@code daylight_savings_deviation} — DST deviation in minutes</li>
 *   <li>{@code daylight_savings_enabled}   — DST enable flag</li>
 *   <li>{@code clock_base}         — clock synchronisation reference</li>
 * </ol>
 *
 * <h3>Methods</h3>
 * <ol>
 *   <li>{@code adjust_to_quarter}          — rounds time to nearest 15 min</li>
 *   <li>{@code adjust_to_measuring_period} — rounds to measuring period</li>
 *   <li>{@code adjust_to_minute}           — rounds to nearest minute</li>
 *   <li>{@code adjust_to_preset_time}      — sets to a predefined preset</li>
 *   <li>{@code preset_adjusting_time}      — schedules a future adjustment</li>
 *   <li>{@code shift_time}                 — shifts time by a given amount</li>
 * </ol>
 *
 * <h3>COSEM date-time encoding (12 bytes)</h3>
 * <pre>
 * Byte  0–1 : year          (uint16, big-endian; 0xFFFF = not specified)
 * Byte  2   : month         (1–12; 0xFE = end of DST, 0xFD = begin of DST)
 * Byte  3   : day of month  (1–31; 0xFE = last day, 0xFD = 2nd last day)
 * Byte  4   : day of week   (1=Mon … 7=Sun; 0xFF = not specified)
 * Byte  5   : hour          (0–23; 0xFF = not specified)
 * Byte  6   : minute        (0–59)
 * Byte  7   : second        (0–59)
 * Byte  8   : hundredths    (0–99; 0xFF = not specified)
 * Byte  9–10: deviation     (int16 minutes from UTC; 0x8000 = not specified)
 * Byte  11  : clock status  (bit field)
 * </pre>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * ClockObject clock = (ClockObject) client.get(
 *         ObisCode.CLOCK, CosemClassId.CLOCK);
 * OffsetDateTime time = clock.getTime();
 * System.out.println("Meter time: " + time);
 * }</pre>
 */
public final class ClockObject extends CosemObject {

    // Attribute indices
    public static final int ATTR_TIME                          = 2;
    public static final int ATTR_TIME_ZONE                     = 3;
    public static final int ATTR_STATUS                        = 4;
    public static final int ATTR_DAYLIGHT_SAVINGS_BEGIN        = 5;
    public static final int ATTR_DAYLIGHT_SAVINGS_END          = 6;
    public static final int ATTR_DAYLIGHT_SAVINGS_DEVIATION    = 7;
    public static final int ATTR_DAYLIGHT_SAVINGS_ENABLED      = 8;
    public static final int ATTR_CLOCK_BASE                    = 9;

    // Method indices
    public static final int METHOD_ADJUST_TO_QUARTER           = 1;
    public static final int METHOD_ADJUST_TO_MEASURING_PERIOD  = 2;
    public static final int METHOD_ADJUST_TO_MINUTE            = 3;
    public static final int METHOD_ADJUST_TO_PRESET_TIME       = 4;
    public static final int METHOD_PRESET_ADJUSTING_TIME       = 5;
    public static final int METHOD_SHIFT_TIME                  = 6;

    // COSEM "not specified" sentinel values
    private static final int NOT_SPECIFIED_YEAR     = 0xFFFF;
    private static final int NOT_SPECIFIED_BYTE     = 0xFF;
    private static final int NOT_SPECIFIED_DEVIATION = 0x8000;

    // -------------------------------------------------------------------------

    private byte[]  rawTimeBytes;     // 12-byte DATE_TIME
    private Integer timeZoneMinutes;  // UTC offset in minutes
    private Integer status;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public ClockObject(ObisCode obisCode) {
        super(CosemClassId.CLOCK, obisCode);
    }

    public ClockObject() {
        this(ObisCode.CLOCK);
    }

    // -------------------------------------------------------------------------
    // Population (called by CosemClient)
    // -------------------------------------------------------------------------

    /**
     * Sets the raw time value from attribute 2 (DATE_TIME or OCTET_STRING,
     * 12 bytes).
     *
     * @throws ApduException if the DataObject is not a 12-byte date-time
     */
    public void setTimeRaw(DataObject timeData) throws ApduException {
        byte[] bytes;
        switch (timeData.getType()) {
            case DATE_TIME:
                bytes = timeData.getDateTimeBytes();
                break;
            case OCTET_STRING:
                bytes = timeData.getOctetString();
                break;
            default:
                throw new ApduException(
                        "Clock time attribute must be DATE_TIME or OCTET_STRING, got: "
                                + timeData.getType());
        }
        if (bytes.length != 12) {
            throw new ApduException(
                    "Clock time must be 12 bytes, got: " + bytes.length);
        }
        this.rawTimeBytes = bytes;
    }

    public void setTimeZone(DataObject timeZoneData) throws ApduException {
        this.timeZoneMinutes = (int) timeZoneData.getLong();
    }

    public void setStatus(DataObject statusData) throws ApduException {
        this.status = (int) statusData.getLong();
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /**
     * Returns the meter's current date and time as an {@link OffsetDateTime}.
     *
     * <p>The UTC offset is read from the deviation bytes in the date-time
     * encoding (bytes 9–10). If the deviation is marked as "not specified"
     * ({@code 0x8000}), {@link ZoneOffset#UTC} is used as a fallback.
     *
     * @throws IllegalStateException if the time has not been populated
     */
    public OffsetDateTime getTime() {
        assertPopulated();
        return parseDateTime(rawTimeBytes);
    }

    /**
     * Returns the raw 12-byte date-time value as received from the meter.
     *
     * @throws IllegalStateException if not yet populated
     */
    public byte[] getRawTimeBytes() {
        assertPopulated();
        return java.util.Arrays.copyOf(rawTimeBytes, 12);
    }

    /**
     * Returns the time zone offset in minutes from UTC, or {@code null} if
     * attribute 3 was not read.
     */
    public Integer getTimeZoneMinutes() {
        return timeZoneMinutes;
    }

    /**
     * Returns the clock status byte, or {@code null} if attribute 4 was not read.
     *
     * <p>Status bit meanings (IEC 62056-62):
     * <ul>
     *   <li>bit 0 — invalid value</li>
     *   <li>bit 1 — doubtful value</li>
     *   <li>bit 2 — different clock base</li>
     *   <li>bit 3 — invalid clock status</li>
     *   <li>bit 7 — daylight saving active</li>
     * </ul>
     */
    public Integer getStatus() {
        return status;
    }

    /** Returns {@code true} if the time attribute has been populated. */
    public boolean isPopulated() {
        return rawTimeBytes != null;
    }

    // -------------------------------------------------------------------------
    // Encoding — build 12-byte DATE_TIME for use in SET / ACTION requests
    // -------------------------------------------------------------------------

    /**
     * Encodes an {@link OffsetDateTime} into a 12-byte COSEM date-time for use
     * in a SET request on attribute 2.
     *
     * @param dateTime the date-time to encode
     * @return 12-byte COSEM date-time
     */
    public static byte[] encodeDateTime(OffsetDateTime dateTime) {
        byte[] b = new byte[12];
        int year    = dateTime.getYear();
        int month   = dateTime.getMonthValue();
        int day     = dateTime.getDayOfMonth();
        int weekday = dateTime.getDayOfWeek().getValue(); // 1=Mon, 7=Sun
        int hour    = dateTime.getHour();
        int minute  = dateTime.getMinute();
        int second  = dateTime.getSecond();
        int hundredths = dateTime.getNano() / 10_000_000;
        int deviation = -(dateTime.getOffset().getTotalSeconds() / 60); // minutes

        b[0]  = (byte) ((year >> 8) & 0xFF);
        b[1]  = (byte) (year & 0xFF);
        b[2]  = (byte) month;
        b[3]  = (byte) day;
        b[4]  = (byte) weekday;
        b[5]  = (byte) hour;
        b[6]  = (byte) minute;
        b[7]  = (byte) second;
        b[8]  = (byte) hundredths;
        b[9]  = (byte) ((deviation >> 8) & 0xFF);
        b[10] = (byte) (deviation & 0xFF);
        b[11] = 0x00; // status — OK

        return b;
    }

    // -------------------------------------------------------------------------
    // Parsing
    // -------------------------------------------------------------------------

    /**
     * Parses a 12-byte COSEM date-time into an {@link OffsetDateTime}.
     *
     * <p>Fields marked as "not specified" (0xFF / 0xFFFF) are substituted with
     * reasonable defaults (e.g. 0 for hour/minute/second).
     */
    public static OffsetDateTime parseDateTime(byte[] b) {
        if (b == null || b.length != 12) {
            throw new IllegalArgumentException(
                    "DATE_TIME must be exactly 12 bytes, got: "
                            + (b == null ? 0 : b.length));
        }

        int year       = ((b[0] & 0xFF) << 8) | (b[1] & 0xFF);
        int month      = b[2] & 0xFF;
        int day        = b[3] & 0xFF;
        // b[4] = day of week (ignored — derived from date)
        int hour       = b[5] & 0xFF;
        int minute     = b[6] & 0xFF;
        int second     = b[7] & 0xFF;
        int hundredths = b[8] & 0xFF;
        int devRaw     = ((b[9] & 0xFF) << 8) | (b[10] & 0xFF);

        // Apply "not specified" defaults
        if (year       == NOT_SPECIFIED_YEAR)  year       = 2000;
        if (month      == NOT_SPECIFIED_BYTE)  month      = 1;
        if (day        == NOT_SPECIFIED_BYTE)  day        = 1;
        if (hour       == NOT_SPECIFIED_BYTE)  hour       = 0;
        if (minute     == NOT_SPECIFIED_BYTE)  minute     = 0;
        if (second     == NOT_SPECIFIED_BYTE)  second     = 0;
        if (hundredths == NOT_SPECIFIED_BYTE)  hundredths = 0;

        // Deviation: signed 16-bit integer, minutes from UTC
        // 0x8000 = not specified → use UTC
        ZoneOffset offset;
        if ((devRaw & 0xFFFF) == (NOT_SPECIFIED_DEVIATION & 0xFFFF)) {
            offset = ZoneOffset.UTC;
        } else {
            // Convert to signed
            short devSigned = (short) devRaw;
            offset = ZoneOffset.ofTotalSeconds(-devSigned * 60);
        }

        int nanos = hundredths * 10_000_000;

        return OffsetDateTime.of(
                LocalDate.of(year, month, day),
                LocalTime.of(hour, minute, second, nanos),
                offset);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void assertPopulated() {
        if (rawTimeBytes == null) {
            throw new IllegalStateException(
                    "ClockObject not populated. Call client.get() first.");
        }
    }

    @Override
    public String toString() {
        if (rawTimeBytes == null) return super.toString() + " [not populated]";
        return super.toString() + " time=" + getTime();
    }
}