// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.scheduler.pg;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.resource.db.PgSqlConnector;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.resource.scheduler.pg.PgSchedulerFixture.*;
import static org.assertj.core.api.Assertions.assertThat;


/// The lease + fencing-token contract of [PgScheduler] against a LIVE PostgreSQL 16 through the production connector.
/// Every `awaitTrue` carries a deadline. Self-skips when no Docker daemon is reachable (fails under
/// `-Daether.requireDocker=true`).
class PgSchedulerTest {
    private static final byte[] PAYLOAD = {1, 2, 3};

    private static final Duration DUE = Duration.ofSeconds(-1);
    private static final long DEADLINE_MS = 30_000;

    private final List<PgScheduler> started = new ArrayList<>();
    private final List<Promise<Unit>> gates = new ArrayList<>();
    private PgSqlConnector admin;

    @BeforeAll
    static void startPostgres() {
        PgSchedulerFixture.startPostgres();
    }

    @AfterAll
    static void stopPostgres() {
        PgSchedulerFixture.stopPostgres();
    }

    @BeforeEach
    void emptyTable() {
        admin = newConnector();
        admin.update("DELETE FROM scheduler_job").await().unwrap();
    }

    @AfterEach
    void stopSchedulers() {
        gates.forEach(PgSchedulerFixture::open);
        started.forEach(s -> s.close()
                              .await());
    }

    private PgScheduler start(PgScheduler scheduler) {
        started.add(scheduler);
        scheduler.start().await().unwrap();

        return scheduler;
    }

    private PgScheduler instance(String id,
                                 long pollMs,
                                 long leaseMs,
                                 long retryMs,
                                 long graceMs,
                                 int batch,
                                 Clock clock,
                                 JobHandler handler) {
        return PgScheduler.pgScheduler(newConnector(),
                                       config(id, pollMs, leaseMs, retryMs, graceMs, batch),
                                       handler,
                                       clock);
    }

    private Promise<Unit> hang() {
        Promise<Unit> gate = Promise.promise();

        gates.add(gate);

        return gate;
    }

    private long count() {
        return admin.queryOne("SELECT count(*)::bigint AS n FROM scheduler_job",
                              row -> row.getLong("n"))
                    .await()
                    .unwrap();
    }

    private long tokenOf(JobId id) {
        return admin.queryOne("SELECT fence_token FROM scheduler_job WHERE id = ?",
                              row -> row.getLong("fence_token"),
                              id.value())
                    .await()
                    .unwrap();
    }

    private String statusOf(JobId id) {
        return admin.queryOne("SELECT status FROM scheduler_job WHERE id = ?",
                              row -> row.getString("status"),
                              id.value())
                    .await()
                    .unwrap();
    }

    private JobId schedule(PgScheduler scheduler, String name, Duration delay) {
        return scheduler.schedule(name, delay, PAYLOAD)
                        .await()
                        .unwrap();
    }

    private static Clock skewed(Duration by) {
        return Clock.fixed(Instant.now().plus(by),
                           ZoneOffset.UTC);
    }

    @Test
    void twoInstances_dueJobs_fireAtLeastOnceAndNeverConcurrentlyWhileLeaseHeld() {
        var jobs = 60;
        var active = new ConcurrentHashMap<Long, AtomicInteger>();
        var firings = new ConcurrentHashMap<Long, AtomicInteger>();
        var overlap = new AtomicBoolean();
        JobHandler handler = job -> {
            var running = active.computeIfAbsent(job.id().value(),
                                                 _ -> new AtomicInteger())
                                .incrementAndGet();

            firings.computeIfAbsent(job.id().value(),
                                    _ -> new AtomicInteger())
                   .incrementAndGet();
            overlap.compareAndSet(false, running > 1);

            return after(15).onResultRun(() -> active.get(job.id().value())
                                                     .decrementAndGet());
        };
        var a = instance("a", 10, 30_000, 100, 5_000, 5, Clock.systemUTC(), handler);
        var b = instance("b", 10, 30_000, 100, 5_000, 5, Clock.systemUTC(), handler);
        var ids = new ArrayList<JobId>();

        for (var i = 0; i < jobs; i++) {
            ids.add(schedule(a, "job-" + i, DUE));
        }

        var arrived = new AtomicInteger();
        var racers = List.of(new Thread(() -> raceStart(arrived, a)), new Thread(() -> raceStart(arrived, b)));

        started.addAll(List.of(a, b));
        racers.forEach(Thread::start);
        awaitTrue("every due job fires", DEADLINE_MS, () -> firings.size() == jobs);
        awaitTrue("every fired job is completed", DEADLINE_MS, () -> count() == 0);
        assertThat(overlap).as("a job fired on both instances at once while its lease was held").isFalse();
        assertThat(firings.values()).allSatisfy(n -> assertThat(n.get()).isGreaterThanOrEqualTo(1));
    }

