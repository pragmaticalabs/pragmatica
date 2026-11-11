// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.db.migration;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.pragmatica.db.migration.MigrationDialects.ExecutionDialect;
import org.pragmatica.db.migration.ParsedMigration.MigrationType;
import org.pragmatica.db.migration.AutocommitStatement.ConcurrentBuild;
import org.pragmatica.db.migration.SchemaHistoryRepository.AppliedMigration;
import org.pragmatica.db.migration.SchemaHistoryRepository.MigrationProgress;
import org.pragmatica.db.migration.SchemaHistoryRepository.MigrationStatus;
import org.pragmatica.aether.pg.split.Statement;
import org.pragmatica.aether.pg.split.StatementSplitter;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.db.migration.MigrationError.BaselineConflict.baselineConflict;
import static org.pragmatica.db.migration.MigrationError.ChecksumMismatch.checksumMismatch;
import static org.pragmatica.db.migration.MigrationError.InvalidIndexLeftBehind.invalidIndexLeftBehind;
import static org.pragmatica.db.migration.MigrationError.NonTransactionalScriptUnfenced.nonTransactionalScriptUnfenced;
import static org.pragmatica.db.migration.MigrationError.UndoNotAvailable.undoNotAvailable;
import static org.pragmatica.db.migration.SchemaHistoryRepository.AppliedMigration.appliedMigration;


public interface SchemaMigrations {
    /// `owner` is the blueprint whose artifact declared these scripts. Every entry point claims
    /// migration ownership of the PHYSICAL database
    /// ([SchemaHistoryRepository#claimOwnership]) right after bootstrap and before reading applied
    /// history, so a database already claimed by a different blueprint is refused with
    /// [MigrationError.PhysicalDatasourceOwnershipConflict] having written nothing.
    Promise<SchemaResult> migrate(String datasource,
                                  List<MigrationScript> scripts,
                                  SqlConnector connector,
                                  String nodeId,
                                  String owner);

    Promise<SchemaResult> undo(String datasource,
                               int targetVersion,
                               List<MigrationScript> scripts,
                               SqlConnector connector,
                               String nodeId,
                               String owner);

    Promise<SchemaResult> baseline(String datasource,
                                   int baselineVersion,
                                   List<MigrationScript> scripts,
                                   SqlConnector connector,
                                   String nodeId,
                                   String owner);

    /// `statementTimeout` bounds every statement on the migration's pinned database session (FDB-2a, #2591); the
    /// caller passes its own per-attempt migration timeout, so no single statement outlives the attempt.
    static SchemaMigrations schemaMigrations(TimeSpan statementTimeout) {
        return new DefaultSchemaMigrations(SchemaHistoryRepository.schemaHistoryRepository(), statementTimeout);
    }

    record SchemaResult(int appliedCount, int currentVersion, long totalMs) {
        public static SchemaResult schemaResult(int appliedCount, int currentVersion, long totalMs) {
            return new SchemaResult(appliedCount, currentVersion, totalMs);
        }
    }
}

final class DefaultSchemaMigrations implements SchemaMigrations {
    private final SchemaHistoryRepository historyRepo;
    private final TimeSpan statementTimeout;

    DefaultSchemaMigrations(SchemaHistoryRepository historyRepo, TimeSpan statementTimeout) {
        this.historyRepo = historyRepo;
        this.statementTimeout = statementTimeout;
    }

    /// Claim ownership of the physical database IMMEDIATELY after bootstrap and BEFORE
    /// [SchemaHistoryRepository#queryApplied], so a refused claim short-circuits with no history read
    /// and no migration applied. All three entry points run under the database fence ([#fenced]).
    @Override
    public Promise<SchemaResult> migrate(String datasource,
                                         List<MigrationScript> scripts,
                                         SqlConnector connector,
                                         String nodeId,
                                         String owner) {
        return parseAll(scripts).async()
                       .flatMap(parsed -> fenced(datasource,
                                                 connector,
                                                 nodeId,
                                                 session -> migrateOn(datasource, parsed, session, nodeId, owner)));
    }

