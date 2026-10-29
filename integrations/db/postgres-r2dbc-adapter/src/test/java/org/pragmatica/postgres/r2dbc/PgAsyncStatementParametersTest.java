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

import org.pragmatica.postgres.net.Connectible;
import org.pragmatica.postgres.net.SslConfig;
import org.pragmatica.postgres.net.netty.NettyConnectibleBuilder;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.Result;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// #2432: parameterised statements through the adapter against a LIVE PostgreSQL 16. The adapter used to rewrite `$n`
/// to `?`, which the server rejects with SQLSTATE 42601, so every bound statement failed. Select, insert and update
/// each bind a parameter; the `$1::int` cast is the exact trigger from the report. Self-skips when no Docker daemon
/// is reachable.
class PgAsyncStatementParametersTest {
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

    @BeforeEach
    void freshTable() {
        update("DROP TABLE IF EXISTS items");
        update("CREATE TABLE items(id INT PRIMARY KEY, name TEXT)");
    }

    @Test
    void select_returnsBoundValue_withCastedPlaceholder() {
        var value = Mono.from(connection.createStatement("SELECT $1::int AS v").bind(0, 7).execute())
                        .flatMapMany(r -> r.map((row, meta) -> row.get(0, Integer.class)))
                        .blockFirst();

        assertThat(value).isEqualTo(7);
    }

    @Test
    void insert_storesBoundValues_withTwoPlaceholders() {
        var rows = rowsUpdated(connection.createStatement("INSERT INTO items(id, name) VALUES ($1, $2)")
                                         .bind(0, 1)
                                         .bind(1, "one")
                                         .execute());

        assertThat(rows).isEqualTo(1L);
        assertThat(nameOf(1)).isEqualTo("one");
    }

    @Test
    void update_changesRow_withBoundValues() {
        update("INSERT INTO items(id, name) VALUES (2, 'two')");

        var rows = rowsUpdated(connection.createStatement("UPDATE items SET name = $1 WHERE id = $2")
                                         .bind(0, "deux")
                                         .bind(1, 2)
                                         .execute());

        assertThat(rows).isEqualTo(1L);
        assertThat(nameOf(2)).isEqualTo("deux");
    }

    private static String nameOf(int id) {
        return Mono.from(connection.createStatement("SELECT name FROM items WHERE id = $1").bind(0, id).execute())
                   .flatMapMany(r -> r.map((row, meta) -> row.get(0, String.class)))
                   .blockFirst();
    }

    private static void update(String sql) {
        rowsUpdated(connection.createStatement(sql).execute());
    }

    private static Long rowsUpdated(Publisher<? extends Result> results) {
        return Flux.from(results).concatMap(Result::getRowsUpdated).blockFirst();
    }
}
