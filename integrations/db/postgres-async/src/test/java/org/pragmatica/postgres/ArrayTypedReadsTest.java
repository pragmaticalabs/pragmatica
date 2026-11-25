package org.pragmatica.postgres;

import org.pragmatica.postgres.net.Row;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Typed reads of `float8[]`, `float4[]`, `uuid[]` and `timestamp[]` into `Double[]`, `Float[]`, `UUID[]` and
/// `LocalDateTime[]` (#2035), in both result formats, each with a NULL element, an empty array and a NULL array.
///
/// Primitive targets (`double[]`, `float[]`) stay rejected: an element may be NULL (`ArrayConversionsTest`
/// pins the rejection). `float8[]` into `Float[]` stays refused too: the scalar path refuses `float8` into `Float`,
/// and a narrowing array read would turn `1e300` into `Infinity` without an error.
@Tag("Slow")
public class ArrayTypedReadsTest {
    @RegisterExtension
    static final DatabaseExtension dbr = DatabaseExtension.defaultConfiguration();

    private static final UUID FIRST = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /// How the server is asked to send the columns: the extended protocol answers binary for the array types
    /// that have a codec, the simple `Query` message (`script`) always answers text.
    enum Format {
        BINARY,
        TEXT
    }

    @BeforeAll
    public static void create() {
        drop();
        dbr.query("CREATE TABLE TA_TEST (F8 FLOAT8[], F4 FLOAT4[], U UUID[], TS TIMESTAMP[])");
    }

    @AfterAll
    public static void drop() {
        dbr.query("DROP TABLE IF EXISTS TA_TEST");
    }

    @AfterEach
    public void empty() {
        dbr.query("DELETE FROM TA_TEST");
    }

    private static Row read(Format format) {
        return switch (format) {
            case BINARY -> dbr.query("SELECT * FROM TA_TEST").index(0);
            case TEXT -> dbr.script("SELECT * FROM TA_TEST").iterator().next().index(0);
        };
    }

    @ParameterizedTest
    @EnumSource(Format.class)
    public void float8ReadsAsDouble(Format format) {
        dbr.query("INSERT INTO TA_TEST (F8) VALUES ('{177.7, null, -2.012, 0, NaN, Infinity, -Infinity, 1e300}')");

        assertArrayEquals(new Double[]{177.7,
                                       null,
                                       -2.012,
                                       0.0,
                                       Double.NaN,
                                       Double.POSITIVE_INFINITY,
                                       Double.NEGATIVE_INFINITY,
                                       1e300},
                          read(format).getArray("f8", Double[].class));
    }

    @ParameterizedTest
    @EnumSource(Format.class)
    public void float8EmptyReadsAsEmptyDouble(Format format) {
        dbr.query("INSERT INTO TA_TEST (F8) VALUES ('{}')");

        assertEquals(0, read(format).getArray("f8", Double[].class).length);
    }

    @ParameterizedTest
    @EnumSource(Format.class)
    public void float8NullArrayReadsAsNull(Format format) {
        dbr.query("INSERT INTO TA_TEST (F8) VALUES (NULL)");

        assertEquals(null, read(format).getArray("f8", Double[].class));
    }

    @ParameterizedTest
    @EnumSource(Format.class)
    public void float4ReadsAsFloat(Format format) {
        dbr.query("INSERT INTO TA_TEST (F4) VALUES ('{177.7, null, -2.012, 0, NaN, Infinity}')");

        assertArrayEquals(new Float[]{177.7f, null, -2.012f, 0.0f, Float.NaN, Float.POSITIVE_INFINITY},
                          read(format).getArray("f4", Float[].class));
    }

    @ParameterizedTest
    @EnumSource(Format.class)
    public void float4ReadsAsDoubleKeepingTheDecimalValue(Format format) {
        dbr.query("INSERT INTO TA_TEST (F4) VALUES ('{177.7, null, -2.012}')");

        assertArrayEquals(new Double[]{177.7, null, -2.012}, read(format).getArray("f4", Double[].class));
    }

    @ParameterizedTest
    @EnumSource(Format.class)
    public void float8StaysRefusedAsFloat(Format format) {
        dbr.query("INSERT INTO TA_TEST (F8) VALUES ('{1.5, null, -2.25}')");

        assertThrows(ArrayStoreException.class, () -> read(format).getArray("f8", Float[].class));
    }

    @ParameterizedTest
    @EnumSource(Format.class)
    public void float8OutOfFloatRangeStaysRefusedAsFloat(Format format) {
        dbr.query("INSERT INTO TA_TEST (F8) VALUES ('{1e300}')");

        assertThrows(ArrayStoreException.class, () -> read(format).getArray("f8", Float[].class));
    }

    @ParameterizedTest
    @EnumSource(Format.class)
    public void float4EmptyReadsAsEmptyFloat(Format format) {
        dbr.query("INSERT INTO TA_TEST (F4) VALUES ('{}')");

        assertEquals(0, read(format).getArray("f4", Float[].class).length);
    }

    @ParameterizedTest
    @EnumSource(Format.class)
    public void float8MultiDimensionalReadsAsDouble(Format format) {
        dbr.query("INSERT INTO TA_TEST (F8) VALUES ('{{1.5, null}, {-2.25, 3}}')");

        assertArrayEquals(new Double[][]{{1.5, null}, {-2.25, 3.0}}, read(format).getArray("f8", Double[][].class));
    }

    @ParameterizedTest
    @EnumSource(Format.class)
    public void uuidReadsAsUuid(Format format) {
        dbr.query("INSERT INTO TA_TEST (U) VALUES ('{" + FIRST + ", NULL, " + SECOND + "}')");

        assertArrayEquals(new UUID[]{FIRST, null, SECOND}, read(format).getArray("u", UUID[].class));
    }

    @ParameterizedTest
    @EnumSource(Format.class)
    public void uuidEmptyReadsAsEmptyUuid(Format format) {
        dbr.query("INSERT INTO TA_TEST (U) VALUES ('{}')");

        assertEquals(0, read(format).getArray("u", UUID[].class).length);
    }

    @ParameterizedTest
    @EnumSource(Format.class)
    public void timestampReadsAsLocalDateTime(Format format) {
        dbr.query("INSERT INTO TA_TEST (TS) VALUES ('{1999-05-16 00:00:00.591, null, 1970-02-04 01:02:33.01}')");

        assertArrayEquals(new LocalDateTime[]{LocalDateTime.parse("1999-05-16T00:00:00.591"),
                                              null,
                                              LocalDateTime.parse("1970-02-04T01:02:33.01")},
                          read(format).getArray("ts", LocalDateTime[].class));
    }

    @ParameterizedTest
    @EnumSource(Format.class)
    public void timestampEmptyReadsAsEmptyLocalDateTime(Format format) {
        dbr.query("INSERT INTO TA_TEST (TS) VALUES ('{}')");

        assertEquals(0, read(format).getArray("ts", LocalDateTime[].class).length);
    }
}
