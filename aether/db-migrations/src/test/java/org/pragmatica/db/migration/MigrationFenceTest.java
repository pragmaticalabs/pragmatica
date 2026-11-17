// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.db.migration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.DatabaseType;
import org.pragmatica.aether.resource.db.PoolConfig;
import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// FDB-2a (#2591): the fence's decisions that need no live database: `verifyHeld` refuses a lock that acquired
/// nothing (M2), the engine probe, the §2.6 topology refusals, the MySQL lock name, the history read happening only
/// after the fence is verified (M3), the scope of tier 1, the per-write verification, and the autocommit statement
/// classification (H3, F8). The live-database properties are in [MigrationFencePgTest] and [MigrationFenceMysqlTest].
class MigrationFenceTest {
    @Nested
    class Acquire {
        /// M2: a lock call that returns "granted" while this session holds nothing (CockroachDB's no-op advisory lock,
        /// a transaction pooler that swapped the session) is refused before the first write.
        @Test
        void acquire_postgresLockGrantedButNotHeldByThisSession_isFenceUnavailable() {
            var session = new ScriptedSession(DatabaseType.POSTGRESQL, Map.of("AS n FROM pg_locks", row("n", 0)));

            var cause = causeOf(MigrationFence.acquire(session, "ds", "node-1").await());

            assertThat(cause).isInstanceOf(MigrationError.FenceUnavailable.class);
        }

        @Test
        void acquire_postgresLockGrantedAndHeld_succeedsAndNamesTheSession() {
            var session = new ScriptedSession(DatabaseType.POSTGRESQL, Map.of());

            assertThat(MigrationFence.acquire(session, "ds", "node-1").await().isSuccess()).isTrue();
            assertThat(session.params).contains("aether-mig:node-1");
        }

        @Test
        void acquire_postgresLockNotGranted_isFencedNamingTheHolder() {
            var session = new ScriptedSession(DatabaseType.POSTGRESQL,
                                              Map.of("pg_try_advisory_lock", row("granted", 0),
                                                     "AS holder", row("holder", "pid=7, application_name=aether-mig:node-0")));

            var cause = causeOf(MigrationFence.acquire(session, "ds", "node-1").await());

            assertThat(cause).isInstanceOf(MigrationError.MigrationFencedInDatabase.class);
            assertThat(cause.message()).contains("pid=7");
        }

        /// The engine is probed, not trusted: CockroachDB configured as PostgreSQL gets no advisory lock but the tier-3
        /// per-script lock (FDB-2b, mutant M7: without the probe the PostgreSQL fence is taken).
        @Test
        void acquire_cockroachBehindAPostgresConfig_takesTheTierThreeLockNotAnAdvisoryLock() {
            var session = new ScriptedSession(DatabaseType.POSTGRESQL, Map.of("version()", row("v", "CockroachDB CCL v23.1.11")));

            var fence = MigrationFence.acquire(session, "ds", "node-1").await().unwrap();

            assertThat(session.statements).noneMatch(sql -> sql.contains("pg_try_advisory_lock"));
            assertThat(fence.scriptLock().map(MigrationFence.ScriptLock::statement)).isEqualTo(some("SELECT 1 FROM aether_schema_owner FOR UPDATE"));
        }

        /// FDB-2a fix round 1 (A1): `pg_try_advisory_lock` is re-entrant, so a server session that already holds the
        /// fence (a transaction-mode pooler handing one backend to two runners) would be granted it again. The grant
        /// statement reports that case as -1 and the runner refuses it, naming the pooler.
        @Test
        void acquire_postgresServerSessionAlreadyHoldsTheFence_isFenceUnavailableNamingThePooler() {
            var session = new ScriptedSession(DatabaseType.POSTGRESQL, Map.of("pg_try_advisory_lock", row("granted", -1)));

            var cause = causeOf(MigrationFence.acquire(session, "ds", "node-1").await());

            assertThat(cause).isInstanceOf(MigrationError.FenceUnavailable.class);
            assertThat(cause.message()).contains("already held the fence before taking it").contains("pooler");
        }

        /// The MySQL half of the same: `GET_LOCK` is re-entrant too (5.7+), so `IS_USED_LOCK = CONNECTION_ID()` before
        /// the grant reports -1 and the runner refuses.
        @Test
        void acquire_mysqlServerSessionAlreadyHoldsTheFence_isFenceUnavailableNamingThePooler() {
            var session = new ScriptedSession(DatabaseType.MYSQL, Map.of("GET_LOCK(", row("granted", -1)));

            var cause = causeOf(MigrationFence.acquire(session, "ds", "node-1").await());

            assertThat(cause).isInstanceOf(MigrationError.FenceUnavailable.class);
            assertThat(cause.message()).contains("already held the fence before taking it").contains("pooler");
        }

