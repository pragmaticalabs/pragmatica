// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.scheduler.pg;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.resource.scheduler.pg.PgSchedulerFixture.*;
import static org.assertj.core.api.Assertions.assertThat;


/// The fencing token against a superseded holder: a late completion or late failure written after close released the
/// claim, or after another instance took the job over, is refused and changes nothing.
/// Live-PostgreSQL scheduler tests; every wait carries a deadline.
class PgSchedulerFencingTest extends PgSchedulerTestBase {
    private final AtomicReference<Promise<Unit>> staleGate = new AtomicReference<>();

    @Test
    void staleHolder_completionAfterTakeover_isRefusedByToken() {
        var id = holderClosedThenTakenOver();

        open(staleGate.get());
        sleep(500);
        assertThat(count()).as("the stale holder's completion must not delete the takeover's row").isEqualTo(1);
        assertThat(tokenOf(id)).isEqualTo(2);
        assertThat(statusOf(id)).isEqualTo("CLAIMED");
    }

    @Test
    void staleHolder_failureAfterTakeover_isRefusedByToken() {
        var id = holderClosedThenTakenOver();

        staleGate.get().resolve(Causes.cause("stale holder failed late").result());
        sleep(500);
        assertThat(statusOf(id)).as("the stale holder's failure must not reschedule the takeover's claim")
                                .isEqualTo("CLAIMED");
        assertThat(tokenOf(id)).isEqualTo(2);
    }

    @Test
    void lateCompletionAfterRelease_isRefusedWhileTheRowIsScheduled() {
        var claimed = new AtomicBoolean();
        var holder = start(instance("holder", 20, 60_000, 100, 200, 1, Clock.systemUTC(), _ -> {
            claimed.set(true);
            staleGate.set(hang());

            return staleGate.get();
        }));
        var id = schedule(holder, "released", DUE);

        awaitTrue("holder claimed the job", DEADLINE_MS, claimed::get);
        holder.close().await();
        assertThat(statusOf(id)).as("close released the claim").isEqualTo("SCHEDULED");
        open(staleGate.get());
        sleep(500);
        assertThat(count()).as("a late completion must not delete a job that is SCHEDULED again").isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("SCHEDULED");

        var refired = new AtomicBoolean();

        start(instance("next", 20, 60_000, 100, 200, 1, Clock.systemUTC(), _ -> {
            refired.set(true);

            return done();
        }));
        awaitTrue("the released job still fires on another instance", DEADLINE_MS, refired::get);
    }

    /// The holder claims the job (60 s lease), is closed (its grace ends, its claim is released), and another instance
    /// claims the job with the next token and keeps it. `staleGate` is the still-pending handler promise of the holder.
    private JobId holderClosedThenTakenOver() {
        var claimed = new AtomicBoolean();
        var takeoverFired = new AtomicBoolean();
        var holder = start(instance("holder", 20, 60_000, 100, 200, 1, Clock.systemUTC(), _ -> {
            claimed.set(true);
            staleGate.set(hang());

            return staleGate.get();
        }));
        var id = schedule(holder, "contested", DUE);

        awaitTrue("holder claimed the job", DEADLINE_MS, claimed::get);
        holder.close().await();
        start(instance("takeover", 20, 60_000, 100, 200, 1, Clock.systemUTC(), _ -> {
            takeoverFired.set(true);

            return hang();
        }));
        awaitTrue("takeover fired the job", DEADLINE_MS, takeoverFired::get);
        assertThat(tokenOf(id)).isEqualTo(2);

        return id;
    }
}
