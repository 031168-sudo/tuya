package com.pivotaccess.dlmscosem.apdu;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A typed DLMS/COSEM data value as defined in IEC 62056-62.
 *
 * <p>Every value exchanged in a DLMS GET/SET/ACTION is a {@code DataObject}.
 * It carries a {@link DlmsDataType} tag and the raw Java value. Use the
 * static factory methods to construct instances, and the typed getters to
 * extract values safely.
 *
 * <h3>Examples</h3>
 * <pre>{@code
 * DataObject num    = DataObject.ofDoubleLong(12345L);
 * DataObject str    = DataObject.ofVisibleString("METER-001");
 * DataObject bytes  = DataObject.ofOctetString(new byte[]{0x01, 0x02});
 * DataObject array  = DataObject.ofArray(List.of(num, str));
 * DataObject struct = DataObject.ofStructure(List.of(num, str));
 * DataObject nullVal = DataObject.ofNull();
 * }</pre>
 */
public final class DataObject {

    private final DlmsDataType       type;
    private final Object             value;   // typed Java value — see factories below

    // -------------------------------------------------------------------------
    // Private constructor
    // -------------------------------------------------------------------------

    private DataObject(DlmsDataType type, Object value) {
        this.type  = type;
        this.value = value;
    }

    // -------------------------------------------------------------------------
    // Static factories
    // -------------------------------------------------------------------------

    public static DataObject ofNull() {
        return new DataObject(DlmsDataType.NULL_DATA, null);
    }

    public static DataObject ofBoolean(boolean v) {
        return new DataObject(DlmsDataType.BOOLEAN, v);
    }

    public static DataObject ofInteger(int v) {
        return new DataObject(DlmsDataType.INTEGER, (byte) v);
    }

    public static DataObject ofUnsigned(int v) {
        return new DataObject(DlmsDataType.UNSIGNED, (short) (v & 0xFF));
    }

    public static DataObject ofLong(int v) {
        return new DataObject(DlmsDataType.LONG, (short) v);
    }

    public static DataObject ofLongUnsigned(int v) {
        return new DataObject(DlmsDataType.LONG_UNSIGNED, v & 0xFFFF);
    }

    public static DataObject ofDoubleLong(long v) {
        return new DataObject(DlmsDataType.DOUBLE_LONG, (int) v);
    }

    public static DataObject ofDoubleLongUnsigned(long v) {
        return new DataObject(DlmsDataType.DOUBLE_LONG_UNSIGNED, v & 0xFFFFFFFFL);
    }

    public static DataObject ofLong64(long v) {
        return new DataObject(DlmsDataType.LONG64, v);
    }

    public static DataObject ofLong64Unsigned(long v) {
        return new DataObject(DlmsDataType.LONG64_UNSIGNED, v);
    }

    public static DataObject ofFloat32(float v) {
        return new DataObject(DlmsDataType.FLOAT32, v);
    }

    public static DataObject ofFloat64(double v) {
        return new DataObject(DlmsDataType.FLOAT64, v);
    }

    public static DataObject ofEnum(int v) {
        return new DataObject(DlmsDataType.ENUM, v & 0xFF);
    }

    public static DataObject ofOctetString(byte[] v) {
        return new DataObject(DlmsDataType.OCTET_STRING, Arrays.copyOf(v, v.length));
    }

    public static DataObject ofVisibleString(String v) {
        return new DataObject(DlmsDataType.VISIBLE_STRING, v);
    }

    public static DataObject ofUtf8String(String v) {
        return new DataObject(DlmsDataType.UTF8_STRING, v);
    }

    public static DataObject ofBitString(byte[] v, int bitCount) {
        return new DataObject(DlmsDataType.BIT_STRING, new BitString(v, bitCount));
    }

    /**
     * Date-time as raw 12-byte octet string.
     * Layout: year(2) month(1) day(1) weekday(1) hour(1) min(1) sec(1)
     *         hundredths(1) deviation(2) status(1)
     */
    public static DataObject ofDateTime(byte[] raw12Bytes) {
        if (raw12Bytes.length != 12) {
            throw new IllegalArgumentException("DateTime must be exactly 12 bytes");
        }
        return new DataObject(DlmsDataType.DATE_TIME, Arrays.copyOf(raw12Bytes, 12));
    }

