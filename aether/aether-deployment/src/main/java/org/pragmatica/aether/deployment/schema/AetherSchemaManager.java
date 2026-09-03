// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.schema;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.pragmatica.aether.deployment.schema.MigrationDialects.ExecutionDialect;
import org.pragmatica.aether.deployment.schema.ParsedMigration.MigrationType;
import org.pragmatica.aether.deployment.schema.SchemaHistoryRepository.AppliedMigration;
import org.pragmatica.aether.deployment.schema.SchemaHistoryRepository.MigrationProgress;
import org.pragmatica.aether.deployment.schema.SchemaHistoryRepository.MigrationStatus;
import org.pragmatica.aether.pg.split.Statement;
import org.pragmatica.aether.pg.split.StatementSplitter;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.MigrationEntry;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.aether.deployment.schema.SchemaError.BaselineConflict.baselineConflict;
import static org.pragmatica.aether.deployment.schema.SchemaError.ChecksumMismatch.checksumMismatch;
import static org.pragmatica.aether.deployment.schema.SchemaError.UndoNotAvailable.undoNotAvailable;
import static org.pragmatica.aether.deployment.schema.SchemaHistoryRepository.AppliedMigration.appliedMigration;


public interface AetherSchemaManager {
    /// `owner` is the blueprint whose artifact declared these scripts. Every entry point claims
    /// migration ownership of the PHYSICAL database
    /// ([SchemaHistoryRepository#claimOwnership]) right after bootstrap and before reading applied
    /// history, so a database already claimed by a different blueprint is refused with
    /// [SchemaError.PhysicalDatasourceOwnershipConflict] having written nothing.
    Promise<SchemaResult> migrate(String datasource,
                                  List<MigrationEntry> scripts,
                                  SqlConnector connector,
                                  String nodeId,
                                  BlueprintId owner);

    Promise<SchemaResult> undo(String datasource,
                               int targetVersion,
                               List<MigrationEntry> scripts,
                               SqlConnector connector,
                               String nodeId,
                               BlueprintId owner);

    Promise<SchemaResult> baseline(String datasource,
                                   int baselineVersion,
                                   List<MigrationEntry> scripts,
                                   SqlConnector connector,
                                   String nodeId,
                                   BlueprintId owner);

    static AetherSchemaManager aetherSchemaManager(SchemaPolicy policy) {
        return new DefaultAetherSchemaManager(policy, SchemaHistoryRepository.schemaHistoryRepository());
    }

    /// Exposes the [SchemaPolicy] this manager was built with, so a caller that must bound its OWN
    /// operation against the same policy (#760/#724 review round 2 item c —
    /// [SchemaOrchestratorService]'s per-attempt migration timeout) reads the single source of truth
    /// instead of duplicating the bound. Defaults to [SchemaPolicy#schemaPolicy()] so existing test
    /// doubles that implement this interface without a policy of their own keep compiling.
    default SchemaPolicy policy() {
        return SchemaPolicy.schemaPolicy();
    }

    record SchemaResult(int appliedCount, int currentVersion, long totalMs) {
        public static SchemaResult schemaResult(int appliedCount, int currentVersion, long totalMs) {
            return new SchemaResult(appliedCount, currentVersion, totalMs);
        }
    }
}

final class DefaultAetherSchemaManager implements AetherSchemaManager {
    private final SchemaPolicy policy;
    private final SchemaHistoryRepository historyRepo;

    DefaultAetherSchemaManager(SchemaPolicy policy, SchemaHistoryRepository historyRepo) {
        this.policy = policy;
        this.historyRepo = historyRepo;
    }

    @Override
    public SchemaPolicy policy() {
        return policy;
    }

