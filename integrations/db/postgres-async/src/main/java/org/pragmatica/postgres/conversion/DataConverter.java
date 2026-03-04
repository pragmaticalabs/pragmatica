package org.pragmatica.postgres.conversion;

import org.pragmatica.postgres.Oid;
import org.pragmatica.postgres.PgColumn;
import org.pragmatica.postgres.net.Converter;
import org.pragmatica.lang.Functions.Fn2;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.time.*;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.pragmatica.postgres.conversion.TemporalConversions.*;
import static org.pragmatica.postgres.util.HexConverter.parseHexBinary;

/**
 * @author Antti Laisi
 */
public class DataConverter {
    private final Map<Class<?>, Converter<?>> typeToConverter;
    private final Charset encoding;

    public DataConverter(List<Converter<?>> converters, Charset encoding) {
        this.typeToConverter = converters.stream()
                                         .collect(Collectors.toMap(Converter::type, Function.identity()));
        this.encoding = encoding;
    }

    public String toString(Oid oid, byte[] value) {
        return value == null ? null : StringConversions.asString(oid, new String(value, encoding));
    }

    public Character toChar(Oid oid, byte[] value) {
        return value == null ? null : StringConversions.toChar(oid, new String(value, encoding));
    }

    public Long toLong(Oid oid, byte[] value) {
        return value == null ? null : NumericConversions.toLong(oid, new String(value, encoding));
    }

    public Integer toInteger(Oid oid, byte[] value) {
        return value == null ? null : NumericConversions.toInteger(oid, new String(value, encoding));
    }

    public Short toShort(Oid oid, byte[] value) {
        return value == null ? null : NumericConversions.toShort(oid, new String(value, encoding));
    }

    public Byte toByte(Oid oid, byte[] value) {
        return value == null ? null : NumericConversions.toByte(oid, new String(value, encoding));
    }

    public BigInteger toBigInteger(Oid oid, byte[] value) {
        return value == null ? null : NumericConversions.toBigInteger(oid, new String(value, encoding));
    }

    public BigDecimal toBigDecimal(Oid oid, byte[] value) {
        return value == null ? null : NumericConversions.toBigDecimal(oid, new String(value, encoding));
    }

    public Double toDouble(Oid oid, byte[] value) {
        return value == null ? null : NumericConversions.toDouble(oid, new String(value, encoding));
    }

    public LocalDate toLocalDate(Oid oid, byte[] value) {
        return value == null ? null : TemporalConversions.toLocalDate(oid, new String(value, encoding));
    }

    public LocalDateTime toLocalDateTime(Oid oid, byte[] value) {
        return value == null ? null : TemporalConversions.toLocalDateTime(oid, new String(value, encoding));
    }

    public LocalTime toLocalTime(Oid oid, byte[] value) {
        return value == null ? null : TemporalConversions.toLocalTime(oid, new String(value, encoding));
    }

    public Instant toInstant(Oid oid, byte[] value) {
        return value == null ? null : TemporalConversions.toInstant(oid, new String(value, encoding));
    }

    public byte[] toBytes(Oid oid, byte[] value) {
        return value == null ? null : BlobConversions.toBytes(oid, new String(value, 2, value.length - 2, encoding));
    }

    public Boolean toBoolean(Oid oid, byte[] value) {
        return value == null ? null : BooleanConversions.toBoolean(oid, new String(value, encoding));
    }

    // Binary-aware overloads

    public String toString(Oid oid, byte[] value, boolean binary) {
        if (value == null) return null;
        if (binary) return new String(value, encoding);
        return StringConversions.asString(oid, new String(value, encoding));
    }

    public Character toChar(Oid oid, byte[] value, boolean binary) {
        if (value == null) return null;
        if (binary) return (char) value[0];
        return StringConversions.toChar(oid, new String(value, encoding));
    }

    public Long toLong(Oid oid, byte[] value, boolean binary) {
        if (value == null) return null;
        if (binary) return ByteBuffer.wrap(value).getLong();
        return NumericConversions.toLong(oid, new String(value, encoding));
    }

    public Integer toInteger(Oid oid, byte[] value, boolean binary) {
        if (value == null) return null;
        if (binary) return ByteBuffer.wrap(value).getInt();
        return NumericConversions.toInteger(oid, new String(value, encoding));
    }