    /** Date as raw 5-byte octet string: year(2) month(1) day(1) weekday(1). */
    public static DataObject ofDate(byte[] raw5Bytes) {
        if (raw5Bytes.length != 5) {
            throw new IllegalArgumentException("Date must be exactly 5 bytes");
        }
        return new DataObject(DlmsDataType.DATE, Arrays.copyOf(raw5Bytes, 5));
    }

    /** Time as raw 4-byte octet string: hour(1) min(1) sec(1) hundredths(1). */
    public static DataObject ofTime(byte[] raw4Bytes) {
        if (raw4Bytes.length != 4) {
            throw new IllegalArgumentException("Time must be exactly 4 bytes");
        }
        return new DataObject(DlmsDataType.TIME, Arrays.copyOf(raw4Bytes, 4));
    }

    public static DataObject ofArray(List<DataObject> items) {
        return new DataObject(DlmsDataType.ARRAY, Collections.unmodifiableList(new ArrayList<>(items)));
    }

    public static DataObject ofStructure(List<DataObject> items) {
        return new DataObject(DlmsDataType.STRUCTURE, Collections.unmodifiableList(new ArrayList<>(items)));
    }

    public static DataObject ofDontCare() {
        return new DataObject(DlmsDataType.DONT_CARE, null);
    }

    // -------------------------------------------------------------------------
    // Type accessors
    // -------------------------------------------------------------------------

    public DlmsDataType getType() { return type; }

    public boolean isNull()      { return type == DlmsDataType.NULL_DATA; }
    public boolean isNumber()    {
        switch (type) {
            case INTEGER: case UNSIGNED: case LONG: case LONG_UNSIGNED:
            case DOUBLE_LONG: case DOUBLE_LONG_UNSIGNED:
            case LONG64: case LONG64_UNSIGNED:
            case FLOAT32: case FLOAT64: case ENUM: return true;
            default: return false;
        }
    }
    public boolean isString()    {
        return type == DlmsDataType.VISIBLE_STRING || type == DlmsDataType.UTF8_STRING;
    }
    public boolean isBytes()     { return type == DlmsDataType.OCTET_STRING; }
    public boolean isComposite() {
        return type == DlmsDataType.ARRAY || type == DlmsDataType.STRUCTURE;
    }

    // -------------------------------------------------------------------------
    // Typed value getters
    // -------------------------------------------------------------------------

    /** Returns the raw Java value. Prefer the typed getters below. */
    public Object getRawValue() { return value; }

    public boolean getBoolean() throws ApduException {
        assertType(DlmsDataType.BOOLEAN);
        return (Boolean) value;
    }

    /** Returns a numeric value as a long regardless of the underlying integer type. */
    public long getLong() throws ApduException {
        return switch (type) {
            case INTEGER -> ((Byte) value).longValue();
            case UNSIGNED, ENUM -> ((Integer) value).longValue();
            case LONG -> ((Short) value).longValue();
            case LONG_UNSIGNED -> ((Integer) value).longValue();
            case DOUBLE_LONG -> ((Integer) value).longValue();
            case DOUBLE_LONG_UNSIGNED -> (Long) value;
            case LONG64 -> (Long) value;
            case LONG64_UNSIGNED -> (Long) value;
            default -> throw new ApduException("Cannot get long from type: " + type);
        };
    }

    /**
     * Convenience method — returns a numeric value as a Long, or null if the type is not numeric.
     * @return the numeric value as a Long, or null if the type is not numeric
     */
    public Long getLongOrNull() {
        try {
            return getLong();
        } catch (ApduException e) {
            return null;
        }
    }

    /** Returns a numeric value as a double regardless of the underlying type. */
    public double getDouble() throws ApduException {
        if (type == DlmsDataType.FLOAT32)  return (double)(Float)  value;
        if (type == DlmsDataType.FLOAT64)  return (Double) value;
        return (double) getLong();
    }

    public byte[] getOctetString() throws ApduException {
        assertType(DlmsDataType.OCTET_STRING);
        return Arrays.copyOf((byte[]) value, ((byte[]) value).length);
    }

    public String getOctetStringAsText(Charset charset) throws ApduException {
        assertType(DlmsDataType.OCTET_STRING);
        byte[] bytes = (byte[]) this.getRawValue();
        return new String(bytes, charset);
    }