    @Override
    public Promise<SchemaResult> undo(String datasource,
                                      int targetVersion,
                                      List<MigrationScript> scripts,
                                      SqlConnector connector,
                                      String nodeId,
                                      String owner) {
        return parseAll(scripts).async()
                       .flatMap(parsed -> fenced(datasource,
                                                 connector,
                                                 nodeId,
                                                 session -> undoOn(datasource,
                                                                   targetVersion,
                                                                   parsed,
                                                                   session,
                                                                   nodeId,
                                                                   owner)));
    }

    @Override
    public Promise<SchemaResult> baseline(String datasource,
                                          int baselineVersion,
                                          List<MigrationScript> scripts,
                                          SqlConnector connector,
                                          String nodeId,
                                          String owner) {
        return parseAll(scripts).async()
                       .flatMap(parsed -> fenced(datasource,
                                                 connector,
                                                 nodeId,
                                                 session -> baselineOn(datasource,
                                                                       baselineVersion,
                                                                       parsed,
                                                                       session,
                                                                       nodeId,
                                                                       owner)));
    }

    /// The database fence (FDB-2a, #2591; design §1, §2.3). On a tier-1 engine ([MigrationFence#isSessionFenced]) the whole
    /// run happens on ONE dedicated session ([SqlConnector#pinned], bounded by the statement timeout): the fence is
    /// acquired first, and only then are bootstrap, the ownership claim, the history read and every statement run, all
    /// on that session ([FencedSession], which re-verifies the fence before each write). Leaving the scope closes the
    /// session, which is what releases the fence; [MigrationFence#release(SqlConnector)] before it is a courtesy. Another holder ends
    /// the attempt with [MigrationError.MigrationFencedInDatabase] having run and read nothing.
    ///
    /// Tier 2 (SQL Server, Oracle) and H2 take the same route. The tier-3 engines (CockroachDB, DB2, SQLite) have no
    /// session lock: they run on the caller's connector and carry their per-script lock inside the [FencedSession]
    /// ([#scriptLockOf]), taken as the first statement of each script's transaction (FDB-2b).
    private <T> Promise<T> fenced(String datasource,
                                  SqlConnector connector,
                                  String nodeId,
                                  SqlConnector.TransactionCallback<T> body) {
        return MigrationFence.isSessionFenced(connector.databaseType())
               ? connector.pinned(statementTimeout.duration(),
                                  session -> acquireThen(datasource, session, nodeId, body),
                                  DefaultSchemaMigrations::closeIsTheRelease)
                          .mapError(cause -> MigrationFence.refusedAtConnect(datasource,
                                                                             connector.databaseType(),
                                                                             cause))
               : acquireThen(datasource, connector, nodeId, body);
    }

    /// The per-script lock of a tier-3 run; none on a session-fenced run and on a bare connector.
    private static Option<MigrationFence.ScriptLock> scriptLockOf(SqlConnector connector) {
        return switch (connector) {
            case FencedSession fenced -> fenced.fence()
                                               .scriptLock();
            default -> Option.none();
        };
    }

    private static <T> Promise<T> acquireThen(String datasource,
                                              SqlConnector session,
                                              String nodeId,
                                              SqlConnector.TransactionCallback<T> body) {
        return MigrationFence.acquire(session, datasource, nodeId).flatMap(fence -> body.execute(new FencedSession(session,
                                                                                                                   fence))
                                                                                        .fold(outcome -> releaseIfStillHeld(fence,
                                                                                                                            session,
                                                                                                                            outcome)));
    }

    /// The courtesy unlock runs only for a run that TOOK the fence and did not lose it. A refused acquire (another
    /// holder, or a transaction pooler's shared server session) and a run refused mid-way ([MigrationError.FenceUnavailable]
    /// from a re-verification) have no lock of their own on this server session: `pg_advisory_unlock_all()` /
    /// `RELEASE_ALL_LOCKS()` there would drop the lock of the client that does hold it, when a pooler shares the session
    /// (CTO ruling, FDB-2a fix round 1). Design-out of the unlock's own failure: closing the session is the release, so
    /// a failed courtesy unlock never changes the run's outcome.
    private static <T> Promise<T> releaseIfStillHeld(MigrationFence fence,
                                                         SqlConnector session,
                                                         Result<T> outcome) {
        return lostTheFence(outcome)
               ? outcome.async()
               : fence.release(session)
                      .fold(_ -> outcome.async());
    }

