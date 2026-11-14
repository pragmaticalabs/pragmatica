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


/// The lease + fencing-token contract between instances: concurrency, takeover after expiry, stale completion.
/// Live-PostgreSQL scheduler tests (production connector); every `awaitTrue` carries a deadline. Self-skips without Docker
/// (fails under `-Daether.requireDocker=true`).
class PgSchedulerTest extends PgSchedulerTestBase {
    @Test
    void severalInstances_dueJobs_fireAtLeastOnceAndNeverConcurrentlyWhileLeaseHeld() {
        var jobs = 150;
        var racersCount = 4;
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

            return after(25).onResultRun(() -> active.get(job.id().value())
                                                     .decrementAndGet());
        };
        var instances = new ArrayList<PgScheduler>();

        for (var i = 0; i < racersCount; i++) {
            instances.add(instance("i" + i, 1, 30_000, 100, 5_000, 10, Clock.systemUTC(), handler));
        }

        for (var i = 0; i < jobs; i++) {
            schedule(instances.getFirst(), "job-" + i, DUE);
        }

        var arrived = new AtomicInteger();

        started.addAll(instances);
        instances.forEach(s -> new Thread(() -> raceStart(arrived, racersCount, s)).start());
        awaitTrue("every due job fires", DEADLINE_MS, () -> firings.size() == jobs);
        awaitTrue("every fired job is completed", DEADLINE_MS, () -> count() == 0);
        assertThat(overlap).as("a job fired on two instances at once while its lease was held").isFalse();
        assertThat(firings.values()).as("lease 30 s never expires here: exactly one firing per job")
                  .allSatisfy(n -> assertThat(n.get()).isEqualTo(1));
    }

    private static void raceStart(AtomicInteger arrived, int expected, PgScheduler scheduler) {
        arrived.incrementAndGet();
        awaitTrue("all racers at the barrier", 10_000, () -> arrived.get() == expected);
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
}