    public String getOctetStringAsTextOrNull(Charset charset) {
        if (type != DlmsDataType.OCTET_STRING) {
            return null;
        }
        byte[] bytes = (byte[]) this.getRawValue();
        return (bytes.length == 0) ? null : new String(bytes, charset);
    }

    public String getVisibleString() throws ApduException {
        assertType(DlmsDataType.VISIBLE_STRING);
        return (String) value;
    }

    public String getString() throws ApduException {
        if (type == DlmsDataType.VISIBLE_STRING) return (String) value;
        if (type == DlmsDataType.UTF8_STRING)    return (String) value;
        throw new ApduException("Cannot get string from type: " + type);
    }

    public byte[] getDateTimeBytes() throws ApduException {
        assertType(DlmsDataType.DATE_TIME);
        return Arrays.copyOf((byte[]) value, 12);
    }

    public byte[] getDateBytes() throws ApduException {
        assertType(DlmsDataType.DATE);
        return Arrays.copyOf((byte[]) value, 5);
    }

    public byte[] getTimeBytes() throws ApduException {
        assertType(DlmsDataType.TIME);
        return Arrays.copyOf((byte[]) value, 4);
    }

    @SuppressWarnings("unchecked")
    public List<DataObject> getList() throws ApduException {
        if (!isComposite()) {
            throw new ApduException("Cannot get list from type: " + type);
        }
        return (List<DataObject>) value;
    }

    // -------------------------------------------------------------------------
    // Encoding
    // -------------------------------------------------------------------------