    private static boolean lostTheFence(Result<?> outcome) {
        return outcome.fold(cause -> cause instanceof MigrationError.FenceUnavailable, _ -> false);
    }

    /// The pinned scope's own release step: nothing beyond closing the session ([#releaseIfStillHeld] already ran the
    /// courtesy unlock where one is safe).
    private static Promise<Unit> closeIsTheRelease(SqlConnector session) {
        return Promise.unitPromise();
    }

    private Promise<SchemaResult> migrateOn(String datasource,
                                            List<ParsedMigration> parsed,
                                            SqlConnector session,
                                            String nodeId,
                                            String owner) {
        return claimAndReadHistory(datasource, session, owner).flatMap(applied -> executeMigration(datasource,
                                                                                                   parsed,
                                                                                                   applied,
                                                                                                   session,
                                                                                                   nodeId));
    }

    private Promise<SchemaResult> undoOn(String datasource,
                                         int targetVersion,
                                         List<ParsedMigration> parsed,
                                         SqlConnector session,
                                         String nodeId,
                                         String owner) {
        return claimAndReadHistory(datasource, session, owner).flatMap(applied -> executeUndo(datasource,
                                                                                              targetVersion,
                                                                                              parsed,
                                                                                              applied,
                                                                                              session,
                                                                                              nodeId));
    }

    private Promise<SchemaResult> baselineOn(String datasource,
                                             int baselineVersion,
                                             List<ParsedMigration> parsed,
                                             SqlConnector session,
                                             String nodeId,
                                             String owner) {
        return claimAndReadHistory(datasource, session, owner).flatMap(applied -> executeBaseline(datasource,
                                                                                                  baselineVersion,
                                                                                                  parsed,
                                                                                                  applied,
                                                                                                  session,
                                                                                                  nodeId));
    }

    /// Bootstrap, then the ownership claim, then the applied-history read: on a fenced session all three run only
    /// after the fence is held (design §1: history is read ONLY after acquire).
    private Promise<List<AppliedMigration>> claimAndReadHistory(String datasource, SqlConnector session, String owner) {
        return historyRepo.bootstrap(session)
                          .flatMap(_ -> historyRepo.claimOwnership(session, datasource, owner))
                          .flatMap(_ -> historyRepo.queryApplied(session));
    }

    private static Result<List<ParsedMigration>> parseAll(List<MigrationScript> scripts) {
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

        return MigrationDialects.dialectFor(connector.databaseType())
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
               : tierThreeRefusal(migration, connector, statements, dialect).fold(() -> runAutocommit(datasource,
                                                                                                      migration,
                                                                                                      connector,
                                                                                                      autocommitStatements(statements,
                                                                                                                           dialect),
                                                                                                      nodeId,
                                                                                                      startNanos,
                                                                                                      resumable),
                                                                                  Cause::promise);
    }

    /// FDB-2b (design §2.3): a tier-3 script is fenced only while it runs in ONE transaction, so one holding a
    /// statement that cannot run in a transaction is refused before its first statement.
    private static Option<Cause> tierThreeRefusal(ParsedMigration migration,
                                                  SqlConnector connector,
                                                  List<Statement> statements,
                                                  ExecutionDialect dialect) {
        return scriptLockOf(connector).flatMap(lock -> firstNonTransactional(statements,
                                                                             dialect).map(text -> refusal(lock,
                                                                                                          migration,
                                                                                                          text)));
    }

    private static Cause refusal(MigrationFence.ScriptLock lock, ParsedMigration migration, String statement) {
        return nonTransactionalScriptUnfenced(lock.engine(),
                                              migration.entry()
                                                       .filename(),
                                              statement);
    }

    private static Option<String> firstNonTransactional(List<Statement> statements, ExecutionDialect dialect) {
        return Option.from(statements.stream()
                                     .map(Statement::text)
                                     .filter(text -> !dialect.spec()
                                                             .isTransactional(text))
                                     .findFirst());
    }

    private static List<AutocommitStatement> autocommitStatements(List<Statement> statements,
                                                                  ExecutionDialect dialect) {
        return statements.stream()
                         .map(statement -> AutocommitStatement.autocommitStatement(statement.text(),
                                                                                   dialect))
                         .toList();
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
        return transactionally(migration,
                               connector,
                               tx -> applyAll(tx, statements).flatMap(_ -> recordExecution(migration,
                                                                                           tx,
                                                                                           nodeId,
                                                                                           startNanos)));
    }

