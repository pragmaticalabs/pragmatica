// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.schema;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.DatabaseType;
import org.pragmatica.aether.resource.db.PoolConfig;
import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.blueprint.MigrationEntry.migrationEntry;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// FDB-2a (#2591), survivor U1: the policy's migration timeout reaches the pinned session as its server-side statement
/// bound. The plumbing is `aetherSchemaManager(policy)` -> `SchemaMigrations(policy.migrationTimeout())` -> `pinned(bound, ...)`;
/// the CIC tests build `SchemaMigrations` directly, so nothing else pins the first link. Mutating it to an unbounded
/// span leaves every statement on every transport without a server-side bound.
class AetherSchemaManagerPinnedBoundTest {
    private static final BlueprintId OWNER = BlueprintId.blueprintId("org.example:orders-app:1.0.0").unwrap();
    private static final SchemaPolicy.FailureMode MODE = SchemaPolicy.FailureMode.LEAVE_PARTIAL;

    @Test
    void migrate_pinsTheSessionWithThePolicysMigrationTimeout() {
        var policy = SchemaPolicy.schemaPolicy(MODE, SchemaPolicy.FailoverMode.AUTO_RESUME, timeSpan(7).minutes());
        var connector = new PinnedBoundRecorder();

        AetherSchemaManager.aetherSchemaManager(policy)
                           .migrate("ds", List.of(migrationEntry("V1__a.sql", "CREATE TABLE a (id INT);", 1L)), connector, "node-1", OWNER)
                           .await();

        assertThat(connector.pinnedBounds).containsExactly(Duration.ofMinutes(7));
    }

    @Test
    void undoAndBaseline_pinTheSessionWithThePolicysMigrationTimeout() {
        var policy = SchemaPolicy.schemaPolicy(MODE, SchemaPolicy.FailoverMode.AUTO_RESUME, timeSpan(11).minutes());
        var connector = new PinnedBoundRecorder();
        var manager = AetherSchemaManager.aetherSchemaManager(policy);

        manager.undo("ds", 0, List.of(), connector, "node-1", OWNER).await();
        manager.baseline("ds", 1, List.of(), connector, "node-1", OWNER).await();

        assertThat(connector.pinnedBounds).containsExactly(Duration.ofMinutes(11), Duration.ofMinutes(11));
    }

    /// Records the bound each `pinned` scope is opened with, and refuses the scope: nothing past it is under test.
    private static final class PinnedBoundRecorder implements SqlConnector {
        private final List<Duration> pinnedBounds = new ArrayList<>();
        private final DatabaseConnectorConfig config = new DatabaseConnectorConfig(none(), some(DatabaseType.POSTGRESQL), some("localhost"), none(),
                                                                                   some("test"), none(), none(), PoolConfig.DEFAULT, Map.of(),
                                                                                   none(), none(), none());

        @Override
        public <T> Promise<T> pinned(Duration statementTimeout, TransactionCallback<T> body, TransactionCallback<Unit> release) {
            pinnedBounds.add(statementTimeout);
            return Causes.cause("pinned scope refused by the recorder").promise();
        }

        @Override
        public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
            return unexpected(sql);
        }

        @Override
        public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params) {
            return unexpected(sql);
        }

        @Override
        public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
            return unexpected(sql);
        }

        @Override
        public Promise<Integer> update(String sql, Object... params) {
            return unexpected(sql);
        }

        @Override
        public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
            return unexpected(sql);
        }

        @Override
        public <T> Promise<T> transactional(TransactionCallback<T> callback) {
            return unexpected("transactional");
        }

        @Override
        public DatabaseConnectorConfig config() {
            return config;
        }

        @Override
        public Promise<Boolean> isHealthy() {
            return Promise.success(true);
        }

        private static <T> Promise<T> unexpected(String what) {
            return Causes.cause("statement outside the pinned scope: " + what).promise();
        }
    }
}