    /// Claim ownership of the physical database IMMEDIATELY after bootstrap and BEFORE
    /// [SchemaHistoryRepository#queryApplied], so a refused claim short-circuits with no history read
    /// and no migration applied.
    @Override
    public Promise<SchemaResult> migrate(String datasource,
                                         List<MigrationEntry> scripts,
                                         SqlConnector connector,
                                         String nodeId,
                                         BlueprintId owner) {
        return parseAll(scripts).async()
                       .flatMap(parsed -> historyRepo.bootstrap(connector)
                                                     .flatMap(_ -> historyRepo.claimOwnership(connector,
                                                                                              datasource,
                                                                                              owner))
                                                     .flatMap(_ -> historyRepo.queryApplied(connector))
                                                     .flatMap(applied -> executeMigration(datasource,
                                                                                          parsed,
                                                                                          applied,
                                                                                          connector,
                                                                                          nodeId)));
    }

    @Override
    public Promise<SchemaResult> undo(String datasource,
                                      int targetVersion,
                                      List<MigrationEntry> scripts,
                                      SqlConnector connector,
                                      String nodeId,
                                      BlueprintId owner) {
        return parseAll(scripts).async()
                       .flatMap(parsed -> historyRepo.bootstrap(connector)
                                                     .flatMap(_ -> historyRepo.claimOwnership(connector,
                                                                                              datasource,
                                                                                              owner))
                                                     .flatMap(_ -> historyRepo.queryApplied(connector))
                                                     .flatMap(applied -> executeUndo(datasource,
                                                                                     targetVersion,
                                                                                     parsed,
                                                                                     applied,
                                                                                     connector,
                                                                                     nodeId)));
    }

    @Override
    public Promise<SchemaResult> baseline(String datasource,
                                          int baselineVersion,
                                          List<MigrationEntry> scripts,
                                          SqlConnector connector,
                                          String nodeId,
                                          BlueprintId owner) {
        return parseAll(scripts).async()
                       .flatMap(parsed -> historyRepo.bootstrap(connector)
                                                     .flatMap(_ -> historyRepo.claimOwnership(connector,
                                                                                              datasource,
                                                                                              owner))
                                                     .flatMap(_ -> historyRepo.queryApplied(connector))
                                                     .flatMap(applied -> executeBaseline(datasource,
                                                                                         baselineVersion,
                                                                                         parsed,
                                                                                         applied,
                                                                                         connector,
                                                                                         nodeId)));
    }

    private static Result<List<ParsedMigration>> parseAll(List<MigrationEntry> scripts) {
        return Result.allOf(scripts.stream().map(ParsedMigration::parsedMigration).toList());
    }

    private Promise<SchemaResult> executeMigration(String datasource,
                                                   List<ParsedMigration> parsed,
                                                   List<AppliedMigration> applied,
                                                   SqlConnector connector,
                                                   String nodeId) {
        var byType = groupByType(parsed);
        var baselines = byType.getOrDefault(MigrationType.BASELINE, List.of());
        var versioned = byType.getOrDefault(MigrationType.VERSIONED, List.of());
        var repeatables = byType.getOrDefault(MigrationType.REPEATABLE, List.of());
        var startNanos = System.nanoTime();

        return applyBaselines(datasource, baselines, applied, connector, nodeId).flatMap(baselineCount -> applyVersioned(datasource,
                                                                                                                         versioned,
                                                                                                                         applied,
                                                                                                                         connector,
                                                                                                                         nodeId).flatMap(versionedCount -> applyRepeatables(repeatables,
                                                                                                                                                                            connector,
                                                                                                                                                                            nodeId).map(repeatableCount -> buildResult(baselineCount + versionedCount + repeatableCount,
                                                                                                                                                                                                                       versioned,
                                                                                                                                                                                                                       applied,
                                                                                                                                                                                                                       startNanos))));
    }

