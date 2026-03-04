package org.pragmatica.postgres;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests cross-type conversions in both text and binary mode.
 * <p>
 * Text mode: simple queries without parameters (always text format).
 * Binary mode: parameterized queries executed twice on same connection;
 * the second execution uses binary format for supported types because
 * column metadata is cached from the first execution.
 */
@Tag("Slow")
public class CrossTypeConversionTest {

    @RegisterExtension
    static final DatabaseExtension dbr = DatabaseExtension.defaultConfiguration();

    private static final String AGG_CTE = "WITH agg(id, amount) AS (VALUES (1, 10.50::NUMERIC(10,2)), (2, 20.75::NUMERIC(10,2)), (3, 30.25::NUMERIC(10,2)))";
    private static final String INT_CTE = "WITH ints(id) AS (VALUES (1), (2), (3))";

    // -----------------------------------------------------------------------
    // Category 1: Numeric widening/narrowing (text mode via simple query)
    // -----------------------------------------------------------------------
    @Nested
    class NumericCrossType {

        @Test
        void shouldConvertInt8ToInt() {
            assertEquals(42, dbr.query("SELECT 42::INT8 AS val").index(0).getInt("val").intValue());
        }

        @Test
        void shouldConvertNumericToInt() {
            assertEquals(42, dbr.query("SELECT 42::NUMERIC AS val").index(0).getInt("val").intValue());
        }

        @Test
        void shouldConvertFloat4ToInt() {
            assertEquals(42, dbr.query("SELECT 42.0::FLOAT4 AS val").index(0).getInt("val").intValue());
        }

        @Test
        void shouldConvertFloat8ToInt() {
            assertEquals(42, dbr.query("SELECT 42.0::FLOAT8 AS val").index(0).getInt("val").intValue());
        }

        @Test
        void shouldConvertInt4ToLong() {
            assertEquals(42L, dbr.query("SELECT 42::INT4 AS val").index(0).getLong("val").longValue());
        }

        @Test
        void shouldConvertInt2ToLong() {
            assertEquals(42L, dbr.query("SELECT 42::INT2 AS val").index(0).getLong("val").longValue());
        }

        @Test
        void shouldConvertNumericToLong() {
            assertEquals(42L, dbr.query("SELECT 42::NUMERIC AS val").index(0).getLong("val").longValue());
        }

        @Test
        void shouldConvertFloat8ToLong() {
            assertEquals(42L, dbr.query("SELECT 42.0::FLOAT8 AS val").index(0).getLong("val").longValue());
        }

        @Test
        void shouldConvertInt4ToShort() {
            assertEquals((short) 42, dbr.query("SELECT 42::INT4 AS val").index(0).getShort("val").shortValue());
        }

        @Test
        void shouldConvertInt8ToShort() {
            assertEquals((short) 42, dbr.query("SELECT 42::INT8 AS val").index(0).getShort("val").shortValue());
        }

        @Test
        void shouldConvertInt4ToByte() {
            assertEquals((byte) 42, dbr.query("SELECT 42::INT4 AS val").index(0).getByte("val").byteValue());
        }

        @Test
        void shouldConvertInt8ToByte() {
            assertEquals((byte) 42, dbr.query("SELECT 42::INT8 AS val").index(0).getByte("val").byteValue());
        }

        @Test
        void shouldConvertNumericToBigInteger() {
            assertEquals(BigInteger.valueOf(42), dbr.query("SELECT 42::NUMERIC AS val").index(0).getBigInteger("val"));
        }

        @Test
        void shouldConvertInt4ToBigDecimal() {
            assertEquals(new BigDecimal("42"), dbr.query("SELECT 42::INT4 AS val").index(0).getBigDecimal("val"));
        }

        @Test
        void shouldConvertInt8ToBigDecimal() {
            assertEquals(new BigDecimal("42"), dbr.query("SELECT 42::INT8 AS val").index(0).getBigDecimal("val"));
        }

        @Test
        void shouldConvertInt4ToDouble() {
            assertEquals(42.0, dbr.query("SELECT 42::INT4 AS val").index(0).getDouble("val"));
        }

        @Test
        void shouldConvertInt8ToDouble() {
            assertEquals(42.0, dbr.query("SELECT 42::INT8 AS val").index(0).getDouble("val"));
        }

