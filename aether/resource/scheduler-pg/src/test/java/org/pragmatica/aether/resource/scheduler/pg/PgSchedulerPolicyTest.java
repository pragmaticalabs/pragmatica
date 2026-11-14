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