    /**
     * Encodes this DataObject into DLMS wire format: type tag + length + value.
     *
     * @return encoded bytes
     * @throws ApduException if the type cannot be encoded
     */
    public byte[] encode() throws ApduException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(type.getTag());
        encodeValue(out);
        return out.toByteArray();
    }

    private void encodeValue(ByteArrayOutputStream out) throws ApduException {
        switch (type) {
            case NULL_DATA:
            case DONT_CARE:
                break;

            case BOOLEAN:
                out.write((Boolean) value ? 0xFF : 0x00);
                break;

            case INTEGER:
                out.write((Byte) value & 0xFF);
                break;

            case UNSIGNED:
            case ENUM:
                out.write((Integer) value & 0xFF);
                break;

            case LONG:
                writeShort(out, (Short) value);
                break;

            case LONG_UNSIGNED:
                writeShort(out, ((Integer) value).shortValue());
                break;

            case DOUBLE_LONG:
                writeInt(out, (Integer) value);
                break;

            case DOUBLE_LONG_UNSIGNED:
                writeInt(out, ((Long) value).intValue());
                break;

            case LONG64:
            case LONG64_UNSIGNED:
                writeLong(out, (Long) value);
                break;

            case FLOAT32: {
                int bits = Float.floatToIntBits((Float) value);
                writeInt(out, bits);
                break;
            }

            case FLOAT64: {
                long bits = Double.doubleToLongBits((Double) value);
                writeLong(out, bits);
                break;
            }

            case OCTET_STRING: {
                byte[] bytes = (byte[]) value;
                BerEncoder.writeLength(out, bytes.length);
                out.write(bytes, 0, bytes.length);
                break;
            }

            case VISIBLE_STRING: {
                byte[] bytes = ((String) value).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                BerEncoder.writeLength(out, bytes.length);
                out.write(bytes, 0, bytes.length);
                break;
            }

            case UTF8_STRING: {
                byte[] bytes = ((String) value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                BerEncoder.writeLength(out, bytes.length);
                out.write(bytes, 0, bytes.length);
                break;
            }

            case BIT_STRING: {
                BitString bs = (BitString) value;
                int unusedBits = (bs.bitCount % 8 == 0) ? 0 : 8 - (bs.bitCount % 8);
                BerEncoder.writeLength(out, bs.bytes.length + 1);
                out.write(unusedBits);
                out.write(bs.bytes, 0, bs.bytes.length);
                break;
            }

            case DATE_TIME:
            case DATE:
            case TIME: {
                byte[] bytes = (byte[]) value;
                out.write(bytes, 0, bytes.length);
                break;
            }

            case ARRAY:
            case STRUCTURE: {
                @SuppressWarnings("unchecked")
                List<DataObject> items = (List<DataObject>) value;
                BerEncoder.writeLength(out, items.size());
                for (DataObject item : items) {
                    byte[] encoded = item.encode();
                    out.write(encoded, 0, encoded.length);
                }
                break;
            }

            default:
                throw new ApduException("Encoding not implemented for type: " + type);
        }
    }

    // -------------------------------------------------------------------------
    // Decoding
    // -------------------------------------------------------------------------

    /**
     * Decodes a {@link DataObject} from a byte array starting at the given offset.
     *
     * @param data   raw bytes
     * @param offset position of the type tag byte
     * @return decoded DataObject
     * @throws ApduException if the data is malformed or the type is unsupported
     */
    public static DataObject decode(byte[] data, int offset) throws ApduException {
        return decodeAt(data, offset).object;
    }

    /**
     * Internal decode result — object + how many bytes were consumed.
     */
    static DecodeResult decodeAt(byte[] data, int offset) throws ApduException {
        if (offset >= data.length) {
            throw new ApduException("DataObject decode: offset out of bounds: " + offset);
        }

        int          tag  = data[offset] & 0xFF;
        DlmsDataType type = DlmsDataType.fromTag(tag);
        int          pos  = offset + 1;

        switch (type) {
            case NULL_DATA:
            case DONT_CARE:
                return new DecodeResult(new DataObject(type, null), pos - offset);

            case BOOLEAN:
                assertAvailable(data, pos, 1);
                return new DecodeResult(
                        new DataObject(type, (data[pos] & 0xFF) != 0),
                        pos + 1 - offset);

            case INTEGER:
                assertAvailable(data, pos, 1);
                return new DecodeResult(
                        new DataObject(type, data[pos]),
                        pos + 1 - offset);

            case UNSIGNED:
            case ENUM:
                assertAvailable(data, pos, 1);
                return new DecodeResult(
                        new DataObject(type, data[pos] & 0xFF),
                        pos + 1 - offset);

            case LONG:
                assertAvailable(data, pos, 2);
                return new DecodeResult(
                        new DataObject(type, (short)(((data[pos] & 0xFF) << 8) | (data[pos+1] & 0xFF))),
                        pos + 2 - offset);

            case LONG_UNSIGNED:
                assertAvailable(data, pos, 2);
                return new DecodeResult(
                        new DataObject(type, ((data[pos] & 0xFF) << 8) | (data[pos+1] & 0xFF)),
                        pos + 2 - offset);

            case DOUBLE_LONG:
                assertAvailable(data, pos, 4);
                return new DecodeResult(
                        new DataObject(type, readInt(data, pos)),
                        pos + 4 - offset);

            case DOUBLE_LONG_UNSIGNED:
                assertAvailable(data, pos, 4);
                return new DecodeResult(
                        new DataObject(type, readUInt(data, pos)),
                        pos + 4 - offset);

            case LONG64:
            case LONG64_UNSIGNED:
                assertAvailable(data, pos, 8);
                return new DecodeResult(
                        new DataObject(type, readLong(data, pos)),
                        pos + 8 - offset);

            case FLOAT32:
                assertAvailable(data, pos, 4);
                return new DecodeResult(
                        new DataObject(type, Float.intBitsToFloat(readInt(data, pos))),
                        pos + 4 - offset);

            case FLOAT64:
                assertAvailable(data, pos, 8);
                return new DecodeResult(
                        new DataObject(type, Double.longBitsToDouble(readLong(data, pos))),
                        pos + 8 - offset);

            case OCTET_STRING: {
                BerDecoder.LengthResult lr = BerDecoder.readLength(data, pos);
                pos = lr.nextOffset;
                assertAvailable(data, pos, lr.length);
                byte[] bytes = Arrays.copyOfRange(data, pos, pos + lr.length);
                return new DecodeResult(
                        new DataObject(type, bytes),
                        pos + lr.length - offset);
            }

            case VISIBLE_STRING: {
                BerDecoder.LengthResult lr = BerDecoder.readLength(data, pos);
                pos = lr.nextOffset;
                assertAvailable(data, pos, lr.length);
                String s = new String(data, pos, lr.length, java.nio.charset.StandardCharsets.US_ASCII);
                return new DecodeResult(new DataObject(type, s), pos + lr.length - offset);
            }

            case UTF8_STRING: {
                BerDecoder.LengthResult lr = BerDecoder.readLength(data, pos);
                pos = lr.nextOffset;
                assertAvailable(data, pos, lr.length);
                String s = new String(data, pos, lr.length, java.nio.charset.StandardCharsets.UTF_8);
                return new DecodeResult(new DataObject(type, s), pos + lr.length - offset);
            }

            case BIT_STRING: {
                BerDecoder.LengthResult lr = BerDecoder.readLength(data, pos);
                pos = lr.nextOffset;

                // GUARD CLAUSE: Handle empty bit string
                if (lr.length == 0) {
                    return new DecodeResult(
                            new DataObject(type, new BitString(new byte[0], 0)),
                            pos - offset);
                }

                assertAvailable(data, pos, lr.length);
                int unusedBits = data[pos] & 0xFF;
                byte[] bits = Arrays.copyOfRange(data, pos + 1, pos + lr.length);
                int bitCount = bits.length * 8 - unusedBits;
                return new DecodeResult(
                        new DataObject(type, new BitString(bits, bitCount)),
                        pos + lr.length - offset);
            }

            case DATE_TIME: {
                assertAvailable(data, pos, 12);
                return new DecodeResult(
                        new DataObject(type, Arrays.copyOfRange(data, pos, pos + 12)),
                        pos + 12 - offset);
            }

            case DATE: {
                assertAvailable(data, pos, 5);
                return new DecodeResult(
                        new DataObject(type, Arrays.copyOfRange(data, pos, pos + 5)),
                        pos + 5 - offset);
            }

            case TIME: {
                assertAvailable(data, pos, 4);
                return new DecodeResult(
                        new DataObject(type, Arrays.copyOfRange(data, pos, pos + 4)),
                        pos + 4 - offset);
            }

            case ARRAY:
            case STRUCTURE: {
                BerDecoder.LengthResult lr = BerDecoder.readLength(data, pos);
                pos = lr.nextOffset;
                int count = lr.length;
                List<DataObject> items = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    DecodeResult inner = decodeAt(data, pos);
                    items.add(inner.object);
                    pos += inner.bytesConsumed;
                }
                return new DecodeResult(
                        new DataObject(type, Collections.unmodifiableList(items)),
                        pos - offset);
            }

            default:
                throw new ApduException("Decoding not implemented for type: " + type);
        }
    }

    // -------------------------------------------------------------------------
    // toString
    // -------------------------------------------------------------------------

    @Override
    public String toString() {
        if (value == null) return type + "(null)";
        if (value instanceof byte[]) {
            return type + "(0x" + bytesToHex((byte[]) value) + ")";
        }
        return type + "(" + value + ")";
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void assertType(DlmsDataType expected) throws ApduException {
        if (type != expected) {
            throw new ApduException(
                    "Type mismatch: expected " + expected + ", got " + type);
        }
    }

    private static void assertAvailable(byte[] data, int pos, int needed) throws ApduException {
        if (pos + needed > data.length) {
            throw new ApduException(String.format(
                    "Insufficient data: need %d bytes at offset %d, have %d",
                    needed, pos, data.length - pos));
        }
    }

    private static int readInt(byte[] d, int pos) {
        return ((d[pos] & 0xFF) << 24) | ((d[pos+1] & 0xFF) << 16)
                | ((d[pos+2] & 0xFF) << 8) | (d[pos+3] & 0xFF);
    }

    private static long readUInt(byte[] d, int pos) {
        return readInt(d, pos) & 0xFFFFFFFFL;
    }

    private static long readLong(byte[] d, int pos) {
        return ByteBuffer.wrap(d, pos, 8).getLong();
    }

    private static void writeShort(ByteArrayOutputStream out, short v) {
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void writeInt(ByteArrayOutputStream out, int v) {
        out.write((v >> 24) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >>  8) & 0xFF);
        out.write( v        & 0xFF);
    }

    private static void writeLong(ByteArrayOutputStream out, long v) {
        for (int i = 7; i >= 0; i--) {
            out.write((int)((v >> (i * 8)) & 0xFF));
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02X", b));
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Internal types
    // -------------------------------------------------------------------------

    static class DecodeResult {
        final DataObject object;
        final int        bytesConsumed;
        DecodeResult(DataObject object, int bytesConsumed) {
            this.object        = object;
            this.bytesConsumed = bytesConsumed;
        }
    }

    static class BitString {
        final byte[] bytes;
        final int    bitCount;
        BitString(byte[] bytes, int bitCount) {
            this.bytes    = bytes;
            this.bitCount = bitCount;
        }
    }
}