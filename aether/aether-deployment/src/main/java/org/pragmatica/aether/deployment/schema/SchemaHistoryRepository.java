// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.schema;

import org.pragmatica.aether.deployment.schema.ParsedMigration.MigrationType;
import org.pragmatica.aether.deployment.schema.SchemaHistoryEvolution.Step;
import org.pragmatica.aether.resource.db.DatabaseType;
import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.List;

import static org.pragmatica.lang.Result.all;


public interface SchemaHistoryRepository {
    Promise<Unit> bootstrap(SqlConnector connector);
    Promise<List<AppliedMigration>> queryApplied(SqlConnector connector);
    Promise<Unit> recordMigration(SqlConnector connector, AppliedMigration migration);
    Promise<Unit> removeMigration(SqlConnector connector, int version, MigrationType type);
    Promise<Option<Long>> queryRepeatableChecksum(SqlConnector connector, String description);

    static SchemaHistoryRepository schemaHistoryRepository() {
        return new DefaultSchemaHistoryRepository();
    }

    record AppliedMigration(int version,
                            MigrationType type,
                            String description,
                            String script,
                            long checksum,
                            String appliedBy,
                            long appliedAt,
                            int executionMs) {
        public static AppliedMigration appliedMigration(int version,
                                                        MigrationType type,
                                                        String description,
                                                        String script,
                                                        long checksum,
                                                        String appliedBy,
                                                        long appliedAt,
                                                        int executionMs) {
            return new AppliedMigration(version, type, description, script, checksum, appliedBy, appliedAt, executionMs);
        }
    }
}

final class DefaultSchemaHistoryRepository implements SchemaHistoryRepository {
    private static final String CREATE_META_SQL = "CREATE TABLE IF NOT EXISTS " + SchemaHistoryEvolution.META_TABLE
                                                + " (schema_version INTEGER NOT NULL)";

    private static final String QUERY_META_SQL = "SELECT schema_version FROM " + SchemaHistoryEvolution.META_TABLE;

    private static final String INSERT_META_SQL = "INSERT INTO " + SchemaHistoryEvolution.META_TABLE
                                                + " (schema_version) VALUES (?)";

    private static final String UPDATE_META_SQL = "UPDATE " + SchemaHistoryEvolution.META_TABLE + " SET schema_version = ?";

    private static final RowMapper<Integer> SCHEMA_VERSION_MAPPER = row -> row.getInt("schema_version");

    private static final String QUERY_APPLIED_SQL = "SELECT version, type, description, script, checksum, applied_by, applied_at, execution_ms "
                                                  + "FROM aether_schema_history ORDER BY version";

    private static final String INSERT_SQL = "INSERT INTO aether_schema_history (version, type, description, script, checksum, applied_by, applied_at, execution_ms) "
                                           + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String DELETE_SQL = "DELETE FROM aether_schema_history WHERE version = ? AND type = ?";

    private static final String QUERY_REPEATABLE_SQL = "SELECT checksum FROM aether_schema_history WHERE type = 'REPEATABLE' AND description = ?";

    private static final RowMapper<SchemaHistoryRepository.AppliedMigration> APPLIED_MIGRATION_MAPPER = row -> all(row.getInt("version"),
                                                                                                                   row.getString("type")
                                                                                                                      .map(MigrationType::valueOf),
                                                                                                                   row.getString("description"),
                                                                                                                   row.getString("script"),
                                                                                                                   row.getLong("checksum"),
                                                                                                                   row.getString("applied_by"),
                                                                                                                   row.getLong("applied_at"),
                                                                                                                   row.getInt("execution_ms")).map(SchemaHistoryRepository.AppliedMigration::new);

    private static final RowMapper<Long> CHECKSUM_MAPPER = row -> row.getLong("checksum");

    /// Bootstraps the `aether_schema_history` table via internally-versioned, self-evolving DDL
    /// (#338-B1): create the fixed meta table `IF NOT EXISTS`, read its current internal version
    /// (absent ⇒ 0), run every [SchemaHistoryEvolution] step from `version + 1` to
    /// [SchemaHistoryEvolution#LATEST_VERSION], then persist the latest version. A cluster already
    /// at the latest version runs no evolution DDL — the flow is idempotent.
    @Override
    public Promise<Unit> bootstrap(SqlConnector connector) {
        return connector.update(CREATE_META_SQL)
                        .flatMap(_ -> readSchemaVersion(connector))
                        .flatMap(version -> evolveFrom(connector, version));
    }

    private Promise<Integer> readSchemaVersion(SqlConnector connector) {
        return connector.queryOptional(QUERY_META_SQL, SCHEMA_VERSION_MAPPER)
                        .map(stored -> stored.or(0));
    }

    private Promise<Unit> evolveFrom(SqlConnector connector, int currentVersion) {
        var steps = SchemaHistoryEvolution.stepsFrom(currentVersion);

        return steps.isEmpty()
               ? Promise.unitPromise()
               : applySteps(connector, steps).flatMap(_ -> persistSchemaVersion(connector, currentVersion));
    }

    private Promise<Unit> applySteps(SqlConnector connector, List<Step> steps) {
        var type = connector.config().effectiveType();
        var result = Promise.unitPromise();

        for (var statement : renderStatements(steps, type)) {
            result = result.flatMap(_ -> connector.update(statement).mapToUnit());
        }

        return result;
    }

    private static List<String> renderStatements(List<Step> steps, DatabaseType type) {
        return steps.stream()
                    .flatMap(step -> step.ddl().render(type).stream())
                    .toList();
    }

    private Promise<Unit> persistSchemaVersion(SqlConnector connector, int previousVersion) {
        return previousVersion == 0
               ? connector.update(INSERT_META_SQL, SchemaHistoryEvolution.LATEST_VERSION).mapToUnit()
               : connector.update(UPDATE_META_SQL, SchemaHistoryEvolution.LATEST_VERSION).mapToUnit();
    }

    @Override
    public Promise<List<SchemaHistoryRepository.AppliedMigration>> queryApplied(SqlConnector connector) {
        return connector.queryList(QUERY_APPLIED_SQL, APPLIED_MIGRATION_MAPPER);
    }

    @Override
    public Promise<Unit> recordMigration(SqlConnector connector, SchemaHistoryRepository.AppliedMigration migration) {
        return connector.update(INSERT_SQL,
                                migration.version(),
                                migration.type().name(),
                                migration.description(),
                                migration.script(),
                                migration.checksum(),
                                migration.appliedBy(),
                                migration.appliedAt(),
                                migration.executionMs())
                        .mapToUnit();
    }

    @Override
    public Promise<Unit> removeMigration(SqlConnector connector, int version, MigrationType type) {
        return connector.update(DELETE_SQL,
                                version,
                                type.name())
                        .mapToUnit();
    }

    @Override
    public Promise<Option<Long>> queryRepeatableChecksum(SqlConnector connector, String description) {
        return connector.queryOptional(QUERY_REPEATABLE_SQL, CHECKSUM_MAPPER, description);
    }
}
