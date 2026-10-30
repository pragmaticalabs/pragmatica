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
import org.testcontainers.DockerClientFactory;
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
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the live-PostgreSQL pin");
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

    @Test
    void get_aTypeTheDriverCannotConvert_failsWithAMessageThatNamesTheColumnTheTypeAndTheTarget() {
        assertThatThrownBy(() -> objectOf("SELECT '{}'::jsonb AS doc")).isInstanceOf(io.r2dbc.spi.R2dbcException.class)
                                                                         .hasMessageContaining("'doc'")
                                                                         .hasMessageContaining("JSONB")
                                                                         .hasMessageContaining("java.lang.Object");
    }

    @Test
    void get_objectTarget_ofNull_isNull() {
        assertThat(objectOf("SELECT NULL::uuid")).isNull();
    }

    private static Object objectOf(String sql) {
        return Mono.from(connection.createStatement(sql).execute())
                   .flatMapMany(result -> result.map((row, meta) -> java.util.Optional.ofNullable(row.get(0, Object.class))))
                   .blockFirst()
                   .orElse(null);
    }
}