    private Promise<Integer> applyBaselines(String datasource,
                                            List<ParsedMigration> baselines,
                                            List<AppliedMigration> applied,
                                            SqlConnector connector,
                                            String nodeId) {
        if (baselines.isEmpty()) {
            return Promise.success(0);
        }

        var maxBaseline = baselines.stream().mapToInt(ParsedMigration::version).max().orElse(0);
        var appliedVersioned = applied.stream().filter(a -> a.type() == MigrationType.VERSIONED).toList();

        if (!appliedVersioned.isEmpty()) {
            var maxApplied = appliedVersioned.stream().mapToInt(AppliedMigration::version).max().orElse(0);

            if (maxApplied > 0) {
                return baselineConflict(datasource, maxApplied).promise();
            }
        }

        return recordSyntheticBaselines(maxBaseline, connector, nodeId);
    }

    private Promise<Integer> recordSyntheticBaselines(int maxVersion, SqlConnector connector, String nodeId) {
        var now = System.currentTimeMillis();

        return IntStream.rangeClosed(1, maxVersion)
                        .mapToObj(v -> recordSyntheticEntry(v, connector, nodeId, now))
                        .reduce(Promise.success(0),
                                this::chainCount,
                                (a, _) -> a);
    }

    private Promise<Integer> recordSyntheticEntry(int version, SqlConnector connector, String nodeId, long now) {
        var migration = appliedMigration(version, MigrationType.BASELINE, "baseline", "synthetic", 0L, nodeId, now, 0);

        return historyRepo.recordMigration(connector, migration)
                          .map(_ -> 1);
    }

    private Promise<Integer> chainCount(Promise<Integer> accumulated, Promise<Integer> next) {
        return accumulated.flatMap(count -> next.map(n -> count + n));
    }

    private Promise<Integer> applyVersioned(String datasource,
                                            List<ParsedMigration> versioned,
                                            List<AppliedMigration> applied,
                                            SqlConnector connector,
                                            String nodeId) {
        var appliedVersions = applied.stream()
                                     .filter(a -> a.type() == MigrationType.VERSIONED)
                                     .collect(Collectors.toMap(AppliedMigration::version, AppliedMigration::checksum));
        var sorted = versioned.stream().sorted(Comparator.comparingInt(ParsedMigration::version)).toList();

        return validateAndApplyVersioned(datasource, sorted, appliedVersions, connector, nodeId);
    }

    private Promise<Integer> validateAndApplyVersioned(String datasource,
                                                       List<ParsedMigration> sorted,
                                                       Map<Integer, Long> appliedVersions,
                                                       SqlConnector connector,
                                                       String nodeId) {
        var initial = Promise.success(0);

        for (var migration : sorted) {
            initial = initial.flatMap(count -> applyOrValidateVersioned(datasource,
                                                                        migration,
                                                                        appliedVersions,
                                                                        connector,
                                                                        nodeId).map(n -> count + n));
        }

        return initial;
    }

    private Promise<Integer> applyOrValidateVersioned(String datasource,
                                                      ParsedMigration migration,
                                                      Map<Integer, Long> appliedVersions,
                                                      SqlConnector connector,
                                                      String nodeId) {
        var version = migration.version();

        return Option.option(appliedVersions.get(version)).fold(() -> executeResumable(datasource,
                                                                                       migration,
                                                                                       connector,
                                                                                       nodeId),
                                                                checksum -> checksum != migration.entry()
                                                                                                 .checksum()
                                                                            ? checksumMismatch(datasource,
                                                                                               version,
                                                                                               checksum,
                                                                                               migration.entry()
                                                                                                        .checksum()).promise()
                                                                            : Promise.success(0));
    }

    private Promise<Integer> applyRepeatables(List<ParsedMigration> repeatables,
                                              SqlConnector connector,
                                              String nodeId) {
        var initial = Promise.success(0);

        for (var migration : repeatables) {
            initial = initial.flatMap(count -> checkAndApplyRepeatable(migration, connector, nodeId).map(n -> count + n));
        }

        return initial;
    }

