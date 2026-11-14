// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.scheduler.pg;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.pragmatica.aether.resource.db.PgSqlConnector;
import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.aether.resource.db.RowMapper.RowAccessor;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.vo.NonBlankString;


/// The SQL of the scheduler: one table, `scheduler_job`, whose row is both the job and its lease.
///
/// **Clock.** Every time decision (is a job due, has a lease expired, when does a lease end, when is a failed job due
/// again) is taken by PostgreSQL's `now()`, never by the local clock: the lease is judged by the same authority that
/// stores it, so instances with skewed clocks cannot disagree about whether a lease is live. The local [Clock] is read
/// only by [#observedSkew()], a diagnostic.
///
/// **Claim** ([#claimDue]): one statement, so one transaction. The inner `SELECT ... FOR UPDATE SKIP LOCKED` locks the
/// candidate rows; under READ COMMITTED, `FOR UPDATE` re-checks the WHERE against the newest committed version of a row
/// that changed after the statement's snapshot, so a row another instance has just claimed is no longer a candidate.
/// The token is raised under that lock, so every claim of a job carries a greater token than every earlier claim.
/// `SKIP LOCKED` only keeps a concurrent poll from waiting on rows another poll is claiming.
///
/// **Fenced writes** ([#complete], [#reschedule]): applied only while the row still carries the writer's token and is
/// claimed; otherwise [Completion#REFUSED].
final class PgJobStore {
    private static final String LOCK_SCHEMA = "SELECT 1 AS locked FROM pg_advisory_xact_lock(hashtext('pragmatica.scheduler_job'))";

    private static final String CREATE_TABLE = """
        CREATE TABLE IF NOT EXISTS scheduler_job (
            id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            name        TEXT        NOT NULL,
            fire_at     TIMESTAMPTZ NOT NULL,
            payload     BYTEA       NOT NULL,
            status      TEXT        NOT NULL CHECK (status IN ('SCHEDULED', 'CLAIMED')),
            attempt     INT         NOT NULL DEFAULT 0,
            fence_token BIGINT      NOT NULL DEFAULT 0,
            lease_until TIMESTAMPTZ,
            claimed_by  TEXT)""";

    private static final String CREATE_INDEX = "CREATE INDEX IF NOT EXISTS scheduler_job_due ON scheduler_job (status, fire_at)";

    private static final String INSERT = """
        INSERT INTO scheduler_job (name, fire_at, payload, status)
        VALUES (?, now() + ?::bigint * interval '1 millisecond', ?, 'SCHEDULED')
        RETURNING id""";

    private static final String DELETE = "DELETE FROM scheduler_job WHERE id = ?";
    private static final String CLAIM = claimSql("now()");

    private static final String COMPLETE = "DELETE FROM scheduler_job WHERE id = ? AND fence_token = ? AND status = 'CLAIMED'";

    private static final String RESCHEDULE = """
        UPDATE scheduler_job
           SET status = 'SCHEDULED', fire_at = now() + ?::bigint * interval '1 millisecond',
               lease_until = NULL, claimed_by = NULL
         WHERE id = ? AND fence_token = ? AND status = 'CLAIMED'""";

    private static final String DB_NOW_MILLIS = "SELECT (extract(epoch FROM now()) * 1000)::bigint AS now_ms";
    private static final RowMapper<JobId> JOB_ID = row -> row.getLong("id")
                                                             .map(JobId::jobId);
    private static final RowMapper<FiredJob> FIRED_JOB = PgJobStore::firedJob;
    private static final RowMapper<Long> NOW_MS = row -> row.getLong("now_ms");
    private static final RowMapper<Integer> LOCKED = row -> row.getInt("locked");

    private final PgSqlConnector connector;
    private final Clock clock;

    private PgJobStore(PgSqlConnector connector, Clock clock) {
        this.connector = connector;
        this.clock = clock;
    }

    static PgJobStore pgJobStore(PgSqlConnector connector, Clock clock) {
        return new PgJobStore(connector, clock);
    }