        /// FDB-2a ruling: CockroachDB under a PostgreSQL config is the one engine that runs unfenced, and the
        /// orchestrator asks this probe so it can warn the operator.
        @Test
        void runsUnfenced_cockroachBehindAPostgresConfig_isTrue() {
            var session = new ScriptedSession(DatabaseType.POSTGRESQL, Map.of("version()", row("v", "CockroachDB CCL v23.1.11")));

            assertThat(MigrationFence.runsUnfenced(session).await().unwrap()).isTrue();
        }

        @Test
        void runsUnfenced_postgres_isFalse() {
            var session = new ScriptedSession(DatabaseType.POSTGRESQL, Map.of());

            assertThat(MigrationFence.runsUnfenced(session).await().unwrap()).isFalse();
        }

        @Test
        void runsUnfenced_mysql_isFalseWithoutAQuery() {
            var session = new ScriptedSession(DatabaseType.MYSQL, Map.of());

            assertThat(MigrationFence.runsUnfenced(session).await().unwrap()).isFalse();
            assertThat(session.statements).isEmpty();
        }

        @Test
        void acquire_mysqlGalera_isRefused() {
            var session = new ScriptedSession(DatabaseType.MYSQL, Map.of(), List.of(variable("wsrep_on", "ON")));

            assertRefused(session, "Galera");
        }

        @Test
        void acquire_mysqlMultiPrimaryGroupReplication_isRefused() {
            var session = new ScriptedSession(DatabaseType.MYSQL, Map.of(), List.of(variable("group_replication_single_primary_mode", "OFF")));

            assertRefused(session, "multi-primary");
        }

        @Test
        void acquire_mysqlSinglePrimaryGroupReplication_isFenced() {
            var session = new ScriptedSession(DatabaseType.MYSQL, Map.of(), List.of(variable("group_replication_single_primary_mode", "ON")));

            assertThat(MigrationFence.acquire(session, "ds", "node-1").await().isSuccess()).isTrue();
        }

        @Test
        void acquire_mysqlWithoutADefaultSchema_isRefused() {
            var session = new ScriptedSession(DatabaseType.MYSQL, Map.of("DATABASE()", row("db", "")));

            assertRefused(session, "no default schema");
        }

        /// F26a, F26b, F18b: MySQL `verifyHeld` compares the lock's holder with THIS connection. A `GET_LOCK` that reports
        /// "granted" while `IS_USED_LOCK` names another connection (a pooler swapped the server session) is refused at acquire.
        @Test
        void acquire_mysqlLockGrantedButHeldByAnotherConnection_isFenceUnavailable() {
            var session = new ScriptedSession(DatabaseType.MYSQL, Map.of("IS_USED_LOCK(?) = CONNECTION_ID(), 0)", row("n", 0)));

            var cause = causeOf(MigrationFence.acquire(session, "ds", "node-1").await());

            assertThat(cause).isInstanceOf(MigrationError.FenceUnavailable.class);
            assertThat(cause.message()).contains("does not hold the fence it took");
            assertThat(session.statements).anyMatch(sql -> sql.contains("GET_LOCK("));
        }

        /// Positive control for the test above (same session, holder answered as this connection): the fence is taken,
        /// and the verification statement named the lock.
        @Test
        void acquire_mysqlLockHeldByThisConnection_verifiesTheNamedLockAndSucceeds() {
            var session = new ScriptedSession(DatabaseType.MYSQL, Map.of());

            assertThat(MigrationFence.acquire(session, "ds", "node-1").await().isSuccess()).isTrue();
            assertThat(session.statements).anyMatch(sql -> sql.contains("IS_USED_LOCK(?) = CONNECTION_ID(), 0)"));
            assertThat(session.params).contains(MigrationFence.MysqlFence.lockName("shop").unwrap());
        }

        /// F1: MariaDB is a tier-1 engine; F3: acquire on a MariaDB session takes the MySQL lock.
        @Test
        void isSessionFenced_byEngine_matchesTheTierTable() {
            assertThat(MigrationFence.isSessionFenced(DatabaseType.MARIADB)).isTrue();
            assertThat(MigrationFence.isSessionFenced(DatabaseType.MYSQL)).isTrue();
            assertThat(MigrationFence.isSessionFenced(DatabaseType.POSTGRESQL)).isTrue();
            assertThat(MigrationFence.isSessionFenced(DatabaseType.H2)).isTrue();
            assertThat(MigrationFence.isSessionFenced(DatabaseType.SQLSERVER)).isTrue();
            assertThat(MigrationFence.isSessionFenced(DatabaseType.ORACLE)).isTrue();
            assertThat(MigrationFence.isSessionFenced(DatabaseType.COCKROACHDB)).isFalse();
            assertThat(MigrationFence.isSessionFenced(DatabaseType.DB2)).isFalse();
            assertThat(MigrationFence.isSessionFenced(DatabaseType.SQLITE)).isFalse();
        }