        @Test
        void shouldConvertNumericToDouble() {
            assertEquals(42.5, dbr.query("SELECT 42.5::NUMERIC AS val").index(0).getDouble("val"));
        }

        @Test
        void shouldConvertFloat4ToLong() {
            assertEquals(42L, dbr.query("SELECT 42.0::FLOAT4 AS val").index(0).getLong("val").longValue());
        }

        @Test
        void shouldConvertNumericToShort() {
            assertEquals((short) 42, dbr.query("SELECT 42::NUMERIC AS val").index(0).getShort("val").shortValue());
        }

        @Test
        void shouldConvertNumericToByte() {
            assertEquals((byte) 42, dbr.query("SELECT 42::NUMERIC AS val").index(0).getByte("val").byteValue());
        }

        @Test
        void shouldConvertFloat4ToDouble() {
            assertEquals(42.0, dbr.query("SELECT 42.0::FLOAT4 AS val").index(0).getDouble("val"), 0.01);
        }

        @Test
        void shouldConvertFloat8ToBigDecimal() {
            assertEquals(new BigDecimal("42.5"), dbr.query("SELECT 42.5::FLOAT8 AS val").index(0).getBigDecimal("val"));
        }

        @Test
        void shouldConvertInt2ToBigInteger() {
            assertEquals(BigInteger.valueOf(42), dbr.query("SELECT 42::INT2 AS val").index(0).getBigInteger("val"));
        }

        @Test
        void shouldConvertInt4ToBigInteger() {
            assertEquals(BigInteger.valueOf(42), dbr.query("SELECT 42::INT4 AS val").index(0).getBigInteger("val"));
        }

        @Test
        void shouldConvertInt8ToBigInteger() {
            assertEquals(BigInteger.valueOf(42), dbr.query("SELECT 42::INT8 AS val").index(0).getBigInteger("val"));
        }
    }

    // -----------------------------------------------------------------------
    // Category 2: SQL expression return types (text mode)
    // -----------------------------------------------------------------------
    @Nested
    class SqlExpressionTypes {

        @Test
        void shouldConvertExtractHourToInt() {
            assertEquals(12, dbr.query("SELECT EXTRACT(HOUR FROM TIMESTAMP '2024-01-15 12:30:00') AS hr")
                .index(0).getInt("hr").intValue());
        }

        @Test
        void shouldConvertExtractHourToLong() {
            assertEquals(12L, dbr.query("SELECT EXTRACT(HOUR FROM TIMESTAMP '2024-01-15 12:30:00') AS hr")
                .index(0).getLong("hr").longValue());
        }

        @Test
        void shouldConvertExtractMinuteToInt() {
            assertEquals(30, dbr.query("SELECT EXTRACT(MINUTE FROM TIMESTAMP '2024-01-15 12:30:00') AS mn")
                .index(0).getInt("mn").intValue());
        }

        @Test
        void shouldConvertCountToInt() {
            assertEquals(3, dbr.query(INT_CTE + " SELECT COUNT(*) AS cnt FROM ints").index(0).getInt("cnt").intValue());
        }

        @Test
        void shouldConvertCountToLong() {
            assertEquals(3L, dbr.query(INT_CTE + " SELECT COUNT(*) AS cnt FROM ints").index(0).getLong("cnt").longValue());
        }

        @Test
        void shouldConvertSumOfInt4ToLong() {
            assertEquals(6L, dbr.query(INT_CTE + " SELECT SUM(id) AS total FROM ints").index(0).getLong("total").longValue());
        }

        @Test
        void shouldConvertSumOfInt4ToInt() {
            assertEquals(6, dbr.query(INT_CTE + " SELECT SUM(id) AS total FROM ints").index(0).getInt("total").intValue());
        }

        @Test
        void shouldConvertAvgToDouble() {
            assertEquals(2.0, dbr.query(INT_CTE + " SELECT AVG(id) AS avg_val FROM ints").index(0).getDouble("avg_val"));
        }

        @Test
        void shouldConvertSumOfNumericToBigDecimal() {
            assertEquals(new BigDecimal("61.50"),
                dbr.query(AGG_CTE + " SELECT SUM(amount) AS total FROM agg").index(0).getBigDecimal("total"));
        }

