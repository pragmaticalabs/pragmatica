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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.pragmatica.postgres.conversion.DataConverter;
import static org.assertj.core.api.Assertions.assertThat;

class GenericBinaryConversionTest {
    private final DataConverter converter = new DataConverter(List.of(), StandardCharsets.UTF_8);

    @Test void toObject_binaryTimestamps_preservesFormatForTypedAndUntypedReads() {
        for (var instant : List.of(Instant.parse("1969-12-31T23:59:59.999999Z"), Instant.parse("2026-10-08T01:02:03.123456Z"))) {
            var micros = Math.addExact(Math.multiplyExact(instant.getEpochSecond() - 946684800L, 1000000L), instant.getNano() / 1000);
            var bytes = ByteBuffer.allocate(8).putLong(micros).array();
            for (var oid : List.of(Oid.TIMESTAMP, Oid.TIMESTAMPTZ)) {
                assertThat(converter.toObject(oid, bytes, Instant.class, true)).isEqualTo(instant);
                assertThat((Object) converter.toObject(oid, bytes, null, true)).isEqualTo(instant);
                assertThat(converter.toObject(oid, bytes, LocalDateTime.class, true)).isEqualTo(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
                assertThat(converter.toObject(oid, bytes, OffsetDateTime.class, true)).isEqualTo(instant.atOffset(ZoneOffset.UTC));
            }
        }
    }

    @Test void toObject_textFloat_preservesSinglePrecisionRounding() {
        var text = "1.0000000596046448";
        assertThat(converter.toObject(Oid.FLOAT4, text.getBytes(StandardCharsets.UTF_8), Float.class, false))
            .isEqualTo(Float.parseFloat(text)).isGreaterThan(1.0f);
    }

    @Test void toObject_binaryScalars_usesBuiltInBinaryConverters() {
        assertThat(converter.toObject(Oid.INT4, ByteBuffer.allocate(4).putInt(123456).array(), Integer.class, true)).isEqualTo(123456);
        assertThat((Object) converter.toObject(Oid.INT4, ByteBuffer.allocate(4).putInt(-42).array(), null, true)).isEqualTo(-42);
        assertThat(converter.toObject(Oid.INT8, ByteBuffer.allocate(8).putLong(9000000000L).array(), Long.class, true)).isEqualTo(9000000000L);
        assertThat(converter.toObject(Oid.FLOAT4, ByteBuffer.allocate(4).putFloat(1.25f).array(), Float.class, true)).isEqualTo(1.25f);
        assertThat(converter.toObject(Oid.BOOL, new byte[]{1}, Boolean.class, true)).isTrue();
        assertThat(converter.toObject(Oid.BYTEA, new byte[]{0, -1, 7}, byte[].class, true)).containsExactly(0, -1, 7);
        assertThat(converter.toObject(Oid.DATE, ByteBuffer.allocate(4).putInt(1).array(), LocalDate.class, true)).isEqualTo(LocalDate.of(2000, 1, 2));
        var uuid = UUID.randomUUID();
        assertThat(converter.toObject(Oid.UUID, ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array(), UUID.class, true)).isEqualTo(uuid);
    }

    @Test void toObject_textAndNull_preservesExistingConversion() {
        assertThat(converter.toObject(Oid.TIMESTAMPTZ, "2026-10-08 01:02:03+02".getBytes(StandardCharsets.UTF_8), OffsetDateTime.class, false))
            .isEqualTo(OffsetDateTime.parse("2026-10-08T01:02:03+02:00"));
        assertThat(converter.toObject(Oid.INT4, "123".getBytes(StandardCharsets.UTF_8), Integer.class, false)).isEqualTo(123);
        assertThat(converter.toObject(Oid.TIMESTAMPTZ, null, Instant.class, true)).isNull();
        assertThat(converter.toObject(Oid.TEXT_ARRAY, "{one,two}".getBytes(StandardCharsets.UTF_8), String[].class, false)).containsExactly("one", "two");
    }
}
