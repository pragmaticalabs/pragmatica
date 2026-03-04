package org.pragmatica.postgres.conversion;

import org.pragmatica.postgres.Oid;

import java.math.BigDecimal;
import java.math.BigInteger;

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
}