        @Test
        void shouldConvertAvgOfNumericToDouble() {
            assertEquals(20.5, dbr.query(AGG_CTE + " SELECT AVG(amount) AS avg_amt FROM agg")
                .index(0).getDouble("avg_amt"), 0.01);
        }
    }

    // -----------------------------------------------------------------------
    // Category 3: getString() on non-string types (text mode)
    // -----------------------------------------------------------------------
    @Nested
    class GetStringUniversal {

        @Test
        void shouldConvertInt4ToString() {
            assertEquals("42", dbr.query("SELECT 42::INT4 AS val").index(0).getString("val"));
        }

        @Test
        void shouldConvertInt8ToString() {
            assertEquals("42", dbr.query("SELECT 42::INT8 AS val").index(0).getString("val"));
        }

        @Test
        void shouldConvertInt2ToString() {
            assertEquals("42", dbr.query("SELECT 42::INT2 AS val").index(0).getString("val"));
        }

        @Test
        void shouldConvertNumericToString() {
            assertEquals("42.5", dbr.query("SELECT 42.5::NUMERIC AS val").index(0).getString("val"));
        }

        @Test
        void shouldConvertFloat4ToString() {
            assertNotNull(dbr.query("SELECT 42.5::FLOAT4 AS val").index(0).getString("val"));
        }

        @Test
        void shouldConvertFloat8ToString() {
            assertEquals("42.5", dbr.query("SELECT 42.5::FLOAT8 AS val").index(0).getString("val"));
        }

        @Test
        void shouldConvertBoolTrueToString() {
            // In text mode, PostgreSQL returns "t" for true
            assertEquals("t", dbr.query("SELECT true::BOOL AS val").index(0).getString("val"));
        }

        @Test
        void shouldConvertBoolFalseToString() {
            // In text mode, PostgreSQL returns "f" for false
            assertEquals("f", dbr.query("SELECT false::BOOL AS val").index(0).getString("val"));
        }

        @Test
        void shouldConvertDateToString() {
            assertNotNull(dbr.query("SELECT CURRENT_DATE AS val").index(0).getString("val"));
        }

        @Test
        void shouldConvertTimestampToString() {
            assertNotNull(dbr.query("SELECT '2024-01-15 12:30:00'::TIMESTAMP AS val").index(0).getString("val"));
        }

        @Test
        void shouldConvertTimestamptzToString() {
            assertNotNull(dbr.query("SELECT '2024-01-15 12:30:00+00'::TIMESTAMPTZ AS val").index(0).getString("val"));
        }

        @Test
        void shouldConvertTimeToString() {
            assertNotNull(dbr.query("SELECT '12:30:00'::TIME AS val").index(0).getString("val"));
        }
    }

    // -----------------------------------------------------------------------
    // Category 4: Binary mode cross-type (parameterized queries, second execution)
    // -----------------------------------------------------------------------
    @Nested
    class BinaryModeCrossType {

        @Test
        void shouldConvertInt8ToIntInBinaryMode() {
            var sql = "SELECT $1::INT8 AS val";
            var params = List.of(42L);
            // First call: text mode (discovers columns)
            dbr.query(sql, params);
            // Second call: binary mode (columns cached, INT8 supports binary)
            assertEquals(42, dbr.query(sql, params).index(0).getInt("val").intValue());
        }

        @Test
        void shouldConvertInt4ToLongInBinaryMode() {
            var sql = "SELECT $1::INT4 AS val";
            var params = List.of(42);
            dbr.query(sql, params);
            assertEquals(42L, dbr.query(sql, params).index(0).getLong("val").longValue());
        }

        @Test
        void shouldConvertInt2ToLongInBinaryMode() {
            var sql = "SELECT $1::INT2 AS val";
            var params = List.of((short) 42);
            dbr.query(sql, params);
            assertEquals(42L, dbr.query(sql, params).index(0).getLong("val").longValue());
        }

        @Test
        void shouldConvertInt2ToIntInBinaryMode() {
            var sql = "SELECT $1::INT2 AS val";
            var params = List.of((short) 42);
            dbr.query(sql, params);
            assertEquals(42, dbr.query(sql, params).index(0).getInt("val").intValue());
        }

        @Test
        void shouldConvertInt4ToShortInBinaryMode() {
            var sql = "SELECT $1::INT4 AS val";
            var params = List.of(42);
            dbr.query(sql, params);
            assertEquals((short) 42, dbr.query(sql, params).index(0).getShort("val").shortValue());
        }