        @Test
        void acquire_mariadb_takesTheMysqlNamedLock() {
            var session = new ScriptedSession(DatabaseType.MARIADB, Map.of());

            var fence = MigrationFence.acquire(session, "ds", "node-1").await();

            assertThat(fence.isSuccess()).isTrue();
            assertThat(fence.unwrap()).isInstanceOf(MigrationFence.MysqlFence.class);
            assertThat(session.statements).anyMatch(sql -> sql.contains("GET_LOCK("));
            assertThat(session.params).contains(MigrationFence.MysqlFence.lockName("shop").unwrap());
        }

        private static void assertRefused(ScriptedSession session, String reason) {
            var cause = causeOf(MigrationFence.acquire(session, "ds", "node-1").await());

            assertThat(cause).isInstanceOf(MigrationError.FenceUnavailable.class);
            assertThat(cause.message()).contains(reason);
            assertThat(session.statements).noneMatch(sql -> sql.contains("GET_LOCK("));
        }

        @Test
        void mysqlLockName_is51CharactersAndCaseInsensitive() {
            var name = MigrationFence.MysqlFence.lockName("Shop_DB").unwrap();

            assertThat(name).hasSize(51).startsWith("aether_mig:");
            assertThat(name).isEqualTo(MigrationFence.MysqlFence.lockName("shop_db").unwrap());
        }
    }

    @Nested
    class Runner {
        /// M3: on a tier-1 engine nothing touches the history tables (bootstrap included) before the fence is taken
        /// and verified on the pinned session.
        @Test
        void migrate_touchesHistoryOnlyAfterTheFenceIsVerified() {
            var session = new ScriptedSession(DatabaseType.POSTGRESQL, Map.of());

            SchemaMigrations.schemaMigrations(timeSpan(1).minutes())
                            .migrate("ds", List.of(MigrationScript.migrationScript("V1__a.sql", "CREATE TABLE a (id INT);", 1L)), session, "node-1", "bp")
                            .await();

            var verified = indexOf(session.statements, "AS n FROM pg_locks");
            var firstHistory = indexOf(session.statements, "aether_schema");

            assertThat(session.pinnedCalls.get()).isEqualTo(1);
            assertThat(verified).isNotNegative();
            assertThat(firstHistory).as("history touched only after the fence is verified").isGreaterThan(verified);
        }

        /// FDB-2a scope: a tier-3 engine (per-script lock) never asks for a pinned session.
        @Test
        void migrate_onATierThreeEngine_neverPins() {
            var session = new ScriptedSession(DatabaseType.SQLITE, Map.of());

            SchemaMigrations.schemaMigrations(timeSpan(1).minutes())
                            .migrate("ds", List.of(MigrationScript.migrationScript("V1__a.sql", "CREATE TABLE a (id INT);", 1L)), session, "node-1", "bp")
                            .await();

            assertThat(session.pinnedCalls.get()).isZero();
            assertThat(session.statements).noneMatch(sql -> sql.contains("pg_try_advisory_lock"));
        }

        /// Every write on a fenced session is preceded by `verifyHeld` (the `pg_backend_pid` lock check); reads are not.
        @Test
        void fencedSession_verifiesBeforeEveryWriteAndNeverBeforeAQuery() {
            var session = new ScriptedSession(DatabaseType.POSTGRESQL, Map.of());
            var fenced = new FencedSession(session, new MigrationFence.PgFence("ds"));

            fenced.queryList("SELECT 1", MigrationFence.IGNORED).await();
            assertThat(verifications(session)).isZero();

            fenced.update("INSERT INTO t VALUES (1)").await();
            fenced.batch("INSERT INTO t VALUES (?)", List.<Object[]>of(new Object[]{1})).await();
            fenced.transactional(tx -> tx.update("INSERT INTO t VALUES (2)")).await();
            assertThat(verifications(session)).isEqualTo(3);
        }

        /// Positive control for the no-unlock tests below: a run that took the fence and kept it ends with the courtesy
        /// unlock on its own session, on both engines.
        @Test
        void migrate_fencedRunThatKeptTheFence_runsTheCourtesyUnlock() {
            var postgres = new ScriptedSession(DatabaseType.POSTGRESQL, Map.of());
            var mysql = new ScriptedSession(DatabaseType.MYSQL, Map.of());

            assertThat(migrateOneScript(postgres).isSuccess()).isTrue();
            assertThat(migrateOneScript(mysql).isSuccess()).isTrue();
            assertThat(postgres.statements).anyMatch(sql -> sql.contains("pg_advisory_unlock_all"));
            assertThat(mysql.statements).anyMatch(sql -> sql.contains("RELEASE_ALL_LOCKS"));
        }