    private Promise<Integer> checkAndApplyRepeatable(ParsedMigration migration, SqlConnector connector, String nodeId) {
        return historyRepo.queryRepeatableChecksum(connector,
                                                   migration.description())
                          .flatMap(existing -> shouldApplyRepeatable(existing, migration)
                                               ? executeSingle(migration, connector, nodeId)
                                               : Promise.success(0));
    }

    private static boolean shouldApplyRepeatable(Option<Long> existing, ParsedMigration migration) {
        return existing.fold(() -> true,
                             checksum -> checksum != migration.entry()
                                                              .checksum());
    }

    /// Applies one migration FRESH (no resume), recording its history row at the point that makes
    /// it atomic with the DDL for every transactional execution path:
    /// - the dialect-aware transactional path ([#runInTransaction]) and the legacy naive path
    ///   ([#executeNaive]) fold [#recordExecution] INTO their `transactional(tx -> ...)` block, so
    ///   the DDL and the history row commit as ONE unit — a crash between them rolls both back.
    /// - the autocommit path ([#runAutocommit], MySQL/Oracle, whose DDL self-commits) cannot make
    ///   the two atomic, so it records an `IN_PROGRESS` checkpoint row, advances it per-statement,
    ///   and finalizes it to `SUCCESS` — see [#runAutocommit].
    ///
    /// Used for fresh non-versioned executions (repeatables, synthetic baselines) where no partial
    /// row can pre-exist; the versioned gate uses [#executeResumable] instead.
    private Promise<Integer> executeSingle(ParsedMigration migration, SqlConnector connector, String nodeId) {
        return executeFor("", migration, connector, nodeId, false);
    }

    /// Versioned-gate execution: identical to [#executeSingle] for the transactional path, but for
    /// the autocommit path it consults the [SchemaHistoryRepository#queryProgress] checkpoint to
    /// RESUME a partially-applied migration (skipping the durably-committed statements) instead of
    /// replaying from statement 1. The `datasource` is carried for the checksum-mismatch cause a
    /// changed script raises on resume.
    private Promise<Integer> executeResumable(String datasource,
                                              ParsedMigration migration,
                                              SqlConnector connector,
                                              String nodeId) {
        return executeFor(datasource, migration, connector, nodeId, true);
    }

    private Promise<Integer> executeFor(String datasource,
                                        ParsedMigration migration,
                                        SqlConnector connector,
                                        String nodeId,
                                        boolean resumable) {
        var startNanos = System.nanoTime();
        var sql = migration.entry().sql();

        return MigrationDialects.dialectFor(connector.config().effectiveType())
                                .fold(() -> executeNaive(migration, connector, sql, nodeId, startNanos),
                                      dialect -> executeSplit(datasource,
                                                              migration,
                                                              connector,
                                                              sql,
                                                              dialect,
                                                              nodeId,
                                                              startNanos,
                                                              resumable))
                                .map(_ -> 1);
    }

    /// PostgreSQL-family path: split with the dialect-aware splitter, then run the whole file
    /// either transactionally or in autocommit depending on its statements. A split failure
    /// (e.g. an unterminated `$$` body) surfaces its [org.pragmatica.aether.pg.split.SplitError]
    /// rather than being silently mis-split.
    private Promise<Unit> executeSplit(String datasource,
                                       ParsedMigration migration,
                                       SqlConnector connector,
                                       String sql,
                                       ExecutionDialect dialect,
                                       String nodeId,
                                       long startNanos,
                                       boolean resumable) {
        return StatementSplitter.split(sql,
                                       dialect.spec())
                                .async()
                                .flatMap(statements -> runStatements(datasource,
                                                                     migration,
                                                                     connector,
                                                                     statements,
                                                                     dialect,
                                                                     nodeId,
                                                                     startNanos,
                                                                     resumable));
    }

