package org.pragmatica.postgres.conversion;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.ByteBuffer;

import org.pragmatica.postgres.Oid;

import static org.pragmatica.postgres.conversion.Common.returnError;


/**
 * @author Antti Laisi
 */
final class NumericConversions {
    private NumericConversions() {}

    static Long toLong(Oid oid, String value) {
        return switch (oid) {
            case UNSPECIFIED, INT2, INT4, INT8 -> Long.valueOf(value);
            case NUMERIC, FLOAT4, FLOAT8 -> new BigDecimal(value).longValue();
            default -> returnError(oid, "Long");
        };
    }

    static Integer toInteger(Oid oid, String value) {
        return switch (oid) {
            case UNSPECIFIED, INT2, INT4 -> Integer.valueOf(value);
            case INT8 -> (int) Long.parseLong(value);
            case NUMERIC, FLOAT4, FLOAT8 -> new BigDecimal(value).intValue();
            default -> returnError(oid, "Integer");
        };
    }

    static Short toShort(Oid oid, String value) {
        return switch (oid) {
            case UNSPECIFIED, INT2 -> Short.valueOf(value);
            case INT4, INT8 -> (short) Long.parseLong(value);
            case NUMERIC, FLOAT4, FLOAT8 -> new BigDecimal(value).shortValue();
            default -> returnError(oid, "Short");
        };
    }

    static Byte toByte(Oid oid, String value) {
        return switch (oid) {
            case UNSPECIFIED, INT2 -> Byte.valueOf(value);
            case INT4, INT8 -> (byte) Long.parseLong(value);
            case NUMERIC, FLOAT4, FLOAT8 -> new BigDecimal(value).byteValue();
            default -> returnError(oid, "Byte");
        };
    }

    static BigInteger toBigInteger(Oid oid, String value) {
        return switch (oid) {
            case UNSPECIFIED, INT2, INT4, INT8 -> new BigInteger(value);
            case NUMERIC -> new BigDecimal(value).toBigInteger();
            default -> returnError(oid, "BigInteger");
        };
    }

    static BigDecimal toBigDecimal(Oid oid, String value) {
        return switch (oid) {
            case UNSPECIFIED, INT2, INT4, INT8, NUMERIC, FLOAT4, FLOAT8 -> new BigDecimal(value);
            default -> returnError(oid, "BigDecimal");
        };
    }

    static Float toFloat(Oid oid, String value) {
        return switch (oid) {
            case UNSPECIFIED, INT2, INT4, INT8, NUMERIC, FLOAT4 -> Float.valueOf(value);
            default -> returnError(oid, "Float");
        };
    }

    static Double toDouble(Oid oid, String value) {
        return switch (oid) {
            case UNSPECIFIED, INT2, INT4, INT8, NUMERIC, FLOAT4, FLOAT8 -> Double.valueOf(value);
            default -> returnError(oid, "Double");
        };
    }

    private static final int NUMERIC_SIGN_NEGATIVE = 0x4000;
    private static final int NUMERIC_SIGN_NAN = 0xC000;
    private static final int NUMERIC_SIGN_POSITIVE_INFINITY = 0xD000;
    private static final int NUMERIC_SIGN_NEGATIVE_INFINITY = 0xF000;
    private static final int NUMERIC_HEADER_BYTES = 8;

    /// Decodes PostgreSQL's binary NUMERIC: `ndigits, weight, sign, dscale` as int16, then `ndigits` base-10000 digits.
    /// NaN and the infinities have no `BigDecimal`; they are reported as a failure naming the value.
    static BigDecimal binaryNumericToBigDecimal(byte[] data, int offset) {
        var buffer = ByteBuffer.wrap(data, offset, data.length - offset);
        var digitCount = buffer.getShort(offset);
        var weight = buffer.getShort(offset + 2);
        var sign = buffer.getShort(offset + 4) & 0xFFFF;
        var scale = buffer.getShort(offset + 6);

        if (sign == NUMERIC_SIGN_NAN || sign == NUMERIC_SIGN_POSITIVE_INFINITY || sign == NUMERIC_SIGN_NEGATIVE_INFINITY) {
            throw new IllegalArgumentException("NUMERIC special value has no BigDecimal representation: " + specialName(sign));
        }

        var value = BigDecimal.ZERO;

        for (int i = 0; i < digitCount; i++) {
            var digit = BigDecimal.valueOf(buffer.getShort(offset + NUMERIC_HEADER_BYTES + 2 * i));

            value = value.add(digit.scaleByPowerOfTen(4 * (weight - i)));
        }

        return (sign == NUMERIC_SIGN_NEGATIVE
                ? value.negate()
                : value).setScale(scale, RoundingMode.HALF_UP);
    }

    /// As [#binaryNumericToBigDecimal], but NaN and the infinities map to the matching `double`.
    static double binaryNumericToDouble(byte[] data, int offset) {
        return switch (ByteBuffer.wrap(data, offset, data.length - offset).getShort(offset + 4) & 0xFFFF) {
            case NUMERIC_SIGN_NAN -> Double.NaN;
            case NUMERIC_SIGN_POSITIVE_INFINITY -> Double.POSITIVE_INFINITY;
            case NUMERIC_SIGN_NEGATIVE_INFINITY -> Double.NEGATIVE_INFINITY;
            default -> binaryNumericToBigDecimal(data, offset).doubleValue();
        };
    }

    private static String specialName(int sign) {
        return switch (sign) {
            case NUMERIC_SIGN_NAN -> "NaN";
            case NUMERIC_SIGN_POSITIVE_INFINITY -> "Infinity";
            default -> "-Infinity";
        };
    }
}