    private static void raceStart(AtomicInteger arrived, PgScheduler scheduler) {
        arrived.incrementAndGet();
        awaitTrue("both racers at the barrier", 10_000, () -> arrived.get() == 2);
        scheduler.start().await().unwrap();
    }

    @Test
    void killedHolder_afterLeaseExpiry_isReclaimedWithHigherTokenAndRefired() {
        var firstToken = new AtomicLong();
        var second = new CopyOnWriteArrayList<FiredJob>();
        var holder = start(instance("holder",
                                    20,
                                    600,
                                    100,
                                    100,
                                    1,
                                    Clock.systemUTC(),
                                    job -> {
                                        firstToken.set(job.fenceToken());

                                        return hang();
                                    }));
        var id = schedule(holder, "orphan", DUE);

        awaitTrue("holder claimed the job", DEADLINE_MS, () -> firstToken.get() > 0);
        start(instance("takeover",
                       20,
                       5_000,
                       100,
                       100,
                       1,
                       Clock.systemUTC(),
                       job -> {
                           second.add(job);

                           return done();
                       }));
        awaitTrue("job re-fired after the holder's lease expired", DEADLINE_MS, () -> !second.isEmpty());
        assertThat(second.getFirst().id()).isEqualTo(id);
        assertThat(second.getFirst().fenceToken()).isGreaterThan(firstToken.get());
        assertThat(second.getFirst().attempt()).isEqualTo(2);
    }

    @Test
    void staleHolder_completionAfterTakeover_isRefusedByToken() {
        var firstToken = new AtomicLong();
        var staleGate = new AtomicReference<Promise<Unit>>();
        var secondFired = new AtomicBoolean();
        var holder = start(instance("holder",
                                    20,
                                    600,
                                    100,
                                    2_000,
                                    1,
                                    Clock.systemUTC(),
                                    job -> {
                                        staleGate.set(hang());
                                        firstToken.set(job.fenceToken());

                                        return staleGate.get();
                                    }));
        var id = schedule(holder, "contested", DUE);

        awaitTrue("holder claimed the job", DEADLINE_MS, () -> firstToken.get() > 0);
        start(instance("takeover",
                       20,
                       30_000,
                       100,
                       100,
                       1,
                       Clock.systemUTC(),
                       job -> {
                           secondFired.set(true);

                           return hang();
                       }));
        awaitTrue("takeover fired the job", DEADLINE_MS, secondFired::get);
        assertThat(tokenOf(id)).isEqualTo(2);
        open(staleGate.get());
        holder.close().await();
        assertThat(count()).as("the stale holder's completion must not delete the takeover's row").isEqualTo(1);
        assertThat(tokenOf(id)).isEqualTo(2);
        assertThat(statusOf(id)).isEqualTo("CLAIMED");
    }

    @Test
    void pastDatedJobs_fireOncePerJobOldestFirst() {
        var order = new CopyOnWriteArrayList<String>();
        var scheduler = instance("solo",
                                 20,
                                 30_000,
                                 100,
                                 1_000,
                                 1,
                                 Clock.systemUTC(),
                                 job -> {
                                     order.add(job.name());

                                     return done();
                                 });

        started.add(scheduler);
        for (var seconds : new int[]{3, 5, 1, 4, 2}) {
            schedule(scheduler, "ago-" + seconds, Duration.ofSeconds(-seconds));
        }

        scheduler.start().await().unwrap();
        awaitTrue("all five past-dated jobs fired", DEADLINE_MS, () -> order.size() >= 5);
        sleep(300);
        assertThat(order).containsExactly("ago-5", "ago-4", "ago-3", "ago-2", "ago-1");
        assertThat(count()).isZero();
    }

