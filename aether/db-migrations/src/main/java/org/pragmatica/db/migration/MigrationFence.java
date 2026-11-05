// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.db.migration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import org.pragmatica.aether.resource.db.DatabaseType;
import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.db.migration.MigrationError.FenceUnavailable.fenceUnavailable;
import static org.pragmatica.db.migration.MigrationError.MigrationFencedInDatabase.migrationFencedInDatabase;


/// The database-side migration fence, tier 1 (FDB-2a, #2591; design `design-migration-db-fence.md` §2.2). A
/// session-scoped database lock, taken on the migration's PINNED session ([SqlConnector#pinned]), held for the whole
/// run and released by closing that session. It has no TTL and no lock timeout: it lives exactly as long as the server
/// session that runs the migration's statements, so nothing tunable can undercut a script's duration.
///
/// - **PostgreSQL:** `pg_try_advisory_lock(0x41455448, 1)`, per database. The session first names itself
///   (`application_name = aether-mig:<node>`), so a holder is identifiable in `pg_stat_activity`, and shortens the
///   server's silent-death detection with TCP keepalives (§2.5, best effort).
/// - **MySQL / MariaDB:** `GET_LOCK(name, 0)`, `name` = `aether_mig:` + the first 40 hex of SHA-256(lower(schema)),
///   51 characters (MySQL refuses names over 64; lock names are case-insensitive). Galera and multi-primary group
///   replication are refused (§2.6): there `GET_LOCK` is node-local.
///
/// **Engine identity is probed, not trusted:** a PostgreSQL-configured datasource whose `version()` names CockroachDB
/// gets NO tier-1 fence ([Unfenced]): CockroachDB's advisory locks acquire nothing. Its tier-3 lock is FDB-2b; until
/// then it runs as before this fence existed, never silently: the runner logs a warning on every unfenced run, and the
/// schema orchestrator raises an operator warning ([#runsUnfenced]).
///
/// **`verifyHeld`** confirms the lock is held by THIS session. It runs inside [#acquire], before any bootstrap write (a
/// no-op lock or a pooler that swapped the session is refused before the first write), and before every write of the
/// run ([FencedSession]).
public sealed interface MigrationFence {
    /// Succeeds while this session holds the fence; fails with [MigrationError.FenceUnavailable] once it does not.
    Promise<Unit> verifyHeld(SqlConnector session);

    /// The engines with a tier-1 fence in FDB-2a: the runner takes every migration of these on a pinned session.
    static boolean isTierOne(DatabaseType type) {
        return switch (type) {
            case POSTGRESQL, MYSQL, MARIADB -> true;
            case COCKROACHDB, H2, SQLITE, DB2, SQLSERVER, ORACLE -> false;
        };
    }

    /// Takes the fence on `session`: [MigrationError.MigrationFencedInDatabase] naming the holder when another session
    /// holds it, [MigrationError.FenceUnavailable] when it cannot be held here.
    static Promise<MigrationFence> acquire(SqlConnector session, String datasource, String nodeId) {
        return switch (session.databaseType()) {
            case POSTGRESQL -> PgFence.acquire(session, datasource, nodeId);
            case MYSQL, MARIADB -> MysqlFence.acquire(session, datasource);
            case COCKROACHDB, H2, SQLITE, DB2, SQLSERVER, ORACLE -> fenceUnavailable(datasource,
                                                                                     "no tier-1 fence for " + session.databaseType()).promise();
        };
    }

    /// The courtesy unlock, run only by a session that took the fence and still holds it (never by a refused one: on a
    /// pooler's shared server session it would drop the real holder's lock). Closing the session is the real release
    /// (§2.1 rule 2), so the caller's outcome never depends on this.
    static Promise<Unit> release(SqlConnector session) {
        return switch (session.databaseType()) {
            case POSTGRESQL -> session.queryList("SELECT pg_advisory_unlock_all()", IGNORED).mapToUnit();
            case MYSQL, MARIADB -> session.queryList("SELECT RELEASE_ALL_LOCKS()", IGNORED).mapToUnit();
            case COCKROACHDB, H2, SQLITE, DB2, SQLSERVER, ORACLE -> Promise.unitPromise();
        };
    }

    /// Whether a schema operation on `connector` would run WITHOUT a tier-1 fence although its configured engine has
    /// one: a PostgreSQL-configured CockroachDB, identified by the same `version()` probe [#acquire] makes. Every other
    /// engine answers without a query.
    static Promise<Boolean> runsUnfenced(SqlConnector connector) {
        return connector.databaseType() == DatabaseType.POSTGRESQL
               ? connector.queryOne("SELECT version() AS v",
                                    row -> row.getString("v"))
                          .map(PgFence::isCockroach)
               : Promise.success(false);
    }

    RowMapper<Unit> IGNORED = _ -> Result.unitResult();
    RowMapper<Integer> COUNT = row -> row.getInt("n");

    /// A PostgreSQL-configured database that turned out to have no working tier-1 lock (CockroachDB). FDB-2b replaces
    /// this with the tier-3 per-script lock.
    enum Unfenced implements MigrationFence {
        UNFENCED;
        @Override
        public Promise<Unit> verifyHeld(SqlConnector session) {
            return Promise.unitPromise();
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
                   ? unfenced(datasource)
                   : lockPostgres(session, datasource, nodeId);
        }

        private static Promise<MigrationFence> unfenced(String datasource) {
            LOG.warn("Migration fence: datasource '{}' is CockroachDB configured as PostgreSQL; it has no tier-1 fence and this run is UNFENCED (FDB-2b adds its lock)",
                     datasource);

            return Promise.success(Unfenced.UNFENCED);
        }

        private static boolean isCockroach(String version) {
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
