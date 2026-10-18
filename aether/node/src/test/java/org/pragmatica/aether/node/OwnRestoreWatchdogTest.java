// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// #1468 — a node whose own consensus history cannot be restored stays `JOINING` only up to a bound,
/// then exits with a distinct code. The clock is injected, so the bound is crossed without sleeping.
class OwnRestoreWatchdogTest {
    private static final Cause UNREADABLE = Causes.cause("snapshot is corrupt and cannot be decoded");
    private static final TimeSpan BOUND = TimeSpan.timeSpan(120).seconds();
    private static final long BOUND_MILLIS = BOUND.millis();

    private final AtomicReference<Option<Cause>> failure = new AtomicReference<>(Option.none());
    private final AtomicLong now = new AtomicLong(1_000);
    private final List<Cause> exits = new CopyOnWriteArrayList<>();
    private final OwnRestoreWatchdog watchdog = OwnRestoreWatchdog.ownRestoreWatchdog(failure::get, now::get, BOUND, exits::add);

    @Test
    void tick_neverExits_whenThereIsNoFailure() {
        watchdog.tick();
        now.addAndGet(10 * BOUND_MILLIS);
        watchdog.tick();

        assertThat(exits).isEmpty();
    }

    @Test
    void tick_doesNotExit_beforeTheBound() {
        failure.set(Option.some(UNREADABLE));
        watchdog.tick();
        now.addAndGet(BOUND_MILLIS - 1);
        watchdog.tick();

        assertThat(exits).as("one millisecond short of the bound").isEmpty();
    }

    @Test
    void tick_exitsWithTheCause_atTheBound() {
        failure.set(Option.some(UNREADABLE));
        watchdog.tick();
        now.addAndGet(BOUND_MILLIS);
        watchdog.tick();

        assertThat(exits).containsExactly(UNREADABLE);
    }

    @Test
    void tick_exitsOnce_howeverLongTheFailurePersists() {
        failure.set(Option.some(UNREADABLE));
        watchdog.tick();
        now.addAndGet(BOUND_MILLIS);
        watchdog.tick();
        now.addAndGet(BOUND_MILLIS);
        watchdog.tick();

        assertThat(exits).hasSize(1);
    }

    /// The clock measures CONTINUOUS presence: a failure that clears (a new genesis installed) and returns
    /// starts a new bound rather than inheriting the time already spent.
    @Test
    void tick_restartsTheClock_whenTheFailureClearsAndReturns() {
        failure.set(Option.some(UNREADABLE));
        watchdog.tick();
        now.addAndGet(BOUND_MILLIS - 1);
        failure.set(Option.none());
        watchdog.tick();
        now.addAndGet(10);
        failure.set(Option.some(UNREADABLE));
        watchdog.tick();
        now.addAndGet(BOUND_MILLIS - 1);
        watchdog.tick();

        assertThat(exits).as("the second bound started when the failure returned").isEmpty();
    }

    /// Scheduled path, real clock: the watchdog armed with a short bound and interval exits by itself.
    @Test
    void arm_exitsOnItsOwn_whenTheFailurePersistsPastTheBound() throws InterruptedException {
        var fired = new CountDownLatch(1);
        var armed = OwnRestoreWatchdog.ownRestoreWatchdog(() -> Option.some(UNREADABLE),
                                                          System::currentTimeMillis,
                                                          TimeSpan.timeSpan(60).millis(),
                                                          _ -> fired.countDown());

        armed.arm(TimeSpan.timeSpan(10).millis());

        assertThat(fired.await(5, TimeUnit.SECONDS)).as("the scheduled sampler reached the exit").isTrue();
    }
}