        /// CTO ruling (FDB-2a fix round 1): a runner refused at acquire never runs `pg_advisory_unlock_all()` /
        /// `RELEASE_ALL_LOCKS()`. On a pooler's shared server session that would drop the lock of the client that does
        /// hold it. Held by another session, and the shared-session refusal, on both engines.
        @Test
        void migrate_refusedAtAcquire_neverRunsTheCourtesyUnlock() {
            var heldElsewhere = new ScriptedSession(DatabaseType.POSTGRESQL, Map.of("pg_try_advisory_lock", row("granted", 0)));
            var sharedPostgres = new ScriptedSession(DatabaseType.POSTGRESQL, Map.of("pg_try_advisory_lock", row("granted", -1)));
            var sharedMysql = new ScriptedSession(DatabaseType.MYSQL, Map.of("GET_LOCK(", row("granted", -1)));

            assertThat(causeOf(migrateOneScript(heldElsewhere))).isInstanceOf(MigrationError.MigrationFencedInDatabase.class);
            assertThat(causeOf(migrateOneScript(sharedPostgres))).isInstanceOf(MigrationError.FenceUnavailable.class);
            assertThat(causeOf(migrateOneScript(sharedMysql))).isInstanceOf(MigrationError.FenceUnavailable.class);
            assertThat(heldElsewhere.statements).noneMatch(sql -> sql.contains("unlock"));
            assertThat(sharedPostgres.statements).noneMatch(sql -> sql.contains("unlock"));
            assertThat(sharedMysql.statements).noneMatch(sql -> sql.contains("RELEASE_ALL_LOCKS"));
        }

        /// The same for a run that took the fence and then lost it (a pooler moved it to another server session): its
        /// next write is refused, and the session it is on now holds nothing of its own to unlock.
        @Test
        void migrate_fenceLostMidRun_isRefusedAndNeverRunsTheCourtesyUnlock() {
            var session = new ScriptedSession(DatabaseType.POSTGRESQL, Map.of()).losingTheFenceAfter(1);

            assertThat(causeOf(migrateOneScript(session))).isInstanceOf(MigrationError.FenceUnavailable.class);
            assertThat(session.statements).noneMatch(sql -> sql.contains("pg_advisory_unlock_all"));
        }

        /// G4: `migrate()` runs its body through the [FencedSession], not the bare pinned session. The fence is granted and
        /// verified at acquire (check 1), held for the first write (check 2) and lost before the second (check 3): the
        /// run must end `FenceUnavailable` with exactly ONE write executed. Without the wrapper nothing re-verifies and the
        /// run completes.
        @Test
        void migrate_fenceLostAfterTheFirstWrite_failsBeforeTheSecondWrite() {
            var session = new ScriptedSession(DatabaseType.POSTGRESQL, Map.of()).losingTheFenceAfter(2);

            var outcome = migrateOneScript(session);

            assertThat(causeOf(outcome)).isInstanceOf(MigrationError.FenceUnavailable.class);
            assertThat(session.writes.get()).as("the second write must be refused before it reaches the session").isEqualTo(1);
        }

        /// M8 for `migrate`, G5a for `undo`, G5b for `baseline`: on a tier-1 engine every statement of the run, history
        /// bootstrap and ownership claim included, goes to the PINNED session; the base connector (the pool) sees none.
        /// Positive control: the pinned session did receive the run.
        @Test
        void migrate_everyStatementRunsOnThePinnedSession_noneOnTheBaseConnector() {
            var dedicated = new ScriptedSession(DatabaseType.POSTGRESQL, Map.of());
            var base = new ScriptedSession(DatabaseType.POSTGRESQL, Map.of()).pinningTo(dedicated);

            assertThat(migrateOneScript(base).isSuccess()).isTrue();

            assertRanOnlyOnThePinnedSession(base, dedicated);
        }

        @Test
        void undo_everyStatementRunsOnThePinnedSession_noneOnTheBaseConnector() {
            var dedicated = new ScriptedSession(DatabaseType.POSTGRESQL, Map.of());
            var base = new ScriptedSession(DatabaseType.POSTGRESQL, Map.of()).pinningTo(dedicated);

            var outcome = SchemaMigrations.schemaMigrations(timeSpan(1).minutes()).undo("ds", 0, List.of(), base, "node-1", "bp").await();

            assertThat(outcome.isSuccess()).isTrue();
            assertRanOnlyOnThePinnedSession(base, dedicated);
        }

