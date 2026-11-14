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


/// Clock skew of an instance changes nothing: only the database clock decides.
/// Live-PostgreSQL scheduler tests (production connector); every `awaitTrue` carries a deadline. Self-skips without Docker
/// (fails under `-Daether.requireDocker=true`).
class PgSchedulerClockTest extends PgSchedulerTestBase {
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
}
