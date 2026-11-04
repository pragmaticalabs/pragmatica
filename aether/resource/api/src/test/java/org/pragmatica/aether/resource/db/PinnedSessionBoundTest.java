// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// FDB-2a (#2591), CTO ruling of fix round 1: the pinned-session bound is PostgreSQL-only, and a zero bound is SENT as
/// `0` (it overrides a role or database default), never skipped.
class PinnedSessionBoundTest {
    @Test
    void serverBounded_postgresPositiveBound_setsBothTimeoutsInMillisBeforeTheBody() {
        var session = new Recording(DatabaseType.POSTGRESQL);

        run(session, Duration.ofSeconds(30));

        assertThat(session.statements).containsExactly("SET statement_timeout = 30000", "SET lock_timeout = 30000", "BODY");
    }

    /// P5 (re-judged against fb06b90adc: the ruling inverted the old "ZERO sends no SET"): zero is sent explicitly.
    @Test
    void serverBounded_postgresZeroBound_isSentAsZeroToOverrideARoleDefault() {
        var session = new Recording(DatabaseType.POSTGRESQL);

        run(session, Duration.ZERO);

        assertThat(session.statements).containsExactly("SET statement_timeout = 0", "SET lock_timeout = 0", "BODY");
    }

    @Test
    void serverBounded_postgresNegativeBound_isSentAsZero() {
        var session = new Recording(DatabaseType.POSTGRESQL);

        run(session, Duration.ofSeconds(-5));

        assertThat(session.statements).containsExactly("SET statement_timeout = 0", "SET lock_timeout = 0", "BODY");
    }

    /// A positive bound below one millisecond must round UP to 1 ms, never down to 0 (= unbounded).
    @Test
    void serverBounded_postgresSubMillisecondBound_roundsUpToOneMillisecond() {
        var session = new Recording(DatabaseType.POSTGRESQL);

        run(session, Duration.ofNanos(1));

        assertThat(session.statements).containsExactly("SET statement_timeout = 1", "SET lock_timeout = 1", "BODY");
    }

    /// P4: `SET statement_timeout` is PostgreSQL syntax; any other engine's pinned session gets no SET, whatever the bound.
    @Test
    void serverBounded_nonPostgresConnector_getsNoSetStatementAndStillRunsTheBody() {
        for (var type : List.of(DatabaseType.MYSQL, DatabaseType.H2, DatabaseType.MARIADB)) {
            var session = new Recording(type);

            run(session, Duration.ofSeconds(30));

            assertThat(session.statements).as("no SET on %s", type).containsExactly("BODY");
        }
    }

    private static void run(Recording session, Duration bound) {
        PinnedSessionBound.<Unit> serverBounded(bound, s -> s.update("BODY").map(_ -> Unit.unit())).execute(session).await();
    }

    private static final class Recording implements SqlConnector {
        private final DatabaseConnectorConfig config;
        private final List<String> statements = new ArrayList<>();

        Recording(DatabaseType type) {
            this.config = new DatabaseConnectorConfig(none(), some(type), some("localhost"), none(), some("test"), none(), none(),
                                                      PoolConfig.DEFAULT, Map.of(), none(), none(), none());
        }

        @Override
        public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
            return DatabaseConnectorError.ResultNotFound.INSTANCE.promise();
        }

        @Override
        public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params) {
            return Promise.success(Option.none());
        }

        @Override
        public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
            return Promise.success(List.of());
        }

        @Override
        public Promise<Integer> update(String sql, Object... params) {
            statements.add(sql);
            return Promise.success(0);
        }

        @Override
        public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
            return Promise.success(new int[0]);
        }

        @Override
        public <T> Promise<T> transactional(TransactionCallback<T> callback) {
            return callback.execute(this);
        }

        @Override
        public DatabaseConnectorConfig config() {
            return config;
        }

        @Override
        public Promise<Boolean> isHealthy() {
            return Promise.success(true);
        }
    }
}
