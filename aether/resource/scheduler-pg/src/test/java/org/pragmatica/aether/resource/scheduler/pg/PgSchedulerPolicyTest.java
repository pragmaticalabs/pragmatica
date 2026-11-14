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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Policy of one instance: catch-up order, retry, cancel, close releasing the lease.
/// Live-PostgreSQL scheduler tests (production connector); every `awaitTrue` carries a deadline. Self-skips without Docker
/// (fails under `-Daether.requireDocker=true`).
class PgSchedulerPolicyTest extends PgSchedulerTestBase {
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
        assertThat(holder.close().await(timeSpan(5).seconds()).isSuccess()).as("close settles within its grace").isTrue();
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

    @Test
    void failedHandler_isDueAgainOnlyAfterRetryDelay() {
        var startsNs = new CopyOnWriteArrayList<Long>();
        var scheduler = start(instance("flaky",
                                       20,
                                       30_000,
                                       3_000,
                                       1_000,
                                       1,
                                       Clock.systemUTC(),
                                       job -> {
                                           startsNs.add(System.nanoTime());

                                           return job.attempt() == 1
                                                  ? SchedulerError.General.CLOSED.promise()
                                                  : done();
                                       }));

        schedule(scheduler, "flaky", DUE);
        awaitTrue("job retried", DEADLINE_MS, () -> startsNs.size() >= 2);
        assertThat((startsNs.get(1) - startsNs.get(0)) / 1_000_000L).as("ms between the failed attempt and its retry")
                                                                    .isGreaterThanOrEqualTo(2_500);
    }

    @Test
    void afterClose_dueJobIsNeverClaimed() {
        var scheduler = start(instance("solo", 300, 30_000, 100, 200, 1, Clock.systemUTC(), _ -> done()));

        scheduler.close().await().unwrap();

        var id = schedule(scheduler, "late", DUE);

        sleep(800);
        assertThat(tokenOf(id)).as("the poll armed before close (due at 300 ms) must not claim: token still 0").isZero();
        assertThat(statusOf(id)).isEqualTo("SCHEDULED");
    }

    @Test
    void close_waitsForInFlightFiringWithinGrace_thenItsCompletionIsApplied() {
        var fired = new AtomicInteger();
        var finished = new AtomicBoolean();
        var holder = start(instance("holder", 20, 60_000, 100, 5_000, 1, Clock.systemUTC(), _ -> {
            fired.incrementAndGet();

            return after(700).onSuccessRun(() -> finished.set(true));
        }));
        schedule(holder, "settles-in-grace", DUE);

        awaitTrue("holder started the firing", DEADLINE_MS, () -> fired.get() == 1);
        assertThat(finished.get()).as("the handler is still running when close is called").isFalse();
        assertThat(holder.close().await(timeSpan(10).seconds()).isSuccess()).as("close settles").isTrue();
        assertThat(finished.get()).as("close waited for the in-flight handler").isTrue();
        assertThat(count()).as("the completion was applied before close returned, so the job is deleted").isZero();
        assertThat(fired.get()).as("the job was neither released nor re-fired").isEqualTo(1);
    }

    @Test
    void settledFirings_completedAndFailed_areForgotten() {
        var scheduler = start(instance("solo", 20, 30_000, 100, 200, 4, Clock.systemUTC(), job -> job.name().equals("flaky") && job.attempt() == 1
                                                                                                   ? SchedulerError.General.CLOSED.promise()
                                                                                                   : done()));

        schedule(scheduler, "flaky", DUE);
        for (var i = 0; i < 3; i++) {
            schedule(scheduler, "job-" + i, DUE);
        }

        awaitTrue("all four jobs, including the retried one, completed", 10_000, () -> count() == 0);
        awaitTrue("no firing is tracked once all have settled", 5_000, () -> scheduler.firingsTracked() == 0);
    }

    @Test
    void tick_afterClose_claimsNothing() {
        var scheduler = start(instance("solo", 60_000, 30_000, 100, 200, 1, Clock.systemUTC(), _ -> done()));
        var id = schedule(scheduler, "late", DUE);

        scheduler.close().await().unwrap();
        scheduler.tick();
        sleep(500);
        assertThat(tokenOf(id)).as("a tick that runs after close must not claim: token still 0").isZero();
        assertThat(statusOf(id)).isEqualTo("SCHEDULED");
    }

    @Test
    void tick_beforeClose_claimsDueJob_controlForTheClosedCheck() {
        var fired = new AtomicBoolean();
        var scheduler = instance("solo", 60_000, 30_000, 100, 200, 1, Clock.systemUTC(), _ -> {
            fired.set(true);

            return done();
        });

        started.add(scheduler);
        schedule(scheduler, "now", DUE);
        scheduler.tick();
        awaitTrue("a direct tick on an open scheduler claims and fires the due job", 10_000, fired::get);
    }

    @Test
    void schedule_delayBeyondMillisRange_failsThePromiseInsteadOfThrowing() {
        var scheduler = instance("solo", 20, 500, 100, 200, 1, Clock.systemUTC(), _ -> done());

        assertThatCode(() -> scheduler.schedule("x", Duration.ofSeconds(Long.MAX_VALUE), PAYLOAD)).doesNotThrowAnyException();

        boolean outOfRange = scheduler.schedule("x", Duration.ofSeconds(Long.MAX_VALUE), PAYLOAD)
                                      .await()
                                      .fold(cause -> cause instanceof SchedulerError.DelayOutOfRange, _ -> false);

        assertThat(outOfRange).as("schedule fails with DelayOutOfRange").isTrue();
        assertThat(count()).as("nothing was written").isZero();
    }
}
