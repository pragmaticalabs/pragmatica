/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package org.pragmatica.postgres;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Rows read from a LIVE PostgreSQL in BINARY result format, through `PgRow.get(col, Class)` and the untyped
/// `PgRow.get(col)`. The converter unit tests hand-build wire bytes, which cannot show that the row accessors pass the
/// column's real format and that PostgreSQL really sends these types in binary. A parameterised statement run TWICE on
/// the same connection requests binary result columns on the second execution (the first one discovers the columns),
/// so every case here reads the SECOND result and first asserts the column really arrived in binary.
@Tag("Slow")
class BinaryRowReadLivePgTest {
    @RegisterExtension
    static final DatabaseExtension dbr = DatabaseExtension.defaultConfiguration();

    /// Runs the statement twice and returns the row of the second execution, whose column must be binary.
    private static PgRow secondExecution(String sql, Object param) {
        dbr.query(sql, List.of(param));
        var result = dbr.query(sql, List.of(param));

        assertThat(result.orderedColumns().getFirst().isBinary()).as("control: the second execution's column arrives in binary: " + sql).isTrue();

        return result.index(0);
    }

    @Nested
    class Numbers {
        @Test void int8() {
            var row = secondExecution("SELECT $1::INT8 AS val", 9_000_000_000L);

            assertThat(row.get("val", Long.class)).isEqualTo(9_000_000_000L);
            assertThat(row.get("val")).isEqualTo(9_000_000_000L);
        }

        @Test void float4() {
            var row = secondExecution("SELECT $1::FLOAT4 AS val", 1.25f);

            assertThat(row.get("val", Float.class)).isEqualTo(1.25f);
            assertThat(row.get("val", Double.class)).isEqualTo(1.25d);
            assertThat(row.get("val")).isEqualTo(new BigDecimal("1.25"));
        }

        @Test void float8() {
            var row = secondExecution("SELECT $1::FLOAT8 AS val", 2.5d);

            assertThat(row.get("val", Double.class)).isEqualTo(2.5d);
            assertThat(row.get("val")).isEqualTo(new BigDecimal("2.5"));
        }
    }

    @Nested
    class Scalars {
        @Test void bool() {
            var row = secondExecution("SELECT $1::BOOL AS val", true);

            assertThat(row.get("val", Boolean.class)).isTrue();
            assertThat(row.get("val")).isEqualTo(true);
        }

        @Test void bytea() {
            var row = secondExecution("SELECT $1::BYTEA AS val", new byte[]{0, -1, 7});

            assertThat(row.get("val", byte[].class)).containsExactly(0, -1, 7);
            assertThat((byte[]) row.get("val")).containsExactly(0, -1, 7);
        }

        @Test void uuid() {
            var id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
            var row = secondExecution("SELECT $1::UUID AS val", id);

            assertThat(row.get("val", UUID.class)).isEqualTo(id);
            assertThat(row.get("val")).isEqualTo(id);
        }
    }

    @Nested
    class Temporal {
        @Test void date() {
            var row = secondExecution("SELECT $1::DATE AS val", LocalDate.of(2026, 1, 2));

            assertThat(row.get("val", LocalDate.class)).isEqualTo(LocalDate.of(2026, 1, 2));
            assertThat(row.get("val")).isEqualTo(LocalDate.of(2026, 1, 2));
        }

        @Test void time() {
            var row = secondExecution("SELECT $1::TIME AS val", LocalTime.of(3, 4, 5, 123_456_000));

            assertThat(row.get("val", LocalTime.class)).isEqualTo(LocalTime.of(3, 4, 5, 123_456_000));
            assertThat(row.get("val")).isEqualTo(LocalTime.of(3, 4, 5, 123_456_000));
        }

        @Test void timestamp() {
            var wall = LocalDateTime.of(2026, 1, 2, 3, 4, 5, 123_456_000);
            var row = secondExecution("SELECT $1::TIMESTAMP AS val", wall);

            assertThat(row.get("val", LocalDateTime.class)).isEqualTo(wall);
            assertThat(row.get("val", Instant.class)).isEqualTo(wall.toInstant(ZoneOffset.UTC));
            assertThat(row.get("val")).isEqualTo(wall.toInstant(ZoneOffset.UTC));
        }