    /// One script's transaction. On a tier-3 engine its FIRST statement is the per-script lock, and under it the
    /// script's history row is read again: a serialized second runner finds the row and skips, completing without a
    /// statement (design §2.3, H1). Every other engine runs `body` in a plain transaction.
    private Promise<Unit> transactionally(ParsedMigration migration,
                                          SqlConnector connector,
                                          SqlConnector.TransactionCallback<Unit> body) {
        return scriptLockOf(connector).fold(() -> connector.transactional(body),
                                            lock -> connector.transactional(tx -> lock.take(tx)
                                                                                      .flatMap(_ -> skipIfApplied(migration,
                                                                                                                  tx,
                                                                                                                  body))));
    }

    private Promise<Unit> skipIfApplied(ParsedMigration migration,
                                        SqlConnector tx,
                                        SqlConnector.TransactionCallback<Unit> body) {
        return appliedAlready(migration, tx).flatMap(applied -> applied
                                                               ? Promise.unitPromise()
                                                               : body.execute(tx));
    }

    /// The history row, as the lock holder sees it now: applied means a `SUCCESS` row with this script's checksum
    /// (a repeatable: the stored checksum of its description). A row with another checksum is NOT a skip: the insert
    /// meets it and ends [MigrationError.HistoryRowCollision].
    private Promise<Boolean> appliedAlready(ParsedMigration migration, SqlConnector tx) {
        var checksum = migration.entry()
                                .checksum();

        return migration.type() == MigrationType.REPEATABLE
               ? historyRepo.queryRepeatableChecksum(tx,
                                                     migration.description())
                            .map(stored -> stored.fold(() -> false, current -> current == checksum))
               : historyRepo.queryProgress(tx,
                                           migration.version(),
                                           migration.type())
                            .map(progress -> progress.fold(() -> false,
                                                           row -> row.status()
                                                                     .equals(MigrationStatus.SUCCESS) && row.checksum() == checksum));
    }

    /// Autocommit, checkpoint-aware path (MySQL/Oracle, whose DDL self-commits so it cannot be made
    /// atomic with the history row). FRESH RUN: write an `IN_PROGRESS` checkpoint row
    /// (`statements_completed = 0`) BEFORE the loop, then for each statement i (0-based) run it on
    /// the migration's session and advance the checkpoint to `i + 1` — in the statement's own
    /// transaction when the dialect allows one, else as a separate auto-commit right after it (see
    /// BOUNDARY below); on full success transition the row
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
    /// BOUNDARY (FDB-2a, design §2.4): a statement the dialect can run in a transaction commits in ONE
    /// transaction with its checkpoint advance, so it is applied exactly once across holder deaths
    /// ([#runWithCheckpointInTransaction]). A statement that cannot (`CREATE INDEX CONCURRENTLY`, and
    /// every DDL on engines whose DDL commits implicitly) still commits separately from its checkpoint:
    /// a crash between the two leaves the checkpoint one short, so the next holder re-attempts that ONE
    /// statement, sequentially (the database fence forbids a concurrent second runner). An idempotent
    /// statement (`IF NOT EXISTS`) makes that harmless; a non-idempotent one errors and the migration is
    /// marked `FAILED` for the operator; an error-free non-idempotent one (an unnamed index) is the
    /// named residual.
    private Promise<Unit> runAutocommit(String datasource,
                                        ParsedMigration migration,
                                        SqlConnector connector,
                                        List<AutocommitStatement> statements,
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
                                            List<AutocommitStatement> statements,
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
                                               List<AutocommitStatement> statements,
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
                                             List<AutocommitStatement> statements,
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
                                              List<AutocommitStatement> statements,
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
                                            List<AutocommitStatement> statements,
                                            int skip) {
        var result = Promise.unitPromise();

        for (var index = skip; index < statements.size(); index++) {
            result = result.flatMap(chainCheckpointStep(migration, connector, statements.get(index), index));
        }

        return result;
    }

    private Fn1<Promise<Unit>, Unit> chainCheckpointStep(ParsedMigration migration,
                                                         SqlConnector connector,
                                                         AutocommitStatement statement,
                                                         int index) {
        return _ -> runCheckpointed(migration, connector, statement, index + 1);
    }