    /// Creates the table and its index when absent. Concurrent installers serialize on a transaction-scoped advisory
    /// lock, so two instances starting together cannot race on `CREATE TABLE IF NOT EXISTS`.
    Promise<Unit> installSchema() {
        return connector.transactional(PgJobStore::createSchema);
    }

    /// Inserts a job due `delay` after the database's `now()`; a negative delay schedules it in the past.
    Promise<JobId> schedule(String name, Duration delay, byte[] payload) {
        return Result.lift(PgJobStore::delayOutOfRange, delay::toMillis)
                     .async()
                     .flatMap(millis -> connector.queryOne(INSERT, JOB_ID, name, millis, payload));
    }

    /// `Duration.toMillis` throws ArithmeticException beyond about 292 million years; lifted here, where it is called.
    private static Cause delayOutOfRange(Throwable thrown) {
        return SchedulerError.DelayOutOfRange.delayOutOfRange(Causes.fromThrowable(thrown));
    }

    Promise<Unit> cancel(JobId id) {
        return connector.update(DELETE,
                                id.value())
                        .mapToUnit();
    }

    /// Claims up to `batchSize` jobs that are due or whose lease has expired, oldest `fire_at` first.
    Promise<List<FiredJob>> claimDue(NonBlankString claimer, TimeSpan lease, int batchSize) {
        return connector.queryList(CLAIM, FIRED_JOB, lease.millis(), claimer.value(), batchSize);
    }

    /// Completes the job by deleting its row, only while the row still carries this firing's token.
    Promise<Completion> complete(FiredJob job) {
        return connector.update(COMPLETE,
                                job.id().value(),
                                job.fenceToken())
                        .map(PgJobStore::completion);
    }

    /// Returns the job to SCHEDULED, due `delay` after the database's `now()`, only while the row still carries this
    /// firing's token. Used after a failed firing and, with a zero delay, to release a claim on close.
    Promise<Completion> reschedule(FiredJob job, TimeSpan delay) {
        return connector.update(RESCHEDULE,
                                delay.millis(),
                                job.id().value(),
                                job.fenceToken())
                        .map(PgJobStore::completion);
    }

    /// Local clock minus database clock, as observed now (diagnostic only; includes one query's latency).
    Promise<Duration> observedSkew() {
        return connector.queryOne(DB_NOW_MILLIS, NOW_MS)
                        .map(dbMillis -> Duration.ofMillis(clock.millis() - dbMillis));
    }

    /// The claim statement with its time source as the one parameter, so the source of every claim decision is
    /// visible in one place. Production uses only `now()` (see [#CLAIM]).
    private static String claimSql(String now) {
        return """
            UPDATE scheduler_job
               SET status = 'CLAIMED', attempt = attempt + 1, fence_token = fence_token + 1,
                   lease_until = %1$s + ?::bigint * interval '1 millisecond', claimed_by = ?
             WHERE id IN (SELECT id FROM scheduler_job
                           WHERE (status = 'SCHEDULED' AND fire_at <= %1$s)
                              OR (status = 'CLAIMED' AND lease_until <= %1$s)
                           ORDER BY fire_at, id
                           LIMIT ?
                           FOR UPDATE SKIP LOCKED)
            RETURNING id, name, payload, attempt, fence_token""".formatted(now);
    }

    private static Promise<Unit> createSchema(SqlConnector tx) {
        return tx.queryOne(LOCK_SCHEMA, LOCKED)
                 .flatMap(_ -> tx.update(CREATE_TABLE))
                 .flatMap(_ -> tx.update(CREATE_INDEX))
                 .mapToUnit();
    }

    private static Result<FiredJob> firedJob(RowAccessor row) {
        return Result.all(row.getLong("id").map(JobId::jobId),
                          row.getString("name"),
                          row.getBytes("payload"),
                          row.getInt("attempt"),
                          row.getLong("fence_token"))
                     .map(FiredJob::firedJob);
    }

    private static Completion completion(int rows) {
        return rows == 1
               ? Completion.ACCEPTED
               : Completion.REFUSED;
    }
}