        @Test void timestamptz() {
            var instant = Instant.parse("2026-01-02T03:04:05.123456Z");
            var row = secondExecution("SELECT $1::TIMESTAMPTZ AS val", instant.atOffset(ZoneOffset.UTC));

            assertThat(row.get("val", Instant.class)).isEqualTo(instant);
            assertThat(row.get("val", OffsetDateTime.class)).isEqualTo(instant.atOffset(ZoneOffset.UTC));
            assertThat(row.get("val", ZonedDateTime.class)).isEqualTo(instant.atZone(ZoneOffset.UTC));
            assertThat(row.get("val")).isEqualTo(instant);
        }
    }

    @Nested
    class Arrays {
        @Test void typedIntegerArray() {
            var row = secondExecution("SELECT $1::INT4[] AS val", new int[]{1, 2, 3});

            assertThat(row.get("val", Integer[].class)).containsExactly(1, 2, 3);
            assertThat(row.getArray("val", Integer[].class)).containsExactly(1, 2, 3);
        }
    }

    /// PostgreSQL's `infinity` and `-infinity` have no `LocalDate` / `Instant`; reading one is a loud failure, never a
    /// silently wrong date. The statement picks the value server-side so the binary result carries the sentinel.
    @Nested
    class Infinity {
        private PgRow secondExecutionOf(String valueExpression, String type) {
            return secondExecution("SELECT CASE WHEN $1::INT4 = 1 THEN '" + valueExpression + "'::" + type + " ELSE NULL END AS val", 1);
        }

        @Test void dateInfinity_failsLoudly() {
            var row = secondExecutionOf("infinity", "DATE");

            assertThatThrownBy(() -> row.get("val", LocalDate.class)).isInstanceOf(RuntimeException.class).hasMessageContaining("infinity");
            assertThatThrownBy(() -> row.get("val")).isInstanceOf(RuntimeException.class).hasMessageContaining("infinity");
        }

        @Test void negativeDateInfinity_failsLoudly() {
            var row = secondExecutionOf("-infinity", "DATE");

            assertThatThrownBy(() -> row.get("val", LocalDate.class)).isInstanceOf(RuntimeException.class).hasMessageContaining("infinity");
        }

        @Test void timestampInfinity_failsLoudly() {
            var row = secondExecutionOf("infinity", "TIMESTAMP");

            assertThatThrownBy(() -> row.get("val", LocalDateTime.class)).isInstanceOf(RuntimeException.class).hasMessageContaining("infinity");
            assertThatThrownBy(() -> row.get("val", Instant.class)).isInstanceOf(RuntimeException.class).hasMessageContaining("infinity");
            assertThatThrownBy(() -> row.get("val")).isInstanceOf(RuntimeException.class).hasMessageContaining("infinity");
        }

        @Test void timestamptzNegativeInfinity_failsLoudly() {
            var row = secondExecutionOf("-infinity", "TIMESTAMPTZ");

            assertThatThrownBy(() -> row.get("val", Instant.class)).isInstanceOf(RuntimeException.class).hasMessageContaining("infinity");
            assertThatThrownBy(() -> row.get("val", OffsetDateTime.class)).isInstanceOf(RuntimeException.class).hasMessageContaining("infinity");
        }
    }

    /// A `String` target on a binary column decodes by the column's type first, then formats; it used to return the raw
    /// binary bytes decoded as text (mojibake).
    @Nested
    class StringTarget {
        @Test void int8() {
            assertThat(secondExecution("SELECT $1::INT8 AS val", 9_000_000_000L).get("val", String.class)).isEqualTo("9000000000");
        }

        @Test void bool() {
            assertThat(secondExecution("SELECT $1::BOOL AS val", true).get("val", String.class)).isEqualTo("true");
        }

        @Test void uuid() {
            var id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

            assertThat(secondExecution("SELECT $1::UUID AS val", id).get("val", String.class)).isEqualTo(id.toString());
        }

        @Test void date() {
            assertThat(secondExecution("SELECT $1::DATE AS val", LocalDate.of(2026, 1, 2)).get("val", String.class)).isEqualTo("2026-01-02");
        }

        @Test void timestamptz() {
            var instant = Instant.parse("2026-01-02T03:04:05.123456Z");

            assertThat(secondExecution("SELECT $1::TIMESTAMPTZ AS val", instant.atOffset(ZoneOffset.UTC)).get("val", String.class))
                .isEqualTo("2026-01-02T03:04:05.123456Z");
        }

        @Test void float8() {
            assertThat(secondExecution("SELECT $1::FLOAT8 AS val", 2.5d).get("val", String.class)).isEqualTo("2.5");
        }

        @Test void bytea() {
            assertThat(secondExecution("SELECT $1::BYTEA AS val", new byte[]{0, -1, 7}).get("val", String.class)).isEqualTo("\\x00ff07");
        }
    }
}
