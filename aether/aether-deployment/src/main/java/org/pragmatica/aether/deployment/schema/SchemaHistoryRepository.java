// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.schema;

import org.pragmatica.aether.deployment.schema.ParsedMigration.MigrationType;
import org.pragmatica.aether.deployment.schema.SchemaHistoryEvolution.Step;
import org.pragmatica.aether.deployment.schema.SchemaHistoryRepository.MigrationProgress;
import org.pragmatica.aether.deployment.schema.SchemaHistoryRepository.MigrationStatus;
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
    /// Autocommit-checkpoint write (#338-B2): UPSERT an `IN_PROGRESS` history row for the
    /// migration's `(version, type)` with `statements_completed = 0` before the autocommit loop
    /// begins. The descriptive fields (`description`/`script`/`checksum`/`applied_by`) and the
    /// initial `applied_at` are filled now; `execution_ms` is recorded as `0` and the row is
    /// finalized later by [#finalizeSuccess]. The PK is `(version, type)`, so the write is a
    /// delete-then-insert to be safe across a re-entered run.
    Promise<Unit> recordInProgress(SqlConnector connector, AppliedMigration migration, int totalStatements);

    /// Autocommit-checkpoint advance (#338-B2): `UPDATE statements_completed = ?` for the
    /// `IN_PROGRESS` row identified by `(version, type)`. Each call is its own auto-commit, so the
    /// checkpoint advances durably as each statement self-commits.
    Promise<Unit> updateProgress(SqlConnector connector, int version, MigrationType type, int statementsCompleted);

    /// Autocommit-checkpoint state change (#338-B2): `UPDATE status = ?` for the row identified by
    /// `(version, type)`. Used to mark a partially-applied migration `FAILED` on error.
    Promise<Unit> markStatus(SqlConnector connector, int version, MigrationType type, String status);
    /// Autocommit-success finalize (#338-B2): transitions the existing `IN_PROGRESS` row for
    /// `(version, type)` to `SUCCESS`, filling the complete record (`checksum`, `applied_at`,
    /// `execution_ms`) and setting `statements_completed = totalStatements`. This is an `UPDATE` of
    /// the row [#recordInProgress] already inserted — NOT a second `INSERT` (which would conflict on
    /// the `(version, type)` PK). The transactional path uses [#recordMigration] instead.
    Promise<Unit> finalizeSuccess(SqlConnector connector, AppliedMigration migration, int totalStatements);
    /// Autocommit-resume gate read (#338-B2): returns the checkpoint state for `(version, type)` —
    /// its `status`, `statements_completed`, and stored `checksum` — or [Option#none()] when no row
    /// exists (a fresh run). A `SUCCESS` row means the migration is already fully applied; an
    /// `IN_PROGRESS` or `FAILED` row means it can be resumed from `statements_completed`, provided
    /// the stored `checksum` still matches the current script (a changed script is a
    /// modified-after-applied error, not a resume).
    Promise<Option<MigrationProgress>> queryProgress(SqlConnector connector, int version, MigrationType type);

    static SchemaHistoryRepository schemaHistoryRepository() {
        return new DefaultSchemaHistoryRepository();
    }

    /// The `status` values of a history row, tracked by the B1 `status` column. A normally-recorded
    /// (transactional) row defaults to [#SUCCESS]; the autocommit checkpoint path moves a row
    /// [#IN_PROGRESS] ⇒ [#SUCCESS] on completion or ⇒ [#FAILED] on a partial failure.
    interface MigrationStatus {
        String SUCCESS = "SUCCESS";
        String IN_PROGRESS = "IN_PROGRESS";
        String FAILED = "FAILED";
    }

    /// The resumable checkpoint state of an autocommit migration, read by [#queryProgress].
    ///
    /// @param status               one of [MigrationStatus#SUCCESS], [MigrationStatus#IN_PROGRESS],
    ///                             [MigrationStatus#FAILED]
    /// @param statementsCompleted  how many statements have durably self-committed so far
    /// @param checksum             the script checksum stored when the row was written, compared
    ///                             against the current script before a resume to reject a changed
    ///                             script (a modified-after-applied error, not a resume)
    record MigrationProgress(String status, int statementsCompleted, long checksum) {
        public static MigrationProgress migrationProgress(String status, int statementsCompleted, long checksum) {
            return new MigrationProgress(status, statementsCompleted, checksum);
        }
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

    private static final String UPDATE_META_SQL = "UPDATE " + SchemaHistoryEvolution.META_TABLE
                                                + " SET schema_version = ?";

    private static final RowMapper<Integer> SCHEMA_VERSION_MAPPER = row -> row.getInt("schema_version");

    private static final String QUERY_APPLIED_SQL = "SELECT version, type, description, script, checksum, applied_by, applied_at, execution_ms "
                                                  + "FROM aether_schema_history WHERE status = 'SUCCESS' ORDER BY version";

    private static final String INSERT_SQL = "INSERT INTO aether_schema_history (version, type, description, script, checksum, applied_by, applied_at, execution_ms) "
                                           + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String INSERT_PROGRESS_SQL = "INSERT INTO aether_schema_history "
                                                    + "(version, type, description, script, checksum, applied_by, applied_at, execution_ms, status, statements_completed) "
                                                    + "VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, 0)";

    private static final String UPDATE_PROGRESS_SQL = "UPDATE aether_schema_history SET statements_completed = ? "
                                                    + "WHERE version = ? AND type = ?";

    private static final String UPDATE_STATUS_SQL = "UPDATE aether_schema_history SET status = ? WHERE version = ? AND type = ?";

    private static final String FINALIZE_SUCCESS_SQL = "UPDATE aether_schema_history "
                                                     + "SET status = ?, checksum = ?, applied_at = ?, execution_ms = ?, statements_completed = ? "
                                                     + "WHERE version = ? AND type = ?";

    private static final String QUERY_PROGRESS_SQL = "SELECT status, statements_completed, checksum FROM aether_schema_history "
                                                   + "WHERE version = ? AND type = ?";

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

    private static final RowMapper<MigrationProgress> PROGRESS_MAPPER = row -> all(row.getString("status"),
                                                                                   row.getInt("statements_completed"),
                                                                                   row.getLong("checksum")).map(MigrationProgress::new);

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
            result = result.flatMap(_ -> connector.update(statement)
                                                  .mapToUnit());
        }

        return result;
    }

    private static List<String> renderStatements(List<Step> steps, DatabaseType type) {
        return steps.stream()
                    .flatMap(step -> step.ddl()
                                         .render(type)
                                         .stream())
                    .toList();
    }

    private Promise<Unit> persistSchemaVersion(SqlConnector connector, int previousVersion) {
        return previousVersion == 0
               ? connector.update(INSERT_META_SQL, SchemaHistoryEvolution.LATEST_VERSION)
                          .mapToUnit()
               : connector.update(UPDATE_META_SQL, SchemaHistoryEvolution.LATEST_VERSION)
                          .mapToUnit();
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

    /// UPSERT the `IN_PROGRESS` checkpoint row for `(version, type)` via delete-then-insert so a
    /// re-entered autocommit run (e.g. #118's retry) starts from a clean row. The insert sets
    /// `execution_ms = 0` and `statements_completed = 0`; [#finalizeSuccess] fills the final values.
    @Override
    public Promise<Unit> recordInProgress(SqlConnector connector, AppliedMigration migration, int totalStatements) {
        return removeMigration(connector,
                               migration.version(),
                               migration.type()).flatMap(_ -> connector.update(INSERT_PROGRESS_SQL,
                                                                               migration.version(),
                                                                               migration.type().name(),
                                                                               migration.description(),
                                                                               migration.script(),
                                                                               migration.checksum(),
                                                                               migration.appliedBy(),
                                                                               migration.appliedAt(),
                                                                               MigrationStatus.IN_PROGRESS))
                              .mapToUnit();
    }

    @Override
    public Promise<Unit> updateProgress(SqlConnector connector,
                                        int version,
                                        MigrationType type,
                                        int statementsCompleted) {
        return connector.update(UPDATE_PROGRESS_SQL,
                                statementsCompleted,
                                version,
                                type.name())
                        .mapToUnit();
    }

    @Override
    public Promise<Unit> markStatus(SqlConnector connector, int version, MigrationType type, String status) {
        return connector.update(UPDATE_STATUS_SQL,
                                status,
                                version,
                                type.name())
                        .mapToUnit();
    }

    /// Finalize the autocommit run by transitioning the existing `IN_PROGRESS` row to `SUCCESS` and
    /// filling the complete record — an `UPDATE` of the row [#recordInProgress] inserted, NOT a
    /// second `INSERT` (which would conflict on the `(version, type)` PK).
    @Override
    public Promise<Unit> finalizeSuccess(SqlConnector connector, AppliedMigration migration, int totalStatements) {
        return connector.update(FINALIZE_SUCCESS_SQL,
                                MigrationStatus.SUCCESS,
                                migration.checksum(),
                                migration.appliedAt(),
                                migration.executionMs(),
                                totalStatements,
                                migration.version(),
                                migration.type().name())
                        .mapToUnit();
    }

    @Override
    public Promise<Option<MigrationProgress>> queryProgress(SqlConnector connector, int version, MigrationType type) {
        return connector.queryOptional(QUERY_PROGRESS_SQL, PROGRESS_MAPPER, version, type.name());
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
