// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.db.migration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.db.migration.SchemaHistoryRepository.AppliedMigration.appliedMigration;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// T7 (#2100 E-B L1): with no schema every statement the history repository and the evolution renderers emit is
/// byte-identical to what they emitted before schema scoping existed, for EVERY [DatabaseType].
///
/// The fixture `history-statements.fixture` was CAPTURED from the unmodified code (the commit that adds it precedes
/// every behaviour change in this lane), so a green run proves backward compatibility rather than self-agreement.
/// To recapture deliberately, run with `-Dhistory.fixture.capture=<absolute path>` and review the diff.
class SchemaHistoryStatementsFixtureTest {
    private static final String FIXTURE = "history-statements.fixture";
    private static final String CAPTURE_PROPERTY = "history.fixture.capture";

    @Test
    void historyStatements_withoutSchema_matchTheCapturedFixture() throws IOException {
        var actual = render();
        var capturePath = System.getProperty(CAPTURE_PROPERTY);

        if (capturePath != null) {
            Files.writeString(Path.of(capturePath), actual, StandardCharsets.UTF_8);
            return;
        }

        assertThat(actual).isEqualTo(readFixture());
    }

    @Test
    void historyStatements_fixtureCoversEveryDialectAndEveryRepositoryOperation() throws IOException {
        var fixture = readFixture();

        for (var type : DatabaseType.values()) {
            assertThat(fixture).as("dialect " + type).contains("[" + type + "]");
        }

        for (var label : List.of("evolution.meta", "evolution.owner", "evolution.v1", "evolution.v2", "bootstrap", "bootstrapFrom1",
                                 "claimOwnership", "queryApplied", "recordMigration", "recordInProgress", "updateProgress",
                                 "markStatus", "finalizeSuccess", "queryProgress", "removeMigration", "queryRepeatableChecksum")) {
            assertThat(fixture).as("operation " + label).contains(" " + label + " ");
        }
    }

    private static String render() {
        var out = new StringBuilder();

        for (var type : DatabaseType.values()) {
            out.append('[').append(type).append("]\n");
            line(out, "evolution.meta", List.of(SchemaHistoryEvolution.createMetaTableSql(type)));
            line(out, "evolution.owner", List.of(SchemaHistoryEvolution.createOwnerTableSql(type)));
            line(out, "evolution.v1", SchemaHistoryEvolution.STEP_V1.ddl().render(type));
            line(out, "evolution.v2", SchemaHistoryEvolution.STEP_V2.ddl().render(type));
            captureOperations(out, type);
        }

        return out.toString();
    }

    private static void captureOperations(StringBuilder out, DatabaseType type) {
        var repository = SchemaHistoryRepository.schemaHistoryRepository();
        var migration = appliedMigration(7, MigrationType.VERSIONED, "desc", "V7__desc.sql", 42L, "node-1", 1000L, 5);

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
    }

    private static void run(StringBuilder out, DatabaseType type, int metaVersion, String label,
                            Function<SqlConnector, Promise<?>> operation) {
        var connector = new ScriptedConnector(type, metaVersion);

        operation.apply(connector).await();
        line(out, label, connector.statements);
    }

    private static void line(StringBuilder out, String label, List<String> statements) {
        out.append(' ').append(label).append(" \n");

        for (var statement : statements) {
            out.append("    ").append(statement.replace("\n", "\\n")).append('\n');
        }
    }

    private static String readFixture() throws IOException {
        try (InputStream in = SchemaHistoryStatementsFixtureTest.class.getResourceAsStream(FIXTURE)) {
            assertThat(in).as("fixture resource " + FIXTURE).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /// Answers every call with success, records each statement with its parameters, and reports the meta table's
    /// internal version as `metaVersion` (0 = no row) so both the insert and the update meta paths are reachable.
    private static final class ScriptedConnector implements SqlConnector {
        private final DatabaseConnectorConfig config;
        private final int metaVersion;
        private final List<String> statements = new ArrayList<>();

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
    private record MetaRow(int schemaVersion) implements RowMapper.RowAccessor {
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