        @Test
        void baseline_everyStatementRunsOnThePinnedSession_noneOnTheBaseConnector() {
            var dedicated = new ScriptedSession(DatabaseType.POSTGRESQL, Map.of());
            var base = new ScriptedSession(DatabaseType.POSTGRESQL, Map.of()).pinningTo(dedicated);

            var outcome = SchemaMigrations.schemaMigrations(timeSpan(1).minutes()).baseline("ds", 1, List.of(), base, "node-1", "bp").await();

            assertThat(outcome.isSuccess()).isTrue();
            assertRanOnlyOnThePinnedSession(base, dedicated);
        }

        private static void assertRanOnlyOnThePinnedSession(ScriptedSession base, ScriptedSession dedicated) {
            assertThat(base.pinnedCalls.get()).isEqualTo(1);
            assertThat(dedicated.statements).as("the run reached the pinned session").anyMatch(sql -> sql.contains("aether_schema"));
            assertThat(base.statements).as("nothing may run on the base connector").isEmpty();
        }

        private static Result<SchemaMigrations.SchemaResult> migrateOneScript(ScriptedSession session) {
            return SchemaMigrations.schemaMigrations(timeSpan(1).minutes())
                                   .migrate("ds", List.of(MigrationScript.migrationScript("V1__a.sql", "CREATE TABLE a (id INT);", 1L)), session, "node-1", "bp")
                                   .await();
        }

        private static long verifications(ScriptedSession session) {
            return session.statements.stream().filter(sql -> sql.contains("AS n FROM pg_locks")).count();
        }

        private static int indexOf(List<String> statements, String fragment) {
            for (var i = 0; i < statements.size(); i++) {
                if (statements.get(i).contains(fragment)) {
                    return i;
                }
            }

            return -1;
        }
    }

    @Nested
    class AutocommitStatements {
        @Test
        void concurrentIndex_namedUnquoted_foldsToLowerCase() {
            var statement = AutocommitStatement.autocommitStatement("CREATE INDEX CONCURRENTLY IF NOT EXISTS Orders_Idx ON orders (id)",
                                                                    MigrationDialects.POSTGRESQL);

            assertThat(statement.concurrentIndex()).isEqualTo(some("orders_idx"));
            assertThat(statement.transactional()).isFalse();
        }

        @Test
        void concurrentIndex_uniqueAndQuoted_keepsTheQuotedName() {
            var statement = AutocommitStatement.autocommitStatement("create unique index concurrently \"Big\"\"Idx\" on t (c)",
                                                                    MigrationDialects.POSTGRESQL);

            assertThat(statement.concurrentIndex()).isEqualTo(some("Big\"Idx"));
        }

        /// F8 scope (fix round 1, A3): the build's table is kept as written, schema and quotes included, so the
        /// validity check can resolve it with `to_regclass`.
        @Test
        void concurrentBuild_keepsTheTableAsWritten() {
            var qualified = AutocommitStatement.autocommitStatement("CREATE INDEX CONCURRENTLY IF NOT EXISTS dup_idx ON public.t13 (c)",
                                                                    MigrationDialects.POSTGRESQL);
            var quotedOnly = AutocommitStatement.autocommitStatement("CREATE INDEX CONCURRENTLY i ON ONLY \"Sch\" . \"T\"(c)",
                                                                     MigrationDialects.POSTGRESQL);

            assertThat(qualified.concurrentBuild().map(AutocommitStatement.ConcurrentBuild::table)).isEqualTo(some("public.t13"));
            assertThat(quotedOnly.concurrentBuild().map(AutocommitStatement.ConcurrentBuild::table)).isEqualTo(some("\"Sch\" . \"T\""));
        }

        @Test
        void concurrentIndex_unnamed_cannotBeChecked() {
            var statement = AutocommitStatement.autocommitStatement("CREATE INDEX CONCURRENTLY ON t (c)", MigrationDialects.POSTGRESQL);

            assertThat(statement.concurrentIndex()).isEqualTo(none());
        }

        @Test
        void dml_isTransactional_andNamesNoIndex() {
            var statement = AutocommitStatement.autocommitStatement("INSERT INTO t VALUES (1)", MigrationDialects.POSTGRESQL);

            assertThat(statement.transactional()).isTrue();
            assertThat(statement.concurrentIndex()).isEqualTo(none());
        }

        @Test
        void mysql_neverNamesAConcurrentIndex() {
            var statement = AutocommitStatement.autocommitStatement("CREATE INDEX CONCURRENTLY i ON t (c)", MigrationDialects.MYSQL);

            assertThat(statement.concurrentIndex()).isEqualTo(none());
        }
    }

    // ---------------------------------------------------------------- fixtures

    private static Cause causeOf(Result<?> result) {
        return result.fold(cause -> cause, value -> {
            throw new AssertionError("expected a failure, got " + value);
        });
    }