    /// Flyway-style per-file classification: if any statement is non-transactional (e.g. a
    /// `CREATE INDEX CONCURRENTLY`), the whole file runs in autocommit; otherwise the whole
    /// file runs in a single transaction (today's wrapping semantics for PG-family DDL).
    private Promise<Unit> runStatements(String datasource,
                                        ParsedMigration migration,
                                        SqlConnector connector,
                                        List<Statement> statements,
                                        ExecutionDialect dialect,
                                        String nodeId,
                                        long startNanos,
                                        boolean resumable) {
        var texts = statements.stream().map(Statement::text).toList();
        var anyNonTransactional = statements.stream().anyMatch(s -> !dialect.spec()
                                                                            .isTransactional(s.text()));

        return dialect.ddlTransactional() && !anyNonTransactional
               ? runInTransaction(migration, connector, texts, nodeId, startNanos)
               : runAutocommit(datasource, migration, connector, texts, nodeId, startNanos, resumable);
    }

    /// Transactional path (PG/DB2/SQL Server): the DDL statements AND the history-row insert run
    /// on the SAME transaction connector `tx`, so they commit atomically — a crash after the DDL
    /// but before the history insert rolls BOTH back. A transactional dialect therefore NEVER
    /// leaves a partial (`IN_PROGRESS`/`FAILED`) history row — its resume gate is a no-op and its
    /// semantics are unchanged from Increment A.
    private Promise<Unit> runInTransaction(ParsedMigration migration,
                                           SqlConnector connector,
                                           List<String> statements,
                                           String nodeId,
                                           long startNanos) {
        return connector.transactional(tx -> applyAll(tx, statements).flatMap(_ -> recordExecution(migration,
                                                                                                   tx,
                                                                                                   nodeId,
                                                                                                   startNanos)));
    }

    /// Autocommit, checkpoint-aware path (MySQL/Oracle, whose DDL self-commits so it cannot be made
    /// atomic with the history row). FRESH RUN: write an `IN_PROGRESS` checkpoint row
    /// (`statements_completed = 0`) BEFORE the loop, then for each statement i (0-based) run it on
    /// the base connector — each self-commits — and AFTER it succeeds advance the checkpoint to
    /// `i + 1` (a separate auto-commit, so it advances durably); on full success transition the row
    /// to `SUCCESS` with the complete record (an UPDATE of the same row, never a second INSERT); on
    /// failure mark the row `FAILED` and propagate the [org.pragmatica.lang.Cause] so #118's retry
    /// re-enters and RESUMES.
    ///
    /// RESUME (`resumable` versioned gate): consults [SchemaHistoryRepository#queryProgress]. A
    /// `SUCCESS` row means already applied (validate the stored checksum, skip). An `IN_PROGRESS` or
    /// `FAILED` row with `statements_completed = K` re-validates the stored checksum first (a changed
    /// script is a modified-after-applied error, not a resume — the split is deterministic only for
    /// the unchanged script), then skips the first K statements and runs K+1..N continuing the SAME
    /// checkpoint row. No row means a fresh run.
    ///
    /// STATEMENT/CHECKPOINT ORDERING: a statement is executed and only THEN is its checkpoint
    /// advanced — never the reverse. Skipping an unapplied statement would silently corrupt the
    /// schema; re-attempting an already-applied statement is the safe failure mode.
    ///
    /// INHERENT LIMITATION (shared with Flyway's non-transactional migrations): because an
    /// autocommit statement and its `updateProgress` checkpoint are SEPARATE auto-commits, a crash
    /// in the window BETWEEN a statement's own commit and its checkpoint advance leaves the
    /// checkpoint one short, so on resume that ONE boundary statement is re-attempted. If it is
    /// idempotent (e.g. `CREATE TABLE IF NOT EXISTS`, `ADD COLUMN IF NOT EXISTS`) the re-attempt is
    /// harmless; if it is non-idempotent the re-attempt errors and the migration is marked `FAILED`
    /// for operator intervention. This is inherent to non-transactional migrations — but the rest of
    /// the already-applied statements are still skipped, far better than replaying from statement 1.
    private Promise<Unit> runAutocommit(String datasource,
                                        ParsedMigration migration,
                                        SqlConnector connector,
                                        List<String> statements,
                                        String nodeId,
                                        long startNanos,
                                        boolean resumable) {
        return resumable
               ? historyRepo.queryProgress(connector,
                                           migration.version(),
                                           migration.type())
                            .flatMap(progress -> runAutocommitFrom(datasource,
                                                                   migration,
                                                                   connector,
                                                                   statements,
                                                                   nodeId,
                                                                   startNanos,
                                                                   progress))
               : runAutocommitFresh(migration, connector, statements, nodeId, startNanos);
    }

