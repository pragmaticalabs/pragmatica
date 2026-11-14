// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.scheduler.pg;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.resource.scheduler.pg.PgSchedulerFixture.*;
import static org.assertj.core.api.Assertions.assertThat;


/// Liveness of one instance: a hung or slow handler delays no other job and never stops polling (handlers are bounded by
/// their lease; polling does not wait for firings; at most `batchSize` firings in flight), and a failed poll is retried.
/// Live-PostgreSQL scheduler tests; every wait carries a deadline.
class PgSchedulerLivenessTest extends PgSchedulerTestBase {
    @Test
    void hungHandler_singleInstance_otherDueJobStillFires() {
        var otherFired = new AtomicBoolean();
        var hungFired = new AtomicInteger();
        var scheduler = start(instance("solo", 20, 500, 100, 200, 32, Clock.systemUTC(), job -> {
            if (job.name().equals("hang")) {
                hungFired.incrementAndGet();

                return hang();
            }

            otherFired.set(true);

            return done();
        }));

        schedule(scheduler, "hang", DUE);
        awaitTrue("hung job fired", DEADLINE_MS, () -> hungFired.get() == 1);
        schedule(scheduler, "other", DUE);
        awaitTrue("an unrelated due job fires while another job's handler is hung", 5_000, otherFired::get);
    }

    @Test
    void hungHandler_singleInstance_isRefiredAfterItsLease() {
        var fired = new AtomicInteger();
        var scheduler = start(instance("solo", 20, 500, 100, 200, 32, Clock.systemUTC(), _ -> {
            fired.incrementAndGet();

            return hang();
        }));

        schedule(scheduler, "hang", DUE);
        awaitTrue("hung job re-fired after its 500 ms lease by the same instance", 5_000, () -> fired.get() >= 2);
    }

    @Test
    void slowHandler_doesNotDelayAnUnrelatedDueJob() {
        var slowFired = new AtomicBoolean();
        var otherAt = new AtomicLong();
        var scheduler = start(instance("solo", 20, 30_000, 100, 200, 32, Clock.systemUTC(), job -> {
            if (job.name().equals("slow")) {
                slowFired.set(true);

                return after(3_000);
            }

            otherAt.set(System.nanoTime());

            return done();
        }));

        schedule(scheduler, "slow", DUE);
        awaitTrue("slow job fired", DEADLINE_MS, slowFired::get);

        var scheduledAt = System.nanoTime();

        schedule(scheduler, "other", DUE);
        awaitTrue("other job fired", DEADLINE_MS, () -> otherAt.get() != 0);
        assertThat((otherAt.get() - scheduledAt) / 1_000_000L).as("ms from scheduling to firing while a 3 s handler runs")
                                                             .isLessThan(1_000);
    }

    @Test
    void firingsInFlight_neverExceedBatchSize() {
        var scheduler = start(instance("solo", 20, 30_000, 100, 200, 2, Clock.systemUTC(), _ -> hang()));

        for (var i = 0; i < 5; i++) {
            schedule(scheduler, "job-" + i, DUE);
        }

        awaitTrue("two jobs claimed", DEADLINE_MS, () -> claimed() == 2);
        sleep(400);
        assertThat(claimed()).as("claimed rows after several polls with all slots busy").isEqualTo(2);
    }

    @Test
    void settledFirings_freeTheirSlots_batchOfOne() {
        var scheduler = start(instance("solo", 20, 30_000, 100, 200, 1, Clock.systemUTC(), _ -> done()));

        for (var i = 0; i < 3; i++) {
            schedule(scheduler, "job-" + i, DUE);
        }

        awaitTrue("all three jobs fire one after another through a single slot", 10_000, () -> count() == 0);
    }

    @Test
    void failedPoll_isRetriedAtTheNextPoll() {
        var fired = new AtomicBoolean();
        var scheduler = start(instance("solo", 20, 30_000, 100, 200, 4, Clock.systemUTC(), _ -> {
            fired.set(true);

            return done();
        }));

        try {
            admin.update("ALTER TABLE scheduler_job RENAME TO scheduler_job_off").await().unwrap();
            sleep(300);
        } finally {
            admin.update("ALTER TABLE scheduler_job_off RENAME TO scheduler_job").await().unwrap();
        }

        schedule(scheduler, "after-outage", DUE);
        awaitTrue("job fires after polls failed for 300 ms", 10_000, fired::get);
    }

    private long claimed() {
        return admin.queryOne("SELECT count(*)::bigint AS n FROM scheduler_job WHERE status = 'CLAIMED'",
                              row -> row.getLong("n"))
                    .await()
                    .unwrap();
    }
}