    private static Map<String, Object> row(String column, Object value) {
        return Map.of(column, value);
    }

    private static Map<String, Object> variable(String name, String value) {
        return Map.of("Variable_name", name, "Value", value);
    }

    /// An in-memory session that records every statement and answers the fence's statements as an uncontended
    /// database would, unless an override (matched by SQL fragment) says otherwise.
    /// FDB-2b: tier 2 (SQL Server, Oracle) and H2 take a session fence; their statements are pinned here against a
    /// scripted session, the live engines being nightly heavy-db `[unverified engine]`.
    @Nested
    class TierTwo {
        @Test
        void acquire_sqlServerLockGranted_takesAnExclusiveSessionOwnedApplock() {
            var session = new ScriptedSession(DatabaseType.SQLSERVER, Map.of());

            var fence = MigrationFence.acquire(session, "ds", "node-1").await();

            assertThat(fence.unwrap()).isInstanceOf(MigrationFence.SqlServerFence.class);
            assertThat(session.statements).anyMatch(sql -> sql.contains("sp_getapplock") && sql.contains("'Session'") && sql.contains("@LockTimeout = 0"));
        }

        @Test
        void acquire_sqlServerLockNotGranted_isFencedNamingTheHolder() {
            var session = new ScriptedSession(DatabaseType.SQLSERVER,
                                              Map.of("sp_getapplock", row("granted", -1),
                                                     "AS holder", row("holder", "session_id=55, program=aether")));

            var cause = causeOf(MigrationFence.acquire(session, "ds", "node-1").await());

            assertThat(cause).isInstanceOf(MigrationError.MigrationFencedInDatabase.class);
            assertThat(cause.message()).contains("session_id=55");
        }

        @Test
        void acquire_sqlServerLockGrantedButNotHeld_isFenceUnavailable() {
            var session = new ScriptedSession(DatabaseType.SQLSERVER, Map.of("APPLOCK_MODE", row("n", 0)));

            assertThat(causeOf(MigrationFence.acquire(session, "ds", "node-1").await())).isInstanceOf(MigrationError.FenceUnavailable.class);
        }

        @Test
        void acquire_oracleRequestGranted_takesTheSessionLockThatSurvivesCommit() {
            var session = new ScriptedSession(DatabaseType.ORACLE, Map.of());

            var fence = MigrationFence.acquire(session, "ds", "node-1").await();

            assertThat(fence.unwrap()).isInstanceOf(MigrationFence.OracleFence.class);
            assertThat(session.statements).anyMatch(sql -> sql.contains("DBMS_LOCK.REQUEST(h, DBMS_LOCK.X_MODE, 0, FALSE)"));
            assertThat(session.params).contains("aether-mig:node-1");
        }

        @Test
        void acquire_oracleLockHeldElsewhere_isFencedInDatabase() {
            var session = new ScriptedSession(DatabaseType.ORACLE, Map.of()).failingUpdate("DBMS_APPLICATION_INFO", Causes.cause("ORA-20931: held"));

            assertThat(causeOf(MigrationFence.acquire(session, "ds", "node-1").await())).isInstanceOf(MigrationError.MigrationFencedInDatabase.class);
        }

        /// Owner ruling Q2: without `EXECUTE ON SYS.DBMS_LOCK` the fence fails closed, naming the GRANT.
        @Test
        void acquire_oracleWithoutExecuteOnDbmsLock_isFenceUnavailableNamingTheGrant() {
            var session = new ScriptedSession(DatabaseType.ORACLE, Map.of()).failingUpdate("DBMS_APPLICATION_INFO", Causes.cause("PLS-00201: identifier 'SYS.DBMS_LOCK' must be declared"));

            var cause = causeOf(MigrationFence.acquire(session, "ds", "node-1").await());

            assertThat(cause).isInstanceOf(MigrationError.FenceUnavailable.class);
            assertThat(cause.message()).contains("GRANT EXECUTE ON SYS.DBMS_LOCK");
        }

        @Test
        void acquire_oracleServerSessionAlreadyHoldsTheLock_isFenceUnavailableNamingThePooler() {
            var session = new ScriptedSession(DatabaseType.ORACLE, Map.of()).failingUpdate("DBMS_APPLICATION_INFO", Causes.cause("ORA-20932: reentered"));

            var cause = causeOf(MigrationFence.acquire(session, "ds", "node-1").await());

            assertThat(cause).isInstanceOf(MigrationError.FenceUnavailable.class);
            assertThat(cause.message()).contains("pooler");
        }

