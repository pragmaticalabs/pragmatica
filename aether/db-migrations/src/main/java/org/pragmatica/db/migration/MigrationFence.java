// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.db.migration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

import org.pragmatica.aether.resource.db.DatabaseConnectorError;
import org.pragmatica.aether.resource.db.DatabaseType;
import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.db.migration.MigrationError.FenceUnavailable.fenceUnavailable;
import static org.pragmatica.db.migration.MigrationError.MigrationFencedInDatabase.migrationFencedInDatabase;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The database-side migration fence (FDB-2a and FDB-2b, #2591; design `design-migration-db-fence.md` §2.2). Three
/// tiers, by what the engine can hold:
///
/// **Tiers 1 and 2: a session-scoped lock** ([#isSessionFenced]), taken on the migration's PINNED session
/// ([SqlConnector#pinned]), held for the whole run and released by closing that session. It has no TTL and no lock
/// timeout: it lives exactly as long as the server session that runs the migration's statements, so nothing tunable can
/// undercut a script's duration.
/// - **PostgreSQL:** `pg_try_advisory_lock(0x41455448, 1)`, per database. The session first names itself
///   (`application_name = aether-mig:<node>`), so a holder is identifiable in `pg_stat_activity`, and shortens the
///   server's silent-death detection with TCP keepalives (§2.5, best effort).
/// - **MySQL / MariaDB:** `GET_LOCK(name, 0)`, `name` = `aether_mig:` + the first 40 hex of SHA-256(lower(schema)),
///   51 characters (MySQL refuses names over 64; lock names are case-insensitive). Galera and multi-primary group
///   replication are refused (§2.6): there `GET_LOCK` is node-local.
/// - **H2** (ruled (a), §7 Q5; T12): `SET EXCLUSIVE 1` on the pinned session. The database rejects every other new
///   connection (error 90135, which [#refusedAtConnect] reads as the fence being held) until that session closes.
/// - **SQL Server** (tier 2): `sp_getapplock` with `@LockOwner = 'Session'`, resource `aether_schema_migration`.
/// - **Oracle** (tier 2): `DBMS_LOCK.REQUEST(.., X_MODE, timeout => 0, release_on_commit => FALSE)`. Without
///   `EXECUTE ON SYS.DBMS_LOCK` the fence cannot be held: [MigrationError.FenceUnavailable] naming the GRANT (fails
///   closed, owner ruling Q2).
///
/// **Tier 3: a per-script transaction lock** ([ScriptLock]) for CockroachDB, DB2 and SQLite, which have no
/// session-scoped lock. It is the FIRST statement of each script's transaction, and the script's history row is
/// re-read under it. The guarantee is weaker and stated so (§1): per script, the statements and the history row commit
/// at most once.
///
/// **Engine identity is probed, not trusted:** a PostgreSQL-configured datasource whose `version()` names CockroachDB
/// gets the CockroachDB tier-3 lock: its advisory locks acquire nothing. The runner logs it on every such run, and the
/// schema orchestrator raises the operator notice ([#runsUnfenced]) that no whole-run fence holds there.
///
/// **`verifyHeld`** confirms a session lock is held by THIS session. It runs inside [#acquire], before any bootstrap
/// write (a no-op lock or a pooler that swapped the session is refused before the first write), and before every write
/// of the run ([FencedSession]).
public sealed interface MigrationFence {
    /// Succeeds while this session holds the fence; fails with [MigrationError.FenceUnavailable] once it does not.
    Promise<Unit> verifyHeld(SqlConnector session);

    /// The courtesy unlock, run only by a session that took the fence and still holds it (never by a refused one: on a
    /// pooler's shared server session it would drop the real holder's lock). Closing the session is the real release
    /// (§2.1 rule 2), so the caller's outcome never depends on this; a fence with nothing to unlock keeps the default.
    default Promise<Unit> release(SqlConnector session) {
        return Promise.unitPromise();
    }

    /// The lock a tier-3 fence takes as the first statement of each script's transaction; none for a session fence.
    default Option<ScriptLock> scriptLock() {
        return none();
    }

    /// The engines whose fence is a session lock on a pinned session (tiers 1 and 2): the runner takes every migration
    /// of these on one dedicated session. The rest (CockroachDB, DB2, SQLite) are tier 3.
    static boolean isSessionFenced(DatabaseType type) {
        return switch (type) {
            case POSTGRESQL, MYSQL, MARIADB, H2, SQLSERVER, ORACLE -> true;
            case COCKROACHDB, SQLITE, DB2 -> false;
        };
    }

    /// Takes the fence on `session`: [MigrationError.MigrationFencedInDatabase] naming the holder when another session
    /// holds it, [MigrationError.FenceUnavailable] when it cannot be held here. A tier-3 engine answers at once with its
    /// per-script lock, which is taken later, inside each script's transaction.
    static Promise<MigrationFence> acquire(SqlConnector session, String datasource, String nodeId) {
        return switch (session.databaseType()) {
            case POSTGRESQL -> PgFence.acquire(session, datasource, nodeId);
            case MYSQL, MARIADB -> MysqlFence.acquire(session, datasource);
            case H2 -> H2Fence.acquire(session, datasource);
            case SQLSERVER -> SqlServerFence.acquire(session, datasource);
            case ORACLE -> OracleFence.acquire(session, datasource, nodeId);
            case COCKROACHDB -> Promise.success(ScriptLock.cockroach());
            case DB2 -> Promise.success(ScriptLock.db2());
            case SQLITE -> Promise.success(ScriptLock.sqlite());
        };
    }

    /// An engine that refuses the second connection while the fence is held (H2's exclusive mode, error 90135) reports
    /// a competing runner at CONNECT, before any session exists: read it as the fence being held. The pool wraps the
    /// driver's error, so the SQLSTATE is looked for on the cause chain too. Every other failure passes through
    /// unchanged.
    static Cause refusedAtConnect(String datasource, DatabaseType type, Cause cause) {
        return type == DatabaseType.H2 && namesExclusiveRefusal(cause)
               ? migrationFencedInDatabase(datasource, "H2: another connection holds the database in exclusive mode (SET EXCLUSIVE)")
               : cause;
    }

    private static boolean namesExclusiveRefusal(Cause cause) {
        return cause instanceof DatabaseConnectorError error
               && (error.sqlState().equals(some(H2Fence.EXCLUSIVE_REFUSED)) || driverChainNamesIt(throwableOf(error)));
    }

    private static Option<Throwable> throwableOf(DatabaseConnectorError error) {
        return switch (error) {
            case DatabaseConnectorError.QueryFailed failed -> failed.cause();
            case DatabaseConnectorError.ConnectionFailed failed -> failed.cause();
            default -> none();
        };
    }

    private static boolean driverChainNamesIt(Option<Throwable> thrown) {
        return thrown.map(first -> Stream.iterate(first, Objects::nonNull, Throwable::getCause)
                                         .anyMatch(MigrationFence::isExclusiveRefusal))
                     .or(false);
    }

    private static boolean isExclusiveRefusal(Throwable thrown) {
        return thrown instanceof SQLException sql && H2Fence.EXCLUSIVE_REFUSED.equals(sql.getSQLState());
    }

    /// Whether a schema operation on `connector` would run WITHOUT a whole-run (tier 1 or 2) fence although its
    /// configured engine has one: a PostgreSQL-configured CockroachDB, identified by the same `version()` probe
    /// [#acquire] makes; it runs on the tier-3 per-script lock only. Every other engine answers without a query.
    static Promise<Boolean> runsUnfenced(SqlConnector connector) {
        return connector.databaseType() == DatabaseType.POSTGRESQL
               ? connector.queryOne("SELECT version() AS v",
                                    row -> row.getString("v"))
                          .map(PgFence::isCockroach)
               : Promise.success(false);
    }

    RowMapper<Unit> IGNORED = _ -> Result.unitResult();
    RowMapper<Integer> COUNT = row -> row.getInt("n");

    /// The tier-3 fence (CockroachDB, DB2, SQLite): a lock taken as the FIRST statement of each script's transaction
    /// ([#take]), under which the runner re-reads that script's history row. Nothing outlives the transaction, so there
    /// is nothing to verify or release.
    ///
    /// @param engine      the engine's name, for the refusal of a script that cannot run in a transaction
    /// @param statement   the locking statement
    /// @param returnsRows whether the statement is a query (`SELECT ... FOR UPDATE`) rather than an update
    record ScriptLock(String engine, String statement, boolean returnsRows) implements MigrationFence {
        /// CockroachDB: `FOR UPDATE` on the owner row, which exists from the first completed bootstrap on. Works on every
        /// supported version, unlike the transaction-scoped advisory lock (F2).
        static ScriptLock cockroach() {
            return new ScriptLock("CockroachDB", "SELECT 1 FROM " + SchemaHistoryEvolution.OWNER_TABLE + " FOR UPDATE", true);
        }

        static ScriptLock db2() {
            return new ScriptLock("DB2", "LOCK TABLE aether_schema_history IN EXCLUSIVE MODE", false);
        }

        /// SQLite: a write that changes nothing. A deferred transaction takes the database write lock at its first write,
        /// and nothing was read before it, so this is the lock `BEGIN IMMEDIATE` takes (the connector offers no hook for
        /// that statement) `[unverified: engine; pinned by MigrationFenceSqliteTest]`.
        static ScriptLock sqlite() {
            return new ScriptLock("SQLite",
                                  "UPDATE " + SchemaHistoryEvolution.OWNER_TABLE + " SET blueprint_base = blueprint_base",
                                  false);
        }

        /// Takes the lock on the script's transaction connector `tx`.
        Promise<Unit> take(SqlConnector tx) {
            return returnsRows
                   ? tx.queryList(statement, IGNORED)
                       .mapToUnit()
                   : tx.update(statement)
                       .mapToUnit();
        }

        @Override
        public Promise<Unit> verifyHeld(SqlConnector session) {
            return Promise.unitPromise();
        }

        @Override
        public Option<ScriptLock> scriptLock() {
            return some(this);
        }
    }

    /// H2 (ruled (a), design §7 Q5; T12): exclusive mode on the pinned session. It lasts until that session closes;
    /// every other new connection is rejected at connect (error 90135, see [#refusedAtConnect]) and the connections
    /// already open are paused, so a competing runner never reaches a statement.
    record H2Fence(String datasource) implements MigrationFence {
        static final String EXCLUSIVE_REFUSED = "90135";
        private static final String VERIFY = "SELECT SETTING_VALUE AS v FROM INFORMATION_SCHEMA.SETTINGS WHERE SETTING_NAME = 'EXCLUSIVE'";

        static Promise<MigrationFence> acquire(SqlConnector session, String datasource) {
            return session.update("SET EXCLUSIVE 1")
                          .flatMap(_ -> verified(session, new H2Fence(datasource)));
        }

        @Override
        public Promise<Unit> verifyHeld(SqlConnector session) {
            return session.queryOne(VERIFY,
                                    row -> row.getString("v"))
                          .flatMap(value -> requireHeld(isExclusive(value)
                                                        ? 1
                                                        : 0,
                                                        datasource));
        }

        private static boolean isExclusive(String value) {
            return !value.equalsIgnoreCase("FALSE") && !value.equals("0");
        }
    }

    /// SQL Server (tier 2): an application lock owned by the session, never by a transaction, so it outlives every
    /// commit and goes with the session. `[unverified engine: nightly heavy-db only]`
    record SqlServerFence(String datasource) implements MigrationFence {
        private static final String TRY_LOCK = "DECLARE @r INT; EXEC @r = sp_getapplock @Resource = 'aether_schema_migration', @LockMode = 'Exclusive',"
                                             + " @LockOwner = 'Session', @LockTimeout = 0; SELECT @r AS granted";
        private static final String VERIFY = "SELECT CASE WHEN APPLOCK_MODE('public', 'aether_schema_migration', 'Session') = 'Exclusive' THEN 1 ELSE 0 END AS n";
        private static final String RELEASE = "EXEC sp_releaseapplock @Resource = 'aether_schema_migration', @LockOwner = 'Session'";
        private static final String HOLDER = "SELECT TOP 1 CONCAT('session_id=', l.request_session_id, ', program=', COALESCE(s.program_name, ''),"
                                           + " ', host=', COALESCE(s.host_name, '')) AS holder FROM sys.dm_tran_locks l"
                                           + " LEFT JOIN sys.dm_exec_sessions s ON s.session_id = l.request_session_id"
                                           + " WHERE l.resource_type = 'APPLICATION' AND l.resource_description LIKE '%aether_schema_migration%'";

        static Promise<MigrationFence> acquire(SqlConnector session, String datasource) {
            return session.queryOne(TRY_LOCK,
                                    row -> row.getInt("granted"))
                          .flatMap(granted -> granted >= 0
                                              ? verified(session, new SqlServerFence(datasource))
                                              : heldBy(session, datasource));
        }

        private static Promise<MigrationFence> heldBy(SqlConnector session, String datasource) {
            return session.queryOptional(HOLDER,
                                         row -> row.getString("holder"))
                          .flatMap(holder -> migrationFencedInDatabase(datasource,
                                                                       holder.or("holder not visible; it may have just ended")).promise());
        }

        @Override
        public Promise<Unit> verifyHeld(SqlConnector session) {
            return session.queryOne(VERIFY, COUNT)
                          .flatMap(held -> requireHeld(held, datasource));
        }

        @Override
        public Promise<Unit> release(SqlConnector session) {
            return session.update(RELEASE)
                          .mapToUnit();
        }
    }

    /// Oracle (tier 2): `DBMS_LOCK` with `release_on_commit => FALSE`, so the lock outlives every commit and goes with
    /// the session. It needs `EXECUTE ON SYS.DBMS_LOCK`; without it the fence cannot be held and every migration is
    /// refused naming the GRANT (fails closed, owner ruling Q2). `REQUEST` on a lock this session already holds answers 4,
    /// which is how a pooler's shared server session shows and how [#verifyHeld] asks. `[unverified engine: nightly
    /// heavy-db only]`
    record OracleFence(String datasource) implements MigrationFence {
        private static final String HELD_CODE = "ORA-20931";
        private static final String REENTERED_CODE = "ORA-20932";
        private static final String ALLOCATE = "DECLARE h VARCHAR2(128); r INTEGER; BEGIN DBMS_LOCK.ALLOCATE_UNIQUE('AETHER_MIG_' || SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA'), h); ";
        private static final String TRY_LOCK = "DECLARE h VARCHAR2(128); r INTEGER; BEGIN DBMS_APPLICATION_INFO.SET_MODULE(?, 'migration');"
                                             + " DBMS_LOCK.ALLOCATE_UNIQUE('AETHER_MIG_' || SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA'), h);"
                                             + " r := DBMS_LOCK.REQUEST(h, DBMS_LOCK.X_MODE, 0, FALSE);"
                                             + " IF r = 1 THEN RAISE_APPLICATION_ERROR(-20931, 'held');"
                                             + " ELSIF r = 4 THEN RAISE_APPLICATION_ERROR(-20932, 'reentered');"
                                             + " ELSIF r <> 0 THEN RAISE_APPLICATION_ERROR(-20934, 'request failed ' || r);"
                                             + " END IF; END;";
        private static final String VERIFY = ALLOCATE
                                           + "r := DBMS_LOCK.REQUEST(h, DBMS_LOCK.X_MODE, 0, FALSE);"
                                           + " IF r <> 4 THEN RAISE_APPLICATION_ERROR(-20933, 'not held'); END IF; END;";
        private static final String RELEASE = ALLOCATE + "r := DBMS_LOCK.RELEASE(h); END;";

        static Promise<MigrationFence> acquire(SqlConnector session, String datasource, String nodeId) {
            return session.update(TRY_LOCK, "aether-mig:" + nodeId)
                          .mapError(cause -> refusal(datasource, cause))
                          .flatMap(_ -> verified(session, new OracleFence(datasource)));
        }

        private static Cause refusal(String datasource, Cause cause) {
            var text = cause.message();

            if (text.contains(HELD_CODE)) {
                return migrationFencedInDatabase(datasource,
                                                 "another session holds DBMS_LOCK AETHER_MIG_<schema> (see v$lock, type UL)");
            }

            if (text.contains(REENTERED_CODE)) {
                return fenceUnavailable(datasource,
                                        "this server session already held the fence before taking it: a connection pooler"
                                       + " shares server sessions between clients; migrations need a direct connection or session pooling");
            }

            return isMissingGrant(text)
                   ? fenceUnavailable(datasource,
                                      "the migration fence needs EXECUTE on SYS.DBMS_LOCK; run: GRANT EXECUTE ON SYS.DBMS_LOCK TO <migration user>")
                   : cause;
        }

        private static boolean isMissingGrant(String text) {
            return text.contains("PLS-00201") || text.contains("ORA-06550") || text.contains("ORA-00942") || text.contains("ORA-04042");
        }

        @Override
        public Promise<Unit> verifyHeld(SqlConnector session) {
            return session.update(VERIFY)
                          .mapError(cause -> cause.message()
                                                  .contains("ORA-20933")
                                            ? fenceUnavailable(datasource, "this session does not hold the fence it took")
                                            : cause)
                          .mapToUnit();
        }

        @Override
        public Promise<Unit> release(SqlConnector session) {
            return session.update(RELEASE)
                          .mapToUnit();
        }
    }

    /// The PostgreSQL session advisory lock (classid `0x41455448` "AETH", objid 1), per database.
    record PgFence(String datasource) implements MigrationFence {
        private static final Logger LOG = LoggerFactory.getLogger(PgFence.class);
        static final int LOCK_CLASS = 0x41455448;
        static final int LOCK_OBJECT = 1;

        private static final String KEY_FILTER = "locktype = 'advisory' AND classid = " + LOCK_CLASS
                                               + " AND objid = " + LOCK_OBJECT
                                               + " AND objsubid = 2 AND granted";

        private static final String VERIFY = "SELECT count(*)::int AS n FROM pg_locks WHERE pid = pg_backend_pid() AND " + KEY_FILTER;

        /// `pg_try_advisory_lock` is re-entrant: a server session that ALREADY holds the fence is granted it again, and
        /// [#verifyHeld] then agrees. A transaction-mode pooler hands one server session to many clients, so a second
        /// runner would pass the fence through the first runner's backend. A session must therefore not hold the fence
        /// before it takes it; the check and the grant are one statement, so they run on one backend. -1: already
        /// held by this server session; 1: granted; 0: held by another session.
        private static final String TRY_LOCK = "SELECT CASE WHEN EXISTS (SELECT 1 FROM pg_locks WHERE pid = pg_backend_pid() AND " + KEY_FILTER
                                             + ") THEN -1 WHEN pg_try_advisory_lock(" + LOCK_CLASS
                                             + ", " + LOCK_OBJECT
                                             + ") THEN 1 ELSE 0 END AS granted";

        private static final String HOLDER = "SELECT 'pid=' || a.pid || ', application_name=' || coalesce(a.application_name, '')"
                                           + " || ', client=' || coalesce(host(a.client_addr), 'local')"
                                           + " || ', since=' || coalesce(a.backend_start::text, '?') AS holder"
                                           + " FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid"
                                           + " WHERE l.locktype = 'advisory' AND l.classid = " + LOCK_CLASS
                                           + " AND l.objid = " + LOCK_OBJECT
                                           + " AND l.objsubid = 2 AND l.granted"
                                           + " AND l.database = (SELECT oid FROM pg_database WHERE datname = current_database())"
                                           + " LIMIT 1";

        /// §2.5: bound the server's detection of a silently dead holder to about 2 minutes. Each is best effort.
        private static final List<String> KEEPALIVES = List.of("tcp_keepalives_idle=60",
                                                               "tcp_keepalives_interval=10",
                                                               "tcp_keepalives_count=3",
                                                               "client_connection_check_interval=10s");

        static Promise<MigrationFence> acquire(SqlConnector session, String datasource, String nodeId) {
            return session.queryOne("SELECT version() AS v",
                                    row -> row.getString("v"))
                          .flatMap(version -> fenceFor(session, datasource, nodeId, version));
        }

        private static Promise<MigrationFence> fenceFor(SqlConnector session,
                                                        String datasource,
                                                        String nodeId,
                                                        String version) {
            return isCockroach(version)
                   ? cockroach(datasource)
                   : lockPostgres(session, datasource, nodeId);
        }

        private static Promise<MigrationFence> cockroach(String datasource) {
            LOG.warn("Migration fence: datasource '{}' is CockroachDB configured as PostgreSQL; it has no session lock, so this run takes the tier-3 per-script lock only",
                     datasource);

            return Promise.success(ScriptLock.cockroach());
        }

        static boolean isCockroach(String version) {
            return version.toLowerCase(Locale.ROOT)
                          .contains("cockroachdb");
        }

        private static Promise<MigrationFence> lockPostgres(SqlConnector session, String datasource, String nodeId) {
            return nameSession(session, nodeId).flatMap(_ -> setKeepalives(session))
                              .flatMap(_ -> session.queryOne(TRY_LOCK,
                                                             row -> row.getInt("granted")))
                              .flatMap(granted -> grantedOrHeld(session, datasource, granted));
        }

        private static Promise<MigrationFence> grantedOrHeld(SqlConnector session, String datasource, int granted) {
            return switch (granted) {
                case 1 -> verified(session, new PgFence(datasource));
                case 0 -> heldBy(session, datasource);
                default -> sharedServerSession(datasource);
            };
        }

        private static Promise<Unit> nameSession(SqlConnector session, String nodeId) {
            return session.queryOne("SELECT set_config('application_name', ?, false) AS v",
                                    row -> row.getString("v"),
                                    "aether-mig:" + nodeId)
                          .mapToUnit();
        }

        private static Promise<Unit> setKeepalives(SqlConnector session) {
            var result = Promise.unitPromise();

            for (var setting : KEEPALIVES) {
                result = result.flatMap(_ -> setBestEffort(session, setting));
            }

            return result;
        }

        /// FER (degrade forward): a refused keepalive `SET` (an older server, a managed service that forbids it) leaves
        /// the fence fully held; only silent-death detection falls back to the server's defaults (§2.5). Logged, never
        /// fatal, per the design.
        private static Promise<Unit> setBestEffort(SqlConnector session, String setting) {
            var parts = setting.split("=", 2);

            return session.queryOne("SELECT set_config(?, ?, false) AS v",
                                    row -> row.getString("v"),
                                    parts[0],
                                    parts[1])
                          .mapToUnit()
                          .onFailure(cause -> LOG.info("Migration fence: session setting {} refused, keeping the server default: {}",
                                                       setting,
                                                       cause.message()))
                          .recover(_ -> Unit.unit());
        }

        private static Promise<MigrationFence> heldBy(SqlConnector session, String datasource) {
            return session.queryOptional(HOLDER,
                                         row -> row.getString("holder"))
                          .flatMap(holder -> migrationFencedInDatabase(datasource,
                                                                       holder.or("holder not visible; it may have just ended")).promise());
        }

        @Override
        public Promise<Unit> verifyHeld(SqlConnector session) {
            return session.queryOne(VERIFY, COUNT)
                          .flatMap(count -> requireHeld(count, datasource));
        }

        @Override
        public Promise<Unit> release(SqlConnector session) {
            return session.queryList("SELECT pg_advisory_unlock_all()", IGNORED)
                          .mapToUnit();
        }
    }

    /// The MySQL/MariaDB named user lock, per server and schema.
    record MysqlFence(String datasource, String lockName) implements MigrationFence {
        private static final String PREFIX = "aether_mig:";
        private static final int HASH_HEX = 40;

        static Promise<MigrationFence> acquire(SqlConnector session, String datasource) {
            return refuseNodeLocalTopologies(session, datasource).flatMap(_ -> session.queryOne("SELECT COALESCE(DATABASE(), '') AS db",
                                                                                                row -> row.getString("db")))
                                            .flatMap(schema -> lockSchema(session, datasource, schema));
        }

        /// §2.6: Galera and multi-primary group replication make `GET_LOCK` node-local, so a second primary would not
        /// see the fence.
        private static Promise<Unit> refuseNodeLocalTopologies(SqlConnector session, String datasource) {
            return session.queryList("SHOW VARIABLES WHERE Variable_name IN ('wsrep_on', 'group_replication_single_primary_mode')",
                                     MysqlFence::variable)
                          .flatMap(variables -> refuseIfNodeLocal(variables, datasource));
        }

        private static Result<String> variable(RowMapper.RowAccessor row) {
            return row.getString("Variable_name")
                      .flatMap(name -> row.getString("Value")
                                          .map(value -> name.toLowerCase(Locale.ROOT)
                                                       + "=" + value.toUpperCase(Locale.ROOT)));
        }

        private static Promise<Unit> refuseIfNodeLocal(List<String> variables, String datasource) {
            if (variables.contains("wsrep_on=ON")) {
                return fenceUnavailable(datasource, "Galera (wsrep_on=ON): GET_LOCK is node-local").promise();
            }

            if (variables.contains("group_replication_single_primary_mode=OFF")) {
                return fenceUnavailable(datasource, "multi-primary group replication: GET_LOCK is node-local").promise();
            }

            return Promise.unitPromise();
        }

        private static Promise<MigrationFence> lockSchema(SqlConnector session, String datasource, String schema) {
            if (schema.isEmpty()) {
                return fenceUnavailable(datasource, "the connection has no default schema to fence").promise();
            }

            return lockName(schema).async()
                           .flatMap(name -> tryLock(session, datasource, name));
        }

        /// `aether_mig:` + the first 40 hex of SHA-256(lower(schema)): 51 characters (design F6).
        static Result<String> lockName(String schema) {
            return Result.lift(Causes::fromThrowable,
                               () -> MessageDigest.getInstance("SHA-256"))
                         .map(digest -> PREFIX + HexFormat.of()
                                                          .formatHex(digest.digest(schema.toLowerCase(Locale.ROOT)
                                                                                         .getBytes(StandardCharsets.UTF_8)))
                                                          .substring(0, HASH_HEX));
        }

        private static Promise<MigrationFence> tryLock(SqlConnector session, String datasource, String name) {
            return session.queryOne("SELECT CASE WHEN IS_USED_LOCK(?) = CONNECTION_ID() THEN -1 ELSE COALESCE(GET_LOCK(?, 0), 0) END AS granted",
                                    row -> row.getInt("granted"),
                                    name,
                                    name)
                          .flatMap(granted -> grantedOrHeld(session, datasource, name, granted));
        }

        private static Promise<MigrationFence> grantedOrHeld(SqlConnector session,
                                                             String datasource,
                                                             String name,
                                                             int granted) {
            return switch (granted) {
                case 1 -> verified(session, new MysqlFence(datasource, name));
                case 0 -> heldBy(session, datasource, name);
                default -> sharedServerSession(datasource);
            };
        }

        private static Promise<MigrationFence> heldBy(SqlConnector session, String datasource, String name) {
            return session.queryOptional("SELECT CONCAT('id=', p.ID, ', user=', p.USER, ', host=', p.HOST, ', running_for_s=', p.TIME)"
                                        + " AS holder FROM information_schema.PROCESSLIST p WHERE p.ID = IS_USED_LOCK(?)",
                                         row -> row.getString("holder"),
                                         name)
                          .flatMap(holder -> migrationFencedInDatabase(datasource,
                                                                       holder.or("holder not visible; it may have just ended")).promise());
        }

        @Override
        public Promise<Unit> verifyHeld(SqlConnector session) {
            return session.queryOne("SELECT COALESCE(IS_USED_LOCK(?) = CONNECTION_ID(), 0) AS n",
                                    COUNT,
                                    lockName)
                          .flatMap(held -> requireHeld(held, datasource));
        }

        @Override
        public Promise<Unit> release(SqlConnector session) {
            return session.queryList("SELECT RELEASE_ALL_LOCKS()", IGNORED)
                          .mapToUnit();
        }
    }

    /// `GET_LOCK` and `pg_try_advisory_lock` are re-entrant, so a session that already held the fence before taking it
    /// is shared with another client: a transaction-mode connection pooler.
    private static Promise<MigrationFence> sharedServerSession(String datasource) {
        return fenceUnavailable(datasource,
                                "this server session already held the fence before taking it: a connection pooler shares"
                               + " server sessions between clients (transaction pooling); migrations need a direct"
                               + " connection or session pooling").promise();
    }

    private static Promise<MigrationFence> verified(SqlConnector session, MigrationFence fence) {
        return fence.verifyHeld(session)
                    .map(_ -> fence);
    }

    private static Promise<Unit> requireHeld(int held, String datasource) {
        return held > 0
               ? Promise.unitPromise()
               : fenceUnavailable(datasource,
                                  "this session does not hold the fence it took: a connection pooler swapped the session,"
                                 + " or the engine's lock acquired nothing").promise();
    }
}