    /// Routes a versioned autocommit execution by its checkpoint state: no row ⇒ fresh; `SUCCESS` ⇒
    /// already applied (with the stored-checksum validation); `IN_PROGRESS`/`FAILED` ⇒ resume from
    /// the recorded `statements_completed` after the stored-checksum validation.
    private Promise<Unit> runAutocommitFrom(String datasource,
                                            ParsedMigration migration,
                                            SqlConnector connector,
                                            List<String> statements,
                                            String nodeId,
                                            long startNanos,
                                            Option<MigrationProgress> progress) {
        return progress.fold(() -> runAutocommitFresh(migration, connector, statements, nodeId, startNanos),
                             checkpoint -> resumeFromCheckpoint(datasource,
                                                                migration,
                                                                connector,
                                                                statements,
                                                                nodeId,
                                                                startNanos,
                                                                checkpoint));
    }

    private Promise<Unit> resumeFromCheckpoint(String datasource,
                                               ParsedMigration migration,
                                               SqlConnector connector,
                                               List<String> statements,
                                               String nodeId,
                                               long startNanos,
                                               MigrationProgress checkpoint) {
        if (checkpoint.checksum() != migration.entry().checksum()) {
            return checksumMismatch(datasource,
                                    migration.version(),
                                    checkpoint.checksum(),
                                    migration.entry().checksum()).promise();
        }

        return MigrationStatus.SUCCESS.equals(checkpoint.status())
               ? Promise.unitPromise()
               : runAutocommitResume(migration,
                                     connector,
                                     statements,
                                     nodeId,
                                     startNanos,
                                     checkpoint.statementsCompleted());
    }

    /// Fresh autocommit run: record the `IN_PROGRESS` checkpoint row, then apply every statement
    /// from index 0 with the per-statement checkpoint advance, then finalize the row to `SUCCESS`.
    private Promise<Unit> runAutocommitFresh(ParsedMigration migration,
                                             SqlConnector connector,
                                             List<String> statements,
                                             String nodeId,
                                             long startNanos) {
        var record = appliedRecord(migration, nodeId, startNanos);

        return historyRepo.recordInProgress(connector,
                                            record,
                                            statements.size())
                          .flatMap(_ -> runAutocommitResume(migration, connector, statements, nodeId, startNanos, 0));
    }

    /// Applies statements from index `skip` onward (the fresh run passes `skip = 0`), advancing the
    /// checkpoint after each, then finalizes to `SUCCESS`; on any failure marks the row `FAILED` and
    /// propagates the cause so #118's retry re-enters.
    private Promise<Unit> runAutocommitResume(ParsedMigration migration,
                                              SqlConnector connector,
                                              List<String> statements,
                                              String nodeId,
                                              long startNanos,
                                              int skip) {
        return applyCheckpointed(migration, connector, statements, skip).flatMap(_ -> finalizeAutocommit(migration,
                                                                                                         connector,
                                                                                                         nodeId,
                                                                                                         startNanos,
                                                                                                         statements.size()))
                                .fold(result -> markFailedOnError(migration, connector, result));
    }