        @Test
        void verifyHeld_oracleRequestNotAnsweringAlreadyOwned_isFenceUnavailable() {
            var session = new ScriptedSession(DatabaseType.ORACLE, Map.of());
            var fence = MigrationFence.acquire(session, "ds", "node-1").await().unwrap();
            session.failingUpdate("IF r <> 4", Causes.cause("ORA-20933: not held"));

            assertThat(causeOf(fence.verifyHeld(session).await())).isInstanceOf(MigrationError.FenceUnavailable.class);
        }

        @Test
        void acquire_h2_setsExclusiveModeAndVerifiesIt() {
            var session = new ScriptedSession(DatabaseType.H2, Map.of("INFORMATION_SCHEMA.SETTINGS", row("v", "TRUE")));

            var fence = MigrationFence.acquire(session, "ds", "node-1").await();

            assertThat(fence.unwrap()).isInstanceOf(MigrationFence.H2Fence.class);
            assertThat(session.statements).anyMatch(sql -> sql.equals("SET EXCLUSIVE 1"));
        }

        @Test
        void acquire_h2ExclusiveNotInEffect_isFenceUnavailable() {
            var session = new ScriptedSession(DatabaseType.H2, Map.of("INFORMATION_SCHEMA.SETTINGS", row("v", "FALSE")));

            assertThat(causeOf(MigrationFence.acquire(session, "ds", "node-1").await())).isInstanceOf(MigrationError.FenceUnavailable.class);
        }

        /// F61: a session fence IS the fence, so none of them takes a per-script lock on top of it.
        @Test
        void sessionFences_takeNoScriptLock() {
            var fences = List.<MigrationFence>of(new MigrationFence.H2Fence("ds"),
                                                           new MigrationFence.SqlServerFence("ds"),
                                                           new MigrationFence.OracleFence("ds"),
                                                           new MigrationFence.PgFence("ds"),
                                                           new MigrationFence.MysqlFence("ds", "lock"));

            assertThat(fences).allSatisfy(fence -> assertThat(fence.scriptLock().isPresent()).as(fence.getClass().getSimpleName()).isFalse());
        }

        @Test
        void acquire_h2ExclusiveReportedAsZero_isFenceUnavailable() {
            var session = new ScriptedSession(DatabaseType.H2, Map.of("INFORMATION_SCHEMA.SETTINGS", row("v", "0")));

            assertThat(causeOf(MigrationFence.acquire(session, "ds", "node-1").await())).isInstanceOf(MigrationError.FenceUnavailable.class);
        }

        @Test
        void acquire_db2AndSqlite_takeNoLockUntilTheScriptTransaction() {
            var db2 = new ScriptedSession(DatabaseType.DB2, Map.of());
            var sqlite = new ScriptedSession(DatabaseType.SQLITE, Map.of());

            var db2Fence = MigrationFence.acquire(db2, "ds", "node-1").await().unwrap();
            var sqliteFence = MigrationFence.acquire(sqlite, "ds", "node-1").await().unwrap();

            assertThat(db2.statements).isEmpty();
            assertThat(sqlite.statements).isEmpty();
            assertThat(db2Fence.scriptLock().map(MigrationFence.ScriptLock::statement)).isEqualTo(some("LOCK TABLE aether_schema_history IN EXCLUSIVE MODE"));
            assertThat(sqliteFence.scriptLock().map(MigrationFence.ScriptLock::statement).map(sql -> sql.startsWith("UPDATE aether_schema_owner SET"))).isEqualTo(some(true));
        }
    }

    private static final class ScriptedSession implements SqlConnector {
        private static final Map<String, Map<String, Object>> DEFAULTS = Map.of("pg_try_advisory_lock", row("granted", 1),
                                                                                "AS n FROM pg_locks", row("n", 1),
                                                                                "version()", row("v", "PostgreSQL 16.4"),
                                                                                "set_config", row("v", "set"),
                                                                                "DATABASE()", row("db", "shop"),
                                                                                "GET_LOCK(", row("granted", 1),
                                                                                "CONNECTION_ID(), 0)", row("n", 1),
                                                                                "sp_getapplock", row("granted", 0),
                                                                                "APPLOCK_MODE", row("n", 1));
        private final DatabaseConnectorConfig config;
        private final Map<String, Map<String, Object>> overrides;
        private final List<Map<String, Object>> variables;
        private final List<String> statements = new ArrayList<>();
        private final List<Object> params = new ArrayList<>();
        private final AtomicInteger pinnedCalls = new AtomicInteger();
        private final AtomicInteger writes = new AtomicInteger();
        private Option<AtomicInteger> heldChecksLeft = none();
        private Option<ScriptedSession> pinnedSession = none();
        private final Map<String, Cause> updateFailures = new HashMap<>();

        ScriptedSession(DatabaseType type, Map<String, Map<String, Object>> overrides) {
            this(type, overrides, List.of());
        }

