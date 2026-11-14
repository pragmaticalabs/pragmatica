// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.db.migration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.DatabaseType;
import org.pragmatica.aether.resource.db.PoolConfig;
import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.db.migration.ParsedMigration.MigrationType;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.db.migration.SchemaHistoryRepository.AppliedMigration.appliedMigration;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// Test double and driver shared by the history-statement tests: runs every [SchemaHistoryRepository] operation
/// against a connector that answers success and records each statement with its parameters.
final class HistoryOperations {
    private HistoryOperations() {}

    /// Every repository operation for `type`, in a fixed order, as label to the statements it issued.
    static Map<String, List<String>> record(DatabaseType type, SchemaHistoryRepository repository) {
        var migration = appliedMigration(7, MigrationType.VERSIONED, "desc", "V7__desc.sql", 42L, "node-1", 1000L, 5);
        var out = new LinkedHashMap<String, List<String>>();

        run(out, type, 0, "bootstrap", repository::bootstrap);
        run(out, type, 1, "bootstrapFrom1", repository::bootstrap);
        run(out, type, 0, "claimOwnership", connector -> repository.claimOwnership(connector, "ds", "owner"));
        run(out, type, 0, "queryApplied", repository::queryApplied);
        run(out, type, 0, "recordMigration", connector -> repository.recordMigration(connector, migration));
        run(out, type, 0, "recordInProgress", connector -> repository.recordInProgress(connector, migration, 3));
        run(out, type, 0, "updateProgress", connector -> repository.updateProgress(connector, 7, MigrationType.VERSIONED, 2));
        run(out, type, 0, "markStatus", connector -> repository.markStatus(connector, 7, MigrationType.VERSIONED, "FAILED"));
        run(out, type, 0, "finalizeSuccess", connector -> repository.finalizeSuccess(connector, migration, 3));
        run(out, type, 0, "queryProgress", connector -> repository.queryProgress(connector, 7, MigrationType.VERSIONED));
        run(out, type, 0, "removeMigration", connector -> repository.removeMigration(connector, 7, MigrationType.VERSIONED));
        run(out, type, 0, "queryRepeatableChecksum", connector -> repository.queryRepeatableChecksum(connector, "desc"));
        return out;
    }

    private static void run(Map<String, List<String>> out, DatabaseType type, int metaVersion, String label,
                            Function<SqlConnector, Promise<?>> operation) {
        var connector = new ScriptedConnector(type, metaVersion);

        operation.apply(connector).await();
        out.put(label, connector.statements);
    }

    /// Answers every call with success, records each statement with its parameters, and reports the meta table's
    /// internal version as `metaVersion` (0 = no row) so both the insert and the update meta paths are reachable.
    static final class ScriptedConnector implements SqlConnector {
        private final DatabaseConnectorConfig config;
        private final int metaVersion;
        final List<String> statements = new ArrayList<>();

        ScriptedConnector(DatabaseType type, int metaVersion) {
            this.metaVersion = metaVersion;
            this.config = new DatabaseConnectorConfig(none(), some(type), some("localhost"), none(), some("test"), none(), none(),
                                                      PoolConfig.DEFAULT, Map.of(), none(), none(), none());
        }

        private void record(String sql, Object[] params) {
            statements.add(params.length == 0
                           ? sql
                           : sql + "  -- " + Arrays.toString(params));
        }

        @Override
        public Promise<Integer> update(String sql, Object... params) {
            record(sql, params);
            return Promise.success(1);
        }

        @Override
        public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
            record(sql, params);
            return Causes.cause("queryOne is not scripted").promise();
        }

        @Override
        public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params) {
            record(sql, params);

            return metaVersion > 0 && sql.contains(SchemaHistoryEvolution.META_TABLE)
                   ? mapper.map(new MetaRow(metaVersion)).async().map(Option::some)
                   : Promise.success(none());
        }

        @Override
        public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
            record(sql, params);
            return Promise.success(List.of());
        }

        @Override
        public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
            statements.add(sql);
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

    /// The single-column row the meta-table read maps: only `schema_version` is answered.
    record MetaRow(int schemaVersion) implements RowMapper.RowAccessor {
        @Override
        public Result<Integer> getInt(String column) {
            return Result.success(schemaVersion);
        }

        @Override
        public Result<String> getString(String column) {
            return unscripted(column);
        }

        @Override
        public Result<Long> getLong(String column) {
            return unscripted(column);
        }

        @Override
        public Result<Double> getDouble(String column) {
            return unscripted(column);
        }

        @Override
        public Result<Boolean> getBoolean(String column) {
            return unscripted(column);
        }

        @Override
        public Result<byte[]> getBytes(String column) {
            return unscripted(column);
        }

        @Override
        public <V> Result<V> getObject(String column, Class<V> type) {
            return unscripted(column);
        }

        private static <V> Result<V> unscripted(String column) {
            return Causes.cause("column " + column + " is not scripted").result();
        }
    }
}