    private Promise<Unit> runCheckpointed(ParsedMigration migration,
                                          SqlConnector connector,
                                          AutocommitStatement statement,
                                          int completed) {
        return statement.transactional()
               ? runWithCheckpointInTransaction(migration, connector, statement, completed)
               : runThenCheckpoint(migration, connector, statement, completed);
    }

    /// H3 (FDB-2a, design §2.4): a statement the dialect can run in a transaction (DML; on PostgreSQL also ordinary
    /// DDL) commits in ONE transaction with its checkpoint, so across any number of holder deaths it is applied exactly
    /// once: a death before the commit rolls both back and the next holder re-runs it; a death after leaves the
    /// checkpoint past it. Engines whose DDL commits implicitly (MySQL, Oracle) still commit such DDL on its own, so
    /// for them DDL keeps the at-most-twice guarantee of the autocommit statement below.
    private Promise<Unit> runWithCheckpointInTransaction(ParsedMigration migration,
                                                         SqlConnector connector,
                                                         AutocommitStatement statement,
                                                         int completed) {
        return connector.transactional(tx -> updateThenCheckpoint(migration, tx, statement, completed));
    }

    private Promise<Unit> updateThenCheckpoint(ParsedMigration migration,
                                               SqlConnector tx,
                                               AutocommitStatement statement,
                                               int completed) {
        return tx.update(statement.text())
                 .flatMap(_ -> historyRepo.updateProgress(tx,
                                                          migration.version(),
                                                          migration.type(),
                                                          completed));
    }

    /// A statement that cannot run in a transaction (`CREATE INDEX CONCURRENTLY` and its kin) runs in autocommit and
    /// only THEN advances the checkpoint; under the fence it executes at most twice, sequentially, never concurrently
    /// (design §2.4). After a concurrent index build, the F8 check runs BEFORE the checkpoint moves past it.
    private Promise<Unit> runThenCheckpoint(ParsedMigration migration,
                                            SqlConnector connector,
                                            AutocommitStatement statement,
                                            int completed) {
        return connector.update(statement.text())
                        .fold(outcome -> outcome.fold(cause -> explainFailedBuild(migration, connector, statement, cause),
                                                      _ -> Promise.unitPromise()))
                        .flatMap(_ -> requireValidIndex(migration, connector, statement))
                        .flatMap(_ -> historyRepo.updateProgress(connector,
                                                                 migration.version(),
                                                                 migration.type(),
                                                                 completed));
    }

    /// F8 (design §2.4): a `CREATE INDEX CONCURRENTLY` cancelled by the statement timeout, or killed with its session,
    /// leaves an INVALID index, and a resumed `IF NOT EXISTS` passes over it silently. So after every such statement,
    /// fresh or resumed, an invalid index of that name stops the run with [MigrationError.InvalidIndexLeftBehind]. An
    /// unnamed concurrent index cannot be checked by name (the design's named residual: name every index).
    private static Promise<Unit> requireValidIndex(ParsedMigration migration,
                                                   SqlConnector connector,
                                                   AutocommitStatement statement) {
        return statement.concurrentBuild()
                        .fold(Promise::unitPromise,
                              build -> checkIndexValid(migration, connector, build));
    }

    private static Promise<Unit> checkIndexValid(ParsedMigration migration,
                                                 SqlConnector connector,
                                                 ConcurrentBuild build) {
        return invalidCount(connector, build).flatMap(invalid -> refuseInvalid(migration, build.index(), invalid));
    }

    /// F8 for a FAILED concurrent build too: one cancelled by the statement or lock bound, or a resumed build without
    /// `IF NOT EXISTS` that meets the INVALID leftover ("already exists"). When the named index is INVALID the run stops
    /// with [MigrationError.InvalidIndexLeftBehind] (its name and the DROP lever) instead of the raw error.
    ///
    /// Design-out of the check's own failure: the statement has already failed, so the run fails either way; a check
    /// that cannot run (a dead or cancelled session) only loses the better explanation, and the statement's original
    /// cause stands (the absorbed check failure is read as "no INVALID index found").
    private static Promise<Unit> explainFailedBuild(ParsedMigration migration,
                                                    SqlConnector connector,
                                                    AutocommitStatement statement,
                                                    Cause cause) {
        return statement.concurrentBuild()
                        .fold(cause::<Unit> promise,
                              build -> invalidCount(connector, build).fold(counted -> invalidOr(migration,
                                                                                                build.index(),
                                                                                                counted.or(0),
                                                                                                cause)));
    }