    public Short toShort(Oid oid, byte[] value, boolean binary) {
        if (value == null) return null;
        if (binary) return ByteBuffer.wrap(value).getShort();
        return NumericConversions.toShort(oid, new String(value, encoding));
    }

    public Byte toByte(Oid oid, byte[] value, boolean binary) {
        if (value == null) return null;
        if (binary) return value[0];
        return NumericConversions.toByte(oid, new String(value, encoding));
    }

    public BigInteger toBigInteger(Oid oid, byte[] value, boolean binary) {
        if (value == null) return null;
        if (binary) return BigInteger.valueOf(ByteBuffer.wrap(value).getLong());
        return NumericConversions.toBigInteger(oid, new String(value, encoding));
    }

    public BigDecimal toBigDecimal(Oid oid, byte[] value, boolean binary) {
        if (value == null) return null;
        if (binary) {
            var buf = ByteBuffer.wrap(value);
            return value.length == 4 ? BigDecimal.valueOf(buf.getFloat())
                 : value.length == 8 ? BigDecimal.valueOf(buf.getDouble())
                 : new BigDecimal(new String(value, encoding));
        }
        return NumericConversions.toBigDecimal(oid, new String(value, encoding));
    }

    public Double toDouble(Oid oid, byte[] value, boolean binary) {
        if (value == null) return null;
        if (binary) {
            var buf = ByteBuffer.wrap(value);
            return value.length == 4 ? (double) buf.getFloat() : buf.getDouble();
        }
        return NumericConversions.toDouble(oid, new String(value, encoding));
    }

    public LocalDate toLocalDate(Oid oid, byte[] value, boolean binary) {
        if (value == null) return null;
        if (binary) {
            int days = ByteBuffer.wrap(value).getInt();
            return LocalDate.of(2000, 1, 1).plusDays(days);
        }
        return TemporalConversions.toLocalDate(oid, new String(value, encoding));
    }