        ScriptedSession(DatabaseType type, Map<String, Map<String, Object>> overrides, List<Map<String, Object>> variables) {
            this.config = new DatabaseConnectorConfig(none(), some(type), some("localhost"), none(), some("test"), none(), none(),
                                                      PoolConfig.DEFAULT, Map.of(), none(), none(), none());
            this.overrides = overrides;
            this.variables = variables;
        }

        /// `pinned` opens THIS dedicated session instead of the connector itself, so a statement on the connector
        /// (the pool) is distinguishable from one on the pinned session.
        ScriptedSession pinningTo(ScriptedSession dedicated) {
            pinnedSession = some(dedicated);
            return this;
        }

        /// An update whose text contains `fragment` fails with `cause`.
        ScriptedSession failingUpdate(String fragment, Cause cause) {
            updateFailures.put(fragment, cause);
            return this;
        }

        /// After `checks` fence verifications (the `AS n FROM pg_locks` check) this session no longer holds the fence.
        ScriptedSession losingTheFenceAfter(int checks) {
            heldChecksLeft = some(new AtomicInteger(checks));
            return this;
        }

        @Override
        public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... args) {
            return queryOptional(sql, mapper, args).flatMap(row -> row.async(Causes.cause("no row for " + sql)));
        }

        @Override
        public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... args) {
            record(sql, args);
            return Promise.success(lostFence(sql).orElse(answer(overrides, sql))
                                                 .orElse(answer(DEFAULTS, sql))
                                                 .flatMap(values -> mapper.map(new MapRow(values)).option()));
        }

        @Override
        public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... args) {
            record(sql, args);
            var rows = sql.contains("SHOW VARIABLES") ? variables : List.<Map<String, Object>>of();

            return Promise.success(rows.stream().map(values -> mapper.map(new MapRow(values)).unwrap()).toList());
        }

        @Override
        public Promise<Integer> update(String sql, Object... args) {
            record(sql, args);
            writes.incrementAndGet();
            return updateFailures.entrySet()
                                 .stream()
                                 .filter(entry -> sql.contains(entry.getKey()))
                                 .findFirst()
                                 .map(entry -> entry.getValue().<Integer>promise())
                                 .orElseGet(() -> Promise.success(0));
        }

        @Override
        public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
            record(sql, new Object[0]);
            writes.incrementAndGet();
            return Promise.success(new int[0]);
        }

        @Override
        public <T> Promise<T> transactional(TransactionCallback<T> callback) {
            return callback.execute(this);
        }

        @Override
        public <T> Promise<T> pinned(Duration statementTimeout, TransactionCallback<T> body, TransactionCallback<Unit> release) {
            pinnedCalls.incrementAndGet();

            var dedicated = pinnedSession.or(this);

            return body.execute(dedicated).fold(result -> release.execute(dedicated).fold(_ -> Promise.resolved(result)));
        }

        @Override
        public DatabaseConnectorConfig config() {
            return config;
        }

        @Override
        public Promise<Boolean> isHealthy() {
            return Promise.success(true);
        }

        private Option<Map<String, Object>> lostFence(String sql) {
            return heldChecksLeft.filter(_ -> sql.contains("AS n FROM pg_locks"))
                                 .filter(left -> left.getAndDecrement() <= 0)
                                 .map(_ -> row("n", 0));
        }

        private void record(String sql, Object[] args) {
            statements.add(sql);
            params.addAll(List.of(args));
        }

        private static Option<Map<String, Object>> answer(Map<String, Map<String, Object>> table, String sql) {
            return Option.from(table.entrySet().stream().filter(entry -> sql.contains(entry.getKey())).map(Map.Entry::getValue).findFirst());
        }
    }

    private record MapRow(Map<String, Object> values) implements RowMapper.RowAccessor {
        @Override
        public Result<String> getString(String column) {
            return value(column, String.class);
        }

        @Override
        public Result<Integer> getInt(String column) {
            return value(column, Integer.class);
        }

        @Override
        public Result<Long> getLong(String column) {
            return value(column, Integer.class).map(Integer::longValue);
        }

        @Override
        public Result<Double> getDouble(String column) {
            return value(column, Double.class);
        }

        @Override
        public Result<Boolean> getBoolean(String column) {
            return value(column, Boolean.class);
        }

        @Override
        public Result<byte[]> getBytes(String column) {
            return value(column, byte[].class);
        }

        @Override
        public <V> Result<V> getObject(String column, Class<V> type) {
            return value(column, type);
        }

        private <V> Result<V> value(String column, Class<V> type) {
            return Option.option(values.get(column)).filter(type::isInstance).map(type::cast).toResult(Causes.cause("no " + column));
        }
    }
}