    private static Promise<Unit> invalidOr(ParsedMigration migration, String index, int invalid, Cause cause) {
        return invalid == 0
               ? cause.promise()
               : invalidIndexLeftBehind(migration.entry().filename(),
                                        index).promise();
    }

    /// INVALID indexes of this name ON THIS BUILD'S TABLE: an index name is unique per schema, not per database, so a
    /// same-named index in another schema is not this build's. A table `to_regclass` cannot resolve (dropped, or not
    /// visible to the runner) falls back to the name alone, which can only over-report: fail-safe.
    private static Promise<Integer> invalidCount(SqlConnector connector, ConcurrentBuild build) {
        return connector.queryOne("SELECT count(*)::int AS n FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid"
                                 + " WHERE c.relname = ? AND NOT i.indisvalid"
                                 + " AND (to_regclass(?) IS NULL OR i.indrelid = to_regclass(?))",
                                  row -> row.getInt("n"),
                                  build.index(),
                                  build.table(),
                                  build.table());
    }

    private static Promise<Unit> refuseInvalid(ParsedMigration migration, String index, int invalid) {
        return invalid == 0
               ? Promise.unitPromise()
               : invalidIndexLeftBehind(migration.entry().filename(),
                                        index).promise();
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
        var statements = naiveStatements(sql);

        return naiveRefusal(migration, connector, statements).fold(() -> transactionally(migration,
                                                                                         connector,
                                                                                         tx -> applyAll(tx,
                                                                                                        statements).flatMap(_ -> recordExecution(migration,
                                                                                                                                                 tx,
                                                                                                                                                 nodeId,
                                                                                                                                                 startNanos))),
                                                                   Cause::promise);
    }

    /// SQLite (tier 3, naive path): `VACUUM` cannot run inside a transaction, so a script holding it is refused.
    /// `[marked guess: VACUUM is the statement the SQLite path can name as non-transactional]`
    private static Option<Cause> naiveRefusal(ParsedMigration migration, SqlConnector connector, List<String> statements) {
        return scriptLockOf(connector).flatMap(lock -> Option.from(statements.stream()
                                                                             .filter(text -> text.toUpperCase(Locale.ROOT)
                                                                                                 .startsWith("VACUUM"))
                                                                             .findFirst())
                                                             .map(text -> refusal(lock, migration, text)));
    }

    private static List<String> naiveStatements(String sql) {
        var stripped = Arrays.stream(sql.split("\n"))
                             .filter(line -> !line.strip()
                                                  .startsWith("--"))
                             .collect(Collectors.joining("\n"));

        return Arrays.stream(stripped.split(";")).map(String::strip).filter(s -> !s.isEmpty()).toList();
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
        return scriptLockOf(connector).fold(() -> connector.transactional(tx -> undoAndForget(undoScript, version, tx)),
                                            lock -> connector.transactional(tx -> lock.take(tx)
                                                                                      .flatMap(_ -> undoIfRecorded(undoScript,
                                                                                                                   version,
                                                                                                                   tx))))
                            .map(_ -> 1);
    }

    /// FDB-2b (design §0.5): the undo script and the removal of its history row commit as ONE unit, so a crash
    /// between them leaves neither or both.
    private Promise<Unit> undoAndForget(ParsedMigration undoScript, int version, SqlConnector tx) {
        return tx.update(undoScript.entry()
                                   .sql())
                 .flatMap(_ -> historyRepo.removeMigration(tx, version, MigrationType.VERSIONED));
    }

    /// Tier 3: under the lock the row is read again; a serialized second runner finds it already gone and skips.
    private Promise<Unit> undoIfRecorded(ParsedMigration undoScript, int version, SqlConnector tx) {
        return historyRepo.queryProgress(tx, version, MigrationType.VERSIONED)
                          .flatMap(row -> row.fold(Promise::unitPromise, _ -> undoAndForget(undoScript, version, tx)));
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