        @Test
        void shouldConvertInt8ToShortInBinaryMode() {
            var sql = "SELECT $1::INT8 AS val";
            var params = List.of(42L);
            dbr.query(sql, params);
            assertEquals((short) 42, dbr.query(sql, params).index(0).getShort("val").shortValue());
        }

        @Test
        void shouldConvertFloat4ToDoubleInBinaryMode() {
            var sql = "SELECT $1::FLOAT4 AS val";
            var params = List.of(42.5f);
            dbr.query(sql, params);
            assertEquals(42.5, dbr.query(sql, params).index(0).getDouble("val"), 0.01);
        }

        @Test
        void shouldConvertInt4ToBigDecimalInBinaryMode() {
            var sql = "SELECT $1::INT4 AS val";
            var params = List.of(42);
            dbr.query(sql, params);
            var result = dbr.query(sql, params).index(0).getBigDecimal("val");
            assertEquals(42, result.intValue());
        }

        @Test
        void shouldConvertInt8ToBigDecimalInBinaryMode() {
            var sql = "SELECT $1::INT8 AS val";
            var params = List.of(42L);
            dbr.query(sql, params);
            var result = dbr.query(sql, params).index(0).getBigDecimal("val");
            assertEquals(42, result.intValue());
        }

        @Test
        void shouldConvertInt4ToBigIntegerInBinaryMode() {
            var sql = "SELECT $1::INT4 AS val";
            var params = List.of(42);
            dbr.query(sql, params);
            assertEquals(BigInteger.valueOf(42), dbr.query(sql, params).index(0).getBigInteger("val"));
        }

        @Test
        void shouldConvertInt8ToBigIntegerInBinaryMode() {
            var sql = "SELECT $1::INT8 AS val";
            var params = List.of(42L);
            dbr.query(sql, params);
            assertEquals(BigInteger.valueOf(42), dbr.query(sql, params).index(0).getBigInteger("val"));
        }

        @Test
        void shouldConvertInt2ToBigIntegerInBinaryMode() {
            var sql = "SELECT $1::INT2 AS val";
            var params = List.of((short) 42);
            dbr.query(sql, params);
            assertEquals(BigInteger.valueOf(42), dbr.query(sql, params).index(0).getBigInteger("val"));
        }

        @Test
        void shouldConvertInt4ToDoubleInBinaryMode() {
            var sql = "SELECT $1::INT4 AS val";
            var params = List.of(42);
            dbr.query(sql, params);
            assertEquals(42.0, dbr.query(sql, params).index(0).getDouble("val"), 0.01);
        }

        @Test
        void shouldConvertInt8ToDoubleInBinaryMode() {
            var sql = "SELECT $1::INT8 AS val";
            var params = List.of(42L);
            dbr.query(sql, params);
            assertEquals(42.0, dbr.query(sql, params).index(0).getDouble("val"), 0.01);
        }

        @Test
        void shouldConvertInt4ToByteInBinaryMode() {
            var sql = "SELECT $1::INT4 AS val";
            var params = List.of(42);
            dbr.query(sql, params);
            assertEquals((byte) 42, dbr.query(sql, params).index(0).getByte("val").byteValue());
        }

        @Test
        void shouldConvertFloat8ToLongInBinaryMode() {
            var sql = "SELECT $1::FLOAT8 AS val";
            var params = List.of(42.0);
            dbr.query(sql, params);
            assertEquals(42L, dbr.query(sql, params).index(0).getLong("val").longValue());
        }

        @Test
        void shouldConvertFloat8ToIntInBinaryMode() {
            var sql = "SELECT $1::FLOAT8 AS val";
            var params = List.of(42.0);
            dbr.query(sql, params);
            assertEquals(42, dbr.query(sql, params).index(0).getInt("val").intValue());
        }
    }

    // -----------------------------------------------------------------------
    // Category 5: Temporal cross-type (text mode)
    // -----------------------------------------------------------------------
    @Nested
    class TemporalCrossType {

        @Test
        void shouldConvertTimestampToLocalDate() {
            assertEquals(LocalDate.of(2024, 1, 15),
                dbr.query("SELECT '2024-01-15 12:30:00'::TIMESTAMP AS d").index(0).getLocalDate("d"));
        }