    /// On a failed autocommit run, mark the checkpoint row `FAILED` and THEN re-propagate the
    /// original cause (so #118's retry re-enters and resumes); a successful run passes through
    /// untouched. The `markStatus` write is chained, not abandoned, so its own Promise is handled.
    private Promise<Unit> markFailedOnError(ParsedMigration migration, SqlConnector connector, Result<Unit> result) {
        return result.fold(cause -> historyRepo.markStatus(connector,
                                                           migration.version(),
                                                           migration.type(),
                                                           MigrationStatus.FAILED)
                                               .flatMap(_ -> cause.promise()),
                           Promise::success);
    }

    private Promise<Unit> applyCheckpointed(ParsedMigration migration,
                                            SqlConnector connector,
                                            List<String> statements,
                                            int skip) {
        var result = Promise.unitPromise();

        for (var index = skip; index < statements.size(); index++) {
            result = result.flatMap(chainCheckpointStep(migration, connector, statements.get(index), index));
        }

        return result;
    }

    private Fn1<Promise<Unit>, Unit> chainCheckpointStep(ParsedMigration migration,
                                                         SqlConnector connector,
                                                         String statement,
                                                         int index) {
        return _ -> connector.update(statement)
                             .flatMap(_ -> historyRepo.updateProgress(connector,
                                                                      migration.version(),
                                                                      migration.type(),
                                                                      index + 1));
    }

    private Promise<Unit> finalizeAutocommit(ParsedMigration migration,
                                             SqlConnector connector,
                                             String nodeId,
                                             long startNanos,
                                             int totalStatements) {
        return historyRepo.finalizeSuccess(connector, appliedRecord(migration, nodeId, startNanos), totalStatements);
    }

    private static AppliedMigration appliedRecord(ParsedMigration migration, String nodeId, long startNanos) {
        var elapsedMs = (int)((System.nanoTime() - startNanos) / 1_000_000);

        return appliedMigration(migration.version(),
                                migration.type(),
                                migration.description(),
                                migration.entry().filename(),
                                migration.entry().checksum(),
                                nodeId,
                                System.currentTimeMillis(),
                                elapsedMs);
    }

    /// Legacy path for unwired (non-PostgreSQL-family) dialects: preserves the exact prior
    /// execution behavior — strip line comments, naive `split(";")`, all inside one transaction —
    /// and folds the history-row insert into that same transaction so DDL and history are atomic.
    private Promise<Unit> executeNaive(ParsedMigration migration,
                                       SqlConnector connector,
                                       String sql,
                                       String nodeId,
                                       long startNanos) {
        return connector.transactional(tx -> executeStatementsNaive(tx, sql).flatMap(_ -> recordExecution(migration,
                                                                                                          tx,
                                                                                                          nodeId,
                                                                                                          startNanos)));
    }

    private static Promise<Unit> executeStatementsNaive(SqlConnector tx, String sql) {
        var stripped = Arrays.stream(sql.split("\n"))
                             .filter(line -> !line.strip()
                                                  .startsWith("--"))
                             .collect(Collectors.joining("\n"));
        var statements = Arrays.stream(stripped.split(";")).map(String::strip).filter(s -> !s.isEmpty()).toList();

        return applyAll(tx, statements);
    }

    private static Promise<Unit> applyAll(SqlConnector executor, List<String> statements) {
        var result = Promise.unitPromise();

        for (var stmt : statements) {
            result = result.flatMap(_ -> executor.update(stmt)
                                                 .mapToUnit());
        }

        return result;
    }

    private Promise<Unit> recordExecution(ParsedMigration migration,
                                          SqlConnector connector,
                                          String nodeId,
                                          long startNanos) {
        return historyRepo.recordMigration(connector, appliedRecord(migration, nodeId, startNanos));
    }

