/*
 *  Copyright (c) 2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.pragmatica.postgres.r2dbc;

import java.util.UUID;

import org.pragmatica.postgres.net.Connectible;
import org.pragmatica.postgres.net.SslConfig;
import org.pragmatica.postgres.net.netty.NettyConnectibleBuilder;

import io.r2dbc.spi.Connection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.pragmatica.testing.DockerRequirement;
import org.testcontainers.containers.PostgreSQLContainer;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// #2481: a column read with the target `Object` (what jOOQ asks for a uuid or an array column) yields the driver's own
/// value for the column's type instead of being refused with "Unknown conversion target". Live PostgreSQL 16; self-skips
/// when no Docker daemon is reachable.
class PgAsyncRowObjectTargetTest {
    private static PostgreSQLContainer<?> postgres;
    private static Connectible pool;
    private static Connection connection;

    @BeforeAll
    static void startPostgres() {
        assumeTrue(DockerRequirement.dockerAvailable(), "Docker is required for the live-PostgreSQL pin");
        postgres = new PostgreSQLContainer<>("postgres:16");
        postgres.start();
        pool = new NettyConnectibleBuilder().ssl(SslConfig.disabled())
                                            .encoding("utf-8")
                                            .maxConnections(1)
                                            .port(postgres.getMappedPort(5432))
                                            .hostname(postgres.getHost())
                                            .password(postgres.getPassword())
                                            .database(postgres.getDatabaseName())
                                            .username(postgres.getUsername())
                                            .pool();
        connection = Mono.from(PgAsyncConnectionFactory.pgAsyncConnectionFactory(pool).create()).block();
    }

    @AfterAll
    static void stopPostgres() {
        if (pool != null) {
            pool.close().await();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void get_objectTarget_ofUuid_isTheUuid() {
        assertThat(objectOf("SELECT '123e4567-e89b-12d3-a456-426614174000'::uuid")).isEqualTo(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));
    }

    @Test
    void get_objectTarget_ofTextArray_isItsElements() {
        assertThat((Object[]) objectOf("SELECT ARRAY['a','b']::text[]")).containsExactly("a", "b");
    }

    @Test
    void get_objectTarget_ofIntArray_isItsElements() {
        assertThat((Object[]) objectOf("SELECT ARRAY[1,2]::int[]")).containsExactly(1, 2);
    }

    @Test
    void get_objectTarget_ofScalars_isTheNativeJavaValue() {
        assertThat(objectOf("SELECT 7::int")).isEqualTo(7);
        assertThat(objectOf("SELECT 'x'::text")).isEqualTo("x");
    }

    @Test
    void get_objectTarget_ofUuidArray_isItsUuids() {
        assertThat((Object[]) objectOf("SELECT ARRAY['123e4567-e89b-12d3-a456-426614174000','00000000-0000-0000-0000-000000000001']::uuid[]"))
            .containsExactly(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), UUID.fromString("00000000-0000-0000-0000-000000000001"));
        assertThat((Object[]) objectOf("SELECT '{}'::uuid[]")).isEmpty();
    }

    /// #2501: a column type the driver has no Java type for is read untyped as the server's own text rendering, never refused.
    /// Interval, jsonb, xml, money and bit are mapped by the driver; inet, ranges and jsonb[] are not (no `Oid` constant), and
    /// every unmapped type is read the same way.
    @Test
    void get_objectTarget_ofJsonb_isItsText() {
        assertThat(objectOf("SELECT '{\"a\":1}'::jsonb")).isEqualTo("{\"a\": 1}");
    }

    @Test
    void get_objectTarget_ofJson_isItsText() {
        assertThat(objectOf("SELECT '{\"a\":1}'::json")).isEqualTo("{\"a\":1}");
    }

    @Test
    void get_objectTarget_ofInterval_isItsText() {
        assertThat(objectOf("SELECT '1 day 2 hours'::interval")).isEqualTo("1 day 02:00:00");
    }

    @Test
    void get_objectTarget_ofInet_isItsText() {
        assertThat(objectOf("SELECT '10.0.0.1'::inet")).isEqualTo("10.0.0.1");
        assertThat(objectOf("SELECT '10.0.0.0/8'::cidr")).isEqualTo("10.0.0.0/8");
    }

    @Test
    void get_objectTarget_ofJsonbArray_isItsArrayLiteral() {
        assertThat(objectOf("SELECT ARRAY['{\"a\":1}']::jsonb[]")).isEqualTo("{\"{\\\"a\\\": 1}\"}");
    }

    @Test
    void get_objectTarget_ofRange_isItsText() {
        assertThat(objectOf("SELECT int4range(1, 5)")).isEqualTo("[1,5)");
    }

    @Test
    void get_objectTarget_ofXml_isItsText() {
        assertThat(objectOf("SELECT '<a/>'::xml")).isEqualTo("<a/>");
    }

    @Test
    void get_objectTarget_ofMoney_isItsText() {
        assertThat(objectOf("SELECT '1.5'::money")).isEqualTo("$1.50");
    }

    @Test
    void get_objectTarget_ofBitAndVarbit_isTheirBits() {
        assertThat(objectOf("SELECT B'101'")).isEqualTo("101");
        assertThat(objectOf("SELECT '1010'::varbit")).isEqualTo("1010");
    }

    @Test
    void get_objectTarget_ofOtherMappedTextTypes_isTheirText() {
        assertThat(objectOf("SELECT ARRAY['1 day']::interval[]")).isEqualTo("{\"1 day\"}");
        assertThat(objectOf("SELECT point(1, 2)")).isEqualTo("(1,2)");
        assertThat(objectOf("SELECT 'a'::name")).isEqualTo("a");
    }

    @Test
    void get_objectTarget_ofTheseTypes_whenNull_isNull() {
        assertThat(objectOf("SELECT NULL::jsonb")).isNull();
        assertThat(objectOf("SELECT NULL::interval")).isNull();
        assertThat(objectOf("SELECT NULL::inet")).isNull();
    }

    @Test
    void get_byName_objectTarget_isTheDriversNativeValue() {
        assertThat(namedObjectOf("SELECT '10.0.0.1'::inet AS addr", "addr")).isEqualTo("10.0.0.1");
        assertThat(namedObjectOf("SELECT 7::int AS n", "n")).isEqualTo(7);
        assertThat(namedObjectOf("SELECT '{\"a\":1}'::jsonb AS doc", "doc")).isEqualTo("{\"a\": 1}");
    }

    @Test
    void get_byName_asString_ofTheseTypes_isTheirText() {
        assertThat(namedOf("SELECT '1 day'::interval AS span", "span", String.class)).isEqualTo("1 day");
    }

    @Test
    void get_aTargetTheDriverCannotConvert_failsWithAMessageThatNamesTheColumnTheTypeAndTheTarget() {
        assertThatThrownBy(() -> readWith("SELECT '{}'::jsonb AS doc", row -> row.get(0, java.util.Map.class))).isInstanceOf(io.r2dbc.spi.R2dbcException.class)
                                                                                                                .hasMessageContaining("'doc'")
                                                                                                                .hasMessageContaining("JSONB")
                                                                                                                .hasMessageContaining("java.util.Map");
    }

    @Test
    void get_byName_aTargetTheDriverCannotConvert_failsWithAMessageThatNamesTheColumnTheTypeAndTheTarget() {
        assertThatThrownBy(() -> namedOf("SELECT '1 day'::interval AS span", "span", java.util.Map.class)).isInstanceOf(io.r2dbc.spi.R2dbcException.class)
                                                                                                          .hasMessageContaining("'span'")
                                                                                                          .hasMessageContaining("INTERVAL")
                                                                                                          .hasMessageContaining("java.util.Map");
    }

    @Test
    void get_objectTarget_ofNull_isNull() {
        assertThat(objectOf("SELECT NULL::uuid")).isNull();
    }

    private static Object objectOf(String sql) {
        return readWith(sql, row -> row.get(0, Object.class));
    }

    private static Object namedObjectOf(String sql, String column) {
        return namedOf(sql, column, Object.class);
    }

    private static Object namedOf(String sql, String column, Class<?> target) {
        return readWith(sql, row -> row.get(column, target));
    }

    private static Object readWith(String sql, java.util.function.Function<io.r2dbc.spi.Row, Object> read) {
        return Mono.from(connection.createStatement(sql).execute())
                   .flatMapMany(result -> result.map((row, meta) -> java.util.Optional.ofNullable(read.apply(row))))
                   .blockFirst()
                   .orElse(null);
    }
}