        @Test
        void shouldConvertTimestamptzToLocalDate() {
            assertEquals(LocalDate.of(2024, 1, 15),
                dbr.query("SELECT '2024-01-15 12:30:00+00'::TIMESTAMPTZ AS d").index(0).getLocalDate("d"));
        }

        @Test
        void shouldConvertTimestampToLocalTime() {
            assertEquals(LocalTime.of(12, 30, 0),
                dbr.query("SELECT '2024-01-15 12:30:00'::TIMESTAMP AS t").index(0).getLocalTime("t"));
        }

        @Test
        void shouldConvertTimestamptzToLocalTime() {
            assertEquals(LocalTime.of(12, 30, 0),
                dbr.query("SELECT '2024-01-15 12:30:00+00'::TIMESTAMPTZ AS t").index(0).getLocalTime("t"));
        }

        @Test
        void shouldConvertDateToLocalDateTime() {
            assertEquals(LocalDateTime.of(2024, 1, 15, 0, 0, 0),
                dbr.query("SELECT '2024-01-15'::DATE AS val").index(0).getLocalDateTime("val"));
        }

        @Test
        void shouldConvertDateToInstant() {
            assertEquals(LocalDate.of(2024, 1, 15).atStartOfDay().toInstant(ZoneOffset.UTC),
                dbr.query("SELECT '2024-01-15'::DATE AS i").index(0).getInstant("i"));
        }

        @Test
        void shouldConvertTimestamptzToLocalDateTime() {
            assertEquals(LocalDateTime.of(2024, 1, 15, 12, 30, 0),
                dbr.query("SELECT '2024-01-15 12:30:00+00'::TIMESTAMPTZ AS val").index(0).getLocalDateTime("val"));
        }

        @Test
        void shouldConvertTimestampToInstant() {
            assertEquals(LocalDateTime.of(2024, 1, 15, 12, 30, 0).toInstant(ZoneOffset.UTC),
                dbr.query("SELECT '2024-01-15 12:30:00'::TIMESTAMP AS i").index(0).getInstant("i"));
        }
    }

    // -----------------------------------------------------------------------
    // Category 6: Binary mode temporal cross-type (parameterized queries)
    // -----------------------------------------------------------------------
    @Nested
    class BinaryModeTemporalCrossType {

        @Test
        void shouldConvertTimestampToLocalDateInBinaryMode() {
            var sql = "SELECT $1::TIMESTAMP AS val";
            var params = List.of(LocalDateTime.of(2024, 1, 15, 12, 30, 0));
            dbr.query(sql, params);
            assertEquals(LocalDate.of(2024, 1, 15), dbr.query(sql, params).index(0).getLocalDate("val"));
        }

        @Test
        void shouldConvertTimestampToLocalTimeInBinaryMode() {
            var sql = "SELECT $1::TIMESTAMP AS val";
            var params = List.of(LocalDateTime.of(2024, 1, 15, 12, 30, 0));
            dbr.query(sql, params);
            assertEquals(LocalTime.of(12, 30, 0), dbr.query(sql, params).index(0).getLocalTime("val"));
        }

        @Test
        void shouldConvertDateToLocalDateInBinaryMode() {
            var sql = "SELECT $1::DATE AS val";
            var params = List.of(LocalDate.of(2024, 1, 15));
            dbr.query(sql, params);
            assertEquals(LocalDate.of(2024, 1, 15), dbr.query(sql, params).index(0).getLocalDate("val"));
        }

        @Test
        void shouldConvertDateToInstantInBinaryMode() {
            var sql = "SELECT $1::DATE AS d";
            var params = List.of(LocalDate.of(2024, 1, 15));
            dbr.query(sql, params);
            assertEquals(LocalDate.of(2024, 1, 15).atStartOfDay().toInstant(ZoneOffset.UTC),
                dbr.query(sql, params).index(0).getInstant("d"));
        }

        @Test
        void shouldConvertTimestamptzToInstantInBinaryMode() {
            var sql = "SELECT $1::TIMESTAMPTZ AS val";
            var now = Instant.parse("2024-01-15T12:30:00Z");
            var params = List.of(now);
            dbr.query(sql, params);
            assertEquals(now, dbr.query(sql, params).index(0).getInstant("val"));
        }
    }
}