    private Promise<SchemaResult> executeUndo(String datasource,
                                              int targetVersion,
                                              List<ParsedMigration> parsed,
                                              List<AppliedMigration> applied,
                                              SqlConnector connector,
                                              String nodeId) {
        var undoScripts = parsed.stream()
                                .filter(p -> p.type() == MigrationType.UNDO)
                                .collect(Collectors.toMap(ParsedMigration::version, p -> p));
        var toUndo = applied.stream()
                            .filter(a -> a.type() == MigrationType.VERSIONED)
                            .filter(a -> a.version() > targetVersion)
                            .sorted(Comparator.comparingInt(AppliedMigration::version).reversed())
                            .toList();
        var startNanos = System.nanoTime();

        return executeUndoSequence(datasource, toUndo, undoScripts, connector, nodeId).map(count -> buildUndoResult(count,
                                                                                                                    targetVersion,
                                                                                                                    startNanos));
    }

    private Promise<Integer> executeUndoSequence(String datasource,
                                                 List<AppliedMigration> toUndo,
                                                 Map<Integer, ParsedMigration> undoScripts,
                                                 SqlConnector connector,
                                                 String nodeId) {
        var initial = Promise.success(0);

        for (var applied : toUndo) {
            initial = initial.flatMap(count -> Option.option(undoScripts.get(applied.version()))
                                                     .async(undoNotAvailable(datasource,
                                                                             applied.version()))
                                                     .flatMap(script -> executeUndoStep(script,
                                                                                        applied.version(),
                                                                                        connector,
                                                                                        nodeId))
                                                     .map(n -> count + n));
        }

        return initial;
    }

    private Promise<Integer> executeUndoStep(ParsedMigration undoScript,
                                             int version,
                                             SqlConnector connector,
                                             String nodeId) {
        return connector.transactional(tx -> tx.update(undoScript.entry().sql())
                                               .mapToUnit())
                        .flatMap(_ -> historyRepo.removeMigration(connector, version, MigrationType.VERSIONED))
                        .map(_ -> 1);
    }

    private Promise<SchemaResult> executeBaseline(String datasource,
                                                  int baselineVersion,
                                                  List<ParsedMigration> parsed,
                                                  List<AppliedMigration> applied,
                                                  SqlConnector connector,
                                                  String nodeId) {
        var existingVersioned = applied.stream().filter(a -> a.type() == MigrationType.VERSIONED).toList();

        if (!existingVersioned.isEmpty()) {
            var maxApplied = existingVersioned.stream().mapToInt(AppliedMigration::version).max().orElse(0);

            return baselineConflict(datasource, maxApplied).promise();
        }

        var startNanos = System.nanoTime();

        return recordSyntheticBaselines(baselineVersion, connector, nodeId).map(count -> buildBaselineResult(count,
                                                                                                             baselineVersion,
                                                                                                             startNanos));
    }

    private static SchemaResult buildResult(int appliedCount,
                                            List<ParsedMigration> versioned,
                                            List<AppliedMigration> applied,
                                            long startNanos) {
        var maxVersionedScript = versioned.stream().mapToInt(ParsedMigration::version).max().orElse(0);
        var maxAppliedVersion = applied.stream()
                                       .filter(a -> a.type() == MigrationType.VERSIONED)
                                       .mapToInt(AppliedMigration::version)
                                       .max()
                                       .orElse(0);
        var currentVersion = Math.max(maxVersionedScript, maxAppliedVersion);

        return SchemaResult.schemaResult(appliedCount, currentVersion, elapsedMs(startNanos));
    }

    private static SchemaResult buildUndoResult(int appliedCount, int targetVersion, long startNanos) {
        return SchemaResult.schemaResult(appliedCount, targetVersion, elapsedMs(startNanos));
    }

    private static SchemaResult buildBaselineResult(int appliedCount, int baselineVersion, long startNanos) {
        return SchemaResult.schemaResult(appliedCount, baselineVersion, elapsedMs(startNanos));
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static Map<MigrationType, List<ParsedMigration>> groupByType(List<ParsedMigration> parsed) {
        return parsed.stream()
                     .collect(Collectors.groupingBy(ParsedMigration::type));
    }
}