    public LocalDateTime toLocalDateTime(Oid oid, byte[] value, boolean binary) {
        if (value == null) return null;
        if (binary) {
            long pgMicros = ByteBuffer.wrap(value).getLong();
            long epochMicros = pgMicros + BinaryCodec.PG_EPOCH_MICROS_OFFSET;
            long epochSecs = Math.floorDiv(epochMicros, 1_000_000);
            int nanoAdj = (int) (Math.floorMod(epochMicros, 1_000_000) * 1000);
            return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSecs, nanoAdj), ZoneOffset.UTC);
        }
        return TemporalConversions.toLocalDateTime(oid, new String(value, encoding));
    }

    public LocalTime toLocalTime(Oid oid, byte[] value, boolean binary) {
        if (value == null) return null;
        if (binary) {
            long micros = ByteBuffer.wrap(value).getLong();
            return LocalTime.ofNanoOfDay(micros * 1000);
        }
        return TemporalConversions.toLocalTime(oid, new String(value, encoding));
    }

    public Instant toInstant(Oid oid, byte[] value, boolean binary) {
        if (value == null) return null;
        if (binary) {
            long pgMicros = ByteBuffer.wrap(value).getLong();
            long epochMicros = pgMicros + BinaryCodec.PG_EPOCH_MICROS_OFFSET;
            long epochSecs = Math.floorDiv(epochMicros, 1_000_000);
            int nanoAdj = (int) (Math.floorMod(epochMicros, 1_000_000) * 1000);
            return Instant.ofEpochSecond(epochSecs, nanoAdj);
        }
        return TemporalConversions.toInstant(oid, new String(value, encoding));
    }

    public byte[] toBytes(Oid oid, byte[] value, boolean binary) {
        if (value == null) return null;
        if (binary) return value;
        return BlobConversions.toBytes(oid, new String(value, 2, value.length - 2, encoding));
    }

    public Boolean toBoolean(Oid oid, byte[] value, boolean binary) {
        if (value == null) return null;
        if (binary) return value[0] != 0;
        return BooleanConversions.toBoolean(oid, new String(value, encoding));
    }

    // Binary-aware overloads with offset/length (zero-copy from DataRow buffer)

    public String toString(Oid oid, byte[] data, int offset, int length, boolean binary) {
        if (length == -1) return null;
        if (binary) return new String(data, offset, length, encoding);
        return StringConversions.asString(oid, new String(data, offset, length, encoding));
    }

    public Character toChar(Oid oid, byte[] data, int offset, int length, boolean binary) {
        if (length == -1) return null;
        if (binary) return (char) data[offset];
        return StringConversions.toChar(oid, new String(data, offset, length, encoding));
    }

    public Long toLong(Oid oid, byte[] data, int offset, int length, boolean binary) {
        if (length == -1) return null;
        if (binary) return ByteBuffer.wrap(data, offset, 8).getLong();
        return NumericConversions.toLong(oid, new String(data, offset, length, encoding));
    }

    public Integer toInteger(Oid oid, byte[] data, int offset, int length, boolean binary) {
        if (length == -1) return null;
        if (binary) return ByteBuffer.wrap(data, offset, 4).getInt();
        return NumericConversions.toInteger(oid, new String(data, offset, length, encoding));
    }

    public Short toShort(Oid oid, byte[] data, int offset, int length, boolean binary) {
        if (length == -1) return null;
        if (binary) return ByteBuffer.wrap(data, offset, 2).getShort();
        return NumericConversions.toShort(oid, new String(data, offset, length, encoding));
    }

    public Byte toByte(Oid oid, byte[] data, int offset, int length, boolean binary) {
        if (length == -1) return null;
        if (binary) return data[offset];
        return NumericConversions.toByte(oid, new String(data, offset, length, encoding));
    }

    public BigInteger toBigInteger(Oid oid, byte[] data, int offset, int length, boolean binary) {
        if (length == -1) return null;
        if (binary) return BigInteger.valueOf(ByteBuffer.wrap(data, offset, 8).getLong());
        return NumericConversions.toBigInteger(oid, new String(data, offset, length, encoding));
    }

    public BigDecimal toBigDecimal(Oid oid, byte[] data, int offset, int length, boolean binary) {
        if (length == -1) return null;
        if (binary) {
            var buf = ByteBuffer.wrap(data, offset, length);
            return length == 4 ? BigDecimal.valueOf(buf.getFloat())
                 : length == 8 ? BigDecimal.valueOf(buf.getDouble())
                 : new BigDecimal(new String(data, offset, length, encoding));
        }
        return NumericConversions.toBigDecimal(oid, new String(data, offset, length, encoding));
    }

    public Double toDouble(Oid oid, byte[] data, int offset, int length, boolean binary) {
        if (length == -1) return null;
        if (binary) {
            var buf = ByteBuffer.wrap(data, offset, length);
            return length == 4 ? (double) buf.getFloat() : buf.getDouble();
        }
        return NumericConversions.toDouble(oid, new String(data, offset, length, encoding));
    }

    public LocalDate toLocalDate(Oid oid, byte[] data, int offset, int length, boolean binary) {
        if (length == -1) return null;
        if (binary) {
            int days = ByteBuffer.wrap(data, offset, 4).getInt();
            return LocalDate.of(2000, 1, 1).plusDays(days);
        }
        return TemporalConversions.toLocalDate(oid, new String(data, offset, length, encoding));
    }

    public LocalDateTime toLocalDateTime(Oid oid, byte[] data, int offset, int length, boolean binary) {
        if (length == -1) return null;
        if (binary) {
            long pgMicros = ByteBuffer.wrap(data, offset, 8).getLong();
            long epochMicros = pgMicros + BinaryCodec.PG_EPOCH_MICROS_OFFSET;
            long epochSecs = Math.floorDiv(epochMicros, 1_000_000);
            int nanoAdj = (int) (Math.floorMod(epochMicros, 1_000_000) * 1000);
            return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSecs, nanoAdj), ZoneOffset.UTC);
        }
        return TemporalConversions.toLocalDateTime(oid, new String(data, offset, length, encoding));
    }

    public LocalTime toLocalTime(Oid oid, byte[] data, int offset, int length, boolean binary) {
        if (length == -1) return null;
        if (binary) {
            long micros = ByteBuffer.wrap(data, offset, 8).getLong();
            return LocalTime.ofNanoOfDay(micros * 1000);
        }
        return TemporalConversions.toLocalTime(oid, new String(data, offset, length, encoding));
    }

    public Instant toInstant(Oid oid, byte[] data, int offset, int length, boolean binary) {
        if (length == -1) return null;
        if (binary) {
            long pgMicros = ByteBuffer.wrap(data, offset, 8).getLong();
            long epochMicros = pgMicros + BinaryCodec.PG_EPOCH_MICROS_OFFSET;
            long epochSecs = Math.floorDiv(epochMicros, 1_000_000);
            int nanoAdj = (int) (Math.floorMod(epochMicros, 1_000_000) * 1000);
            return Instant.ofEpochSecond(epochSecs, nanoAdj);
        }
        return TemporalConversions.toInstant(oid, new String(data, offset, length, encoding));
    }

    public byte[] toBytes(Oid oid, byte[] data, int offset, int length, boolean binary) {
        if (length == -1) return null;
        if (binary) return Arrays.copyOfRange(data, offset, offset + length);
        return BlobConversions.toBytes(oid, new String(data, offset + 2, length - 2, encoding));
    }

    public Boolean toBoolean(Oid oid, byte[] data, int offset, int length, boolean binary) {
        if (length == -1) return null;
        if (binary) return data[offset] != 0;
        return BooleanConversions.toBoolean(oid, new String(data, offset, length, encoding));
    }

    public static short[] resultFormatCodes(PgColumn[] columns) {
        var codes = new short[columns.length];
        for (int i = 0; i < columns.length; i++) {
            codes[i] = (short) (columns[i].type().supportsBinary() ? 1 : 0);
        }
        return codes;
    }

    public <T> T toArray(Class<T> arrayType, Oid oid, byte[] value) {
        if (value == null) {
            return null;
        }

        return ArrayConversions.toArray(arrayType, oid, new String(value, encoding), lookupParser(oid));
    }

    public <T> T toArray(Class<T> arrayType, Oid oid, byte[] value, boolean binary) {
        if (value == null) {
            return null;
        }

        if (binary) {
            return ArrayConversions.toBinaryArray(arrayType, oid, value);
        }

        return ArrayConversions.toArray(arrayType, oid, new String(value, encoding), lookupParser(oid));
    }

    private BiFunction<Oid, String, Object> lookupParser(Oid oid) {
        return switch (oid) {
            case INT2_ARRAY -> NumericConversions::toShort;
            case INT4_ARRAY -> NumericConversions::toInteger;
            case INT8_ARRAY -> NumericConversions::toLong;
            case TEXT_ARRAY, CHAR_ARRAY, BPCHAR_ARRAY, VARCHAR_ARRAY -> StringConversions::asString;
            case NUMERIC_ARRAY, FLOAT4_ARRAY, FLOAT8_ARRAY -> NumericConversions::toBigDecimal;
            case TIMESTAMP_ARRAY, TIMESTAMPTZ_ARRAY -> TemporalConversions::toInstant;
            case TIMETZ_ARRAY, TIME_ARRAY -> TemporalConversions::toLocalTime;
            case DATE_ARRAY -> TemporalConversions::toLocalDate;
            case BOOL_ARRAY -> BooleanConversions::toBoolean;
            case BYTEA_ARRAY -> (oide, svaluee) -> {
                byte[] first = BlobConversions.toBytes(oide, svaluee.substring(2));
                return parseHexBinary(new String(first, 1, first.length - 1, encoding));
            };
            default -> throw new IllegalStateException("Unsupported array type: " + oid);
        };
    }

    @SuppressWarnings("unchecked")
    private <T> Converter<T> getConverter(Class<T> type) {
        var converter = (Converter<T>) typeToConverter.get(type);

        if (converter == null) {
            throw new IllegalArgumentException("Unknown conversion target: " + type);
        }

        return converter;
    }

    private String fromObject(Object o) {
        return switch (o) {
            case null -> null;
            case LocalDate localDate -> fromLocalDate(localDate);
            case LocalTime localTime -> fromLocalTime(localTime);
            case LocalDateTime localDateTime -> fromLocalDateTime(localDateTime);
            case ZonedDateTime zonedDateTime -> fromZoneDateTime(zonedDateTime);
            case OffsetDateTime offsetDateTime -> fromOffsetDateTime(offsetDateTime);
            case Instant instant -> fromInstant(instant);
            case byte[] bytes -> BlobConversions.fromBytes(bytes);
            case Boolean bool -> BooleanConversions.fromBoolean(bool);
            case String _, Number _, Character _, UUID _ -> o.toString();

            default -> {
                if (o.getClass().isArray()) {
                    yield ArrayConversions.fromArray(o, this::fromObject);
                } else {
                    yield fromConvertible(o);
                }
            }
        };
    }

    @SuppressWarnings("unchecked")
    private <T> String fromConvertible(T value) {
        var converter = getConverter((Class<T>) value.getClass());
        return converter.from(value);
    }

    @SuppressWarnings("unused")
    public byte[][] fromParameters(List<Object> parameters) {
        return fromParameters(parameters.toArray(new Object[]{}));
    }

    public byte[][] fromParameters(Object[] parameters) {
        byte[][] params = new byte[parameters.length][];
        int i = 0;
        for (var param : parameters) {
            var converted = fromObject(param);
            params[i++] = converted == null ? null : converted.getBytes(encoding);
        }
        return params;
    }

    public record EncodedParams(byte[][] values, short[] formatCodes) {}

    @SuppressWarnings("unchecked")
    public EncodedParams fromParametersBinary(Object[] parameters, Oid[] types) {
        byte[][] values = new byte[parameters.length][];
        short[] formatCodes = new short[parameters.length];

        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i] == null) {
                values[i] = null;
                formatCodes[i] = 0;
                continue;
            }

            var codec = (BinaryCodec<Object>) BinaryCodecs.forOid(types[i]);
            if (codec != null && isBinaryEncodable(parameters[i], types[i])) {
                var buf = ByteBuffer.allocate(codec.estimateSize(parameters[i]));
                codec.encode(parameters[i], buf);
                buf.flip();
                values[i] = new byte[buf.remaining()];
                buf.get(values[i]);
                formatCodes[i] = 1;
            } else {
                var converted = fromObject(parameters[i]);
                values[i] = converted == null ? null : converted.getBytes(encoding);
                formatCodes[i] = 0;
            }
        }
        return new EncodedParams(values, formatCodes);
    }

    private static boolean isBinaryEncodable(Object param, Oid type) {
        return switch (type) {
            case INT2 -> param instanceof Short || param instanceof Byte;
            case INT4 -> param instanceof Integer;
            case INT8 -> param instanceof Long;
            case FLOAT4 -> param instanceof Float;
            case FLOAT8 -> param instanceof Double;
            case BOOL -> param instanceof Boolean;
            case BYTEA -> param instanceof byte[];
            case UUID -> param instanceof UUID;
            case DATE -> param instanceof LocalDate;
            case TIME -> param instanceof LocalTime;
            case TIMESTAMP -> param instanceof LocalDateTime;
            case TIMESTAMPTZ -> param instanceof Instant;
            default -> false;
        };
    }

    private static final Map<Class<?>, Fn2<?, Oid, String>> KNOWN_TYPES = new HashMap<>();

    static {
        KNOWN_TYPES.put(byte.class, NumericConversions::toByte);
        KNOWN_TYPES.put(Byte.class, NumericConversions::toByte);
        KNOWN_TYPES.put(char.class, StringConversions::toChar);
        KNOWN_TYPES.put(Character.class, StringConversions::toChar);
        KNOWN_TYPES.put(short.class, NumericConversions::toShort);
        KNOWN_TYPES.put(Short.class, NumericConversions::toShort);
        KNOWN_TYPES.put(int.class, NumericConversions::toInteger);
        KNOWN_TYPES.put(Integer.class, NumericConversions::toInteger);
        KNOWN_TYPES.put(long.class, NumericConversions::toLong);
        KNOWN_TYPES.put(Long.class, NumericConversions::toLong);
        KNOWN_TYPES.put(BigInteger.class, NumericConversions::toBigInteger);
        KNOWN_TYPES.put(BigDecimal.class, NumericConversions::toBigDecimal);
        KNOWN_TYPES.put(float.class, NumericConversions::toFloat);
        KNOWN_TYPES.put(Float.class, NumericConversions::toFloat);
        KNOWN_TYPES.put(double.class, NumericConversions::toDouble);
        KNOWN_TYPES.put(Double.class, NumericConversions::toDouble);
        KNOWN_TYPES.put(String.class, StringConversions::asString);
        KNOWN_TYPES.put(LocalDate.class, TemporalConversions::toLocalDate);
        KNOWN_TYPES.put(LocalTime.class, TemporalConversions::toLocalTime);
        KNOWN_TYPES.put(LocalDateTime.class, TemporalConversions::toLocalDateTime);
        KNOWN_TYPES.put(ZonedDateTime.class, TemporalConversions::toZonedDateTime);
        KNOWN_TYPES.put(OffsetDateTime.class, TemporalConversions::toOffsetDateTime);
        KNOWN_TYPES.put(Instant.class, TemporalConversions::toInstant);
    }

    @SuppressWarnings("unchecked")
    public <T> T toObject(Oid oid, byte[] value, Class<T> type) {
        if (value == null) {
            return null;
        }

        if (type != null) {
            // Try custom converter first
            var converter = (Converter<T>) typeToConverter.get(type);

            if (converter != null) {
                return converter.to(oid, new String(value, encoding));
            }

            // Try known converter
            var knownConverter = KNOWN_TYPES.get(type);

            if (knownConverter != null) {
                return (T) knownConverter.apply(oid, new String(value, encoding));
            }

            throw new IllegalArgumentException("Unknown conversion target: " + type);
        }

        // Convert by oid
        return (T) switch (oid) {
            case null -> null;
            case TEXT, CHAR, BPCHAR, VARCHAR -> toString(oid, value);
            case INT2 -> toShort(oid, value);
            case INT4 -> toInteger(oid, value);
            case INT8 -> toLong(oid, value);
            case NUMERIC, FLOAT4, FLOAT8 -> toBigDecimal(oid, value);
            case BYTEA -> toBytes(oid, value);
            case DATE -> toLocalDate(oid, value);
            case TIMETZ, TIME -> toLocalTime(oid, value);
            case TIMESTAMP, TIMESTAMPTZ -> toInstant(oid, value);
            case UUID -> UUID.fromString(toString(oid, value));
            case BOOL -> toBoolean(oid, value);
            case INT2_ARRAY, INT4_ARRAY, INT8_ARRAY, NUMERIC_ARRAY, FLOAT4_ARRAY, FLOAT8_ARRAY,
                TEXT_ARRAY, CHAR_ARRAY, BPCHAR_ARRAY, VARCHAR_ARRAY,
                TIMESTAMP_ARRAY, TIMESTAMPTZ_ARRAY, TIMETZ_ARRAY, TIME_ARRAY, BOOL_ARRAY -> toArray(Object[].class, oid, value);
            default -> throw new IllegalArgumentException("Unknown conversion target: " + oid);
        };
    }

    public Oid[] assumeTypes(Object... params) {
        var types = new Oid[params.length];

        for (int i = 0; i < params.length; i++) {
            switch (params[i]) {
                case byte[] _ -> types[i] = Oid.BYTEA;
                case Short _, Byte _ -> types[i] = Oid.INT2;
                case Integer _ -> types[i] = Oid.INT4;
                case Long _ -> types[i] = Oid.INT8;
                case Float _ -> types[i] = Oid.FLOAT4;
                case Double _ -> types[i] = Oid.FLOAT8;
                case double[] _ -> types[i] = Oid.FLOAT8_ARRAY;
                case float[] _ -> types[i] = Oid.FLOAT4_ARRAY;
                case long[] _ -> types[i] = Oid.INT8_ARRAY;
                case int[] _ -> types[i] = Oid.INT4_ARRAY;
                case short[] _ -> types[i] = Oid.INT2_ARRAY;
                case byte[][] _ -> types[i] = Oid.BYTEA_ARRAY;
                case BigInteger _, BigDecimal _ -> types[i] = Oid.NUMERIC;
                case BigInteger[] _, BigDecimal[] _ -> types[i] = Oid.NUMERIC_ARRAY;
                case Boolean _ -> types[i] = Oid.BOOL;
                case Boolean[] _ -> types[i] = Oid.BOOL_ARRAY;
                case CharSequence _, Character _ -> types[i] = Oid.VARCHAR;
                case LocalDateTime _, Instant _ -> types[i] = Oid.TIMESTAMP;
                case ZonedDateTime _, OffsetDateTime _ -> types[i] = Oid.TIMESTAMPTZ;
                case LocalDate _ -> types[i] = Oid.DATE;
                case OffsetTime _ -> types[i] = Oid.TIME;
                case LocalTime _ -> types[i] = Oid.TIMETZ;
                case UUID _ -> types[i] = Oid.UUID;
                case null, default -> types[i] = Oid.UNSPECIFIED;
            }
        }
        return types;
    }
}