    @Test
    void failedHandler_isRetriedWithIncrementedAttempt() {
        var attempts = new CopyOnWriteArrayList<Integer>();
        var scheduler = instance("flaky",
                                 20,
                                 30_000,
                                 100,
                                 1_000,
                                 1,
                                 Clock.systemUTC(),
                                 job -> {
                                     attempts.add(job.attempt());

                                     return job.attempt() == 1
                                            ? SchedulerError.General.CLOSED.promise()
                                            : done();
                                 });

        started.add(scheduler);
        schedule(scheduler, "flaky", DUE);
        scheduler.start().await().unwrap();
        awaitTrue("job retried after the failure", DEADLINE_MS, () -> attempts.size() >= 2 && count() == 0);
        assertThat(attempts).containsExactly(1, 2);
    }

    @Test
    void cancel_beforeClaim_neverFires() {
        var fired = new AtomicBoolean();
        var scheduler = instance("solo",
                                 20,
                                 30_000,
                                 100,
                                 1_000,
                                 1,
                                 Clock.systemUTC(),
                                 job -> {
                                     fired.set(true);

                                     return done();
                                 });

        started.add(scheduler);
        scheduler.cancel(schedule(scheduler, "cancelled", DUE)).await().unwrap();
        scheduler.start().await().unwrap();
        sleep(400);
        assertThat(fired).isFalse();
    }

    @Test
    void clockAheadInstance_doesNotFireFutureJobsOrStealLiveLease() {
        var holderFired = new AtomicBoolean();
        var skewedFired = new CopyOnWriteArrayList<String>();
        var holder = start(instance("holder",
                                    20,
                                    30_000,
                                    100,
                                    100,
                                    1,
                                    Clock.systemUTC(),
                                    job -> {
                                        holderFired.set(true);

                                        return hang();
                                    }));
        var held = schedule(holder, "held", DUE);

        awaitTrue("holder claimed the job", DEADLINE_MS, holderFired::get);
        var future = schedule(holder, "tomorrow", Duration.ofHours(1));

        start(instance("fast",
                       20,
                       30_000,
                       100,
                       100,
                       5,
                       skewed(Duration.ofDays(1)),
                       job -> {
                           skewedFired.add(job.name());

                           return done();
                       }));
        sleep(500);
        assertThat(skewedFired).as("a +1 day local clock must change nothing: only the database clock decides")
                  .isEmpty();
        assertThat(tokenOf(held)).isEqualTo(1);
        assertThat(statusOf(future)).isEqualTo("SCHEDULED");
    }

    @Test
    void clockBehindInstance_stillFiresDueJob() {
        var fired = new CopyOnWriteArrayList<String>();
        var scheduler = instance("slow",
                                 20,
                                 30_000,
                                 100,
                                 100,
                                 5,
                                 skewed(Duration.ofDays(-1)),
                                 job -> {
                                     fired.add(job.name());

                                     return done();
                                 });

        started.add(scheduler);
        schedule(scheduler, "due", DUE);
        scheduler.start().await().unwrap();
        awaitTrue("a -1 day local clock still fires a due job", DEADLINE_MS, () -> fired.size() == 1);
    }

    @Test
    void close_releasesHeldLease_soAnotherInstanceClaimsAtOnce() {
        var claimed = new AtomicBoolean();
        var takeover = new CopyOnWriteArrayList<FiredJob>();
        var holder = instance("holder",
                              20,
                              60_000,
                              100,
                              200,
                              1,
                              Clock.systemUTC(),
                              job -> {
                                  claimed.set(true);

                                  return hang();
                              });
        var id = schedule(holder, "released", DUE);

        start(holder);
        awaitTrue("holder claimed the job", DEADLINE_MS, claimed::get);
        assertThat(statusOf(id)).isEqualTo("CLAIMED");
        holder.close().await();
        assertThat(statusOf(id)).as("close releases the claim").isEqualTo("SCHEDULED");
        start(instance("next",
                       20,
                       60_000,
                       100,
                       100,
                       1,
                       Clock.systemUTC(),
                       job -> {
                           takeover.add(job);

                           return done();
                       }));
        awaitTrue("another instance claimed it long before the 60 s lease", 10_000, () -> !takeover.isEmpty());
        assertThat(takeover.getFirst().fenceToken()).isEqualTo(2);
    }
}
