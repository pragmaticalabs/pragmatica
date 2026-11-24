// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db.async;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.Map;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.postgres.PgColumn;
import org.pragmatica.postgres.PgRow;
import org.pragmatica.postgres.net.Connectible;
import org.pragmatica.postgres.net.Connection;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.resource.db.DatabaseConnectorConfig.databaseConnectorConfigBuilder;
import static org.assertj.core.api.Assertions.assertThat;


/// #3635: the pool and the dedicated (`plain()`) Connectible of one connector each hold a lease on the builder-owned
/// event-loop group, which is released with the LAST lease: `stop()` therefore has to close both, or the group of
/// every stopped connector outlives it.
class PgAsyncSqlConnectorStopReleasesBothTest {
    private static final TimeSpan GUARD = TimeSpan.fromDuration(Duration.ofSeconds(5));

    @Test
    void stop_closesPoolAndDedicatedConnectible() {
        var pool = new CountingConnectible();
        var dedicated = new CountingConnectible();
        var config = databaseConnectorConfigBuilder().withName("stop-both")
                                                   .withAsyncUrl("postgresql://127.0.0.1:1/db")
                                                   .withUsername("u")
                                                   .withPassword("p")
                                                   .build()
                                                   .unwrap();
        var connector = PgAsyncSqlConnector.pgAsyncSqlConnector(config, pool, dedicated);

        assertThat(connector.stop().await(GUARD).isSuccess()).isTrue();
        assertThat(pool.closes.get()).as("pool closed").isEqualTo(1);
        assertThat(dedicated.closes.get()).as("dedicated closed").isEqualTo(1);
    }

    private static final class CountingConnectible implements Connectible {
        final AtomicInteger closes = new AtomicInteger();

        @Override
        public Promise<Connection> getConnection() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Promise<Unit> script(BiConsumer<Map<String, PgColumn>, PgColumn[]> onColumns,
                                    Consumer<PgRow> onRow,
                                    Consumer<Integer> onAffected,
                                    String sql) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Promise<Integer> query(BiConsumer<Map<String, PgColumn>, PgColumn[]> onColumns,
                                      Consumer<PgRow> onRow,
                                      String sql,
                                      Object... params) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Promise<Unit> close() {
            closes.incrementAndGet();

            return Promise.success(Unit.unit());
        }
    }
}
