// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember.ports;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;


/// The waits (rendezvous and allocator back-off) are iterative: stack depth does not grow with the wait. The recursive
/// form overflowed a default stack at about 17.7 s of rendezvous wait (v3043 F3), while `ClaimChild` waits up to 15 s.
class PortWaitDepthTest {
    private static final int BASE = 31_000;
    private static final long SMALL_STACK_BYTES = 128 * 1024L;

    @TempDir
    Path meetDir;

    @TempDir
    Path lockDir;

    @Test
    @Timeout(90)
    void meet_waitingTwiceTheClaimChildBound_timesOutCleanly_withoutStackOverflow() throws Exception {
        var outcome = onThread(Thread.currentThread().getThreadGroup(), 0L, rendezvous(30_000));

        assertThat(outcome.get()).isInstanceOf(PortError.RendezvousTimedOut.class);
    }

    @Test
    @Timeout(60)
    void meet_onAShrunkStack_timesOutCleanly_soDepthIsConstantInWaitLength() throws Exception {
        var outcome = onThread(null, SMALL_STACK_BYTES, rendezvous(4_000));

        assertThat(outcome.get()).isInstanceOf(PortError.RendezvousTimedOut.class);
    }

    @Test
    @Timeout(60)
    void claim_backOffOnAShrunkStack_failsAsNoFreeBlock_soDepthIsConstantInWaitLength() throws Exception {
        var allocator = EmberPortAllocator.emberPortAllocator()
                                          .lockDir(lockDir)
                                          .range(BASE + 100, BASE + 100 + PortBlock.STRIDE - 1)
                                          .budget(4_000, 20);

        try (var held = allocator.claim(1).unwrap()) {
            var outcome = onThread(null, SMALL_STACK_BYTES, () -> allocator.claim(1).fold(cause -> cause, _ -> "claimed"));

            assertThat(held.block().base()).isEqualTo(BASE + 100);
            assertThat(outcome.get()).isInstanceOf(PortError.NoFreeBlock.class);
        }
    }

    @Test
    @Timeout(30)
    void poll_sleepsTheIntervalBetweenPolls_insteadOfSpinning() {
        var polls = new AtomicInteger();
        var deadline = System.nanoTime() + 400_000_000L;

        var outcome = PortWait.poll(deadline,
                                    50,
                                    () -> {
                                        polls.incrementAndGet();
                                        return Result.success(Option.<String> none());
                                    },
                                    () -> Causes.cause("timed out"));

        assertThat(outcome.isFailure()).isTrue();
        assertThat(polls.get()).as("about 400 ms / 50 ms polls, not a spin").isBetween(2, 20);
    }

    @Test
    @Timeout(30)
    void poll_stepFailure_returnsTheFailureAtOnce_insteadOfRetryingUntilTheDeadline() {
        var polls = new AtomicInteger();
        var failure = Causes.cause("step failed");
        var started = System.nanoTime();

        var outcome = PortWait.poll(started + 10_000_000_000L,
                                    50,
                                    () -> polls.incrementAndGet() == 1
                                          ? failure.<Option<String>> result()
                                          : Result.success(Option.some("late")),
                                    () -> Causes.cause("timed out"));

        assertThat(outcome.<Object> fold(cause -> cause, _ -> "value")).isSameAs(failure);
        assertThat(polls.get()).as("no retry after a failure").isEqualTo(1);
        assertThat(System.nanoTime() - started).as("returned well before the 10 s deadline").isLessThan(1_000_000_000L);
    }

    @Test
    @Timeout(30)
    void poll_lastSleepIsClampedToTheRemainingTime_soTheWaitDoesNotOvershootTheDeadline() {
        var started = System.nanoTime();

        var outcome = PortWait.poll(started + 100_000_000L,
                                    2_000,
                                    () -> Result.success(Option.<String> none()),
                                    () -> Causes.cause("timed out"));

        assertThat(outcome.isFailure()).isTrue();
        assertThat(System.nanoTime() - started).as("2 s interval must not push a 100 ms wait past ~1 s").isLessThan(1_000_000_000L);
    }

    private Supplier<Object> rendezvous(long boundMs) {
        var rendezvous = EmberPortRendezvous.emberPortRendezvous(meetDir, 2, boundMs, "solo");

        return () -> rendezvous.meet(PortBlock.portBlock(BASE, 1).unwrap()).<Object> fold(cause -> cause, unit -> unit);
    }

    private static AtomicReference<Object> onThread(ThreadGroup group, long stackBytes, Supplier<Object> work) throws Exception {
        var outcome = new AtomicReference<Object>();
        var thread = new Thread(group, () -> run(outcome, work), "depth-probe", stackBytes);

        thread.start();
        thread.join();
        return outcome;
    }

    private static void run(AtomicReference<Object> outcome, Supplier<Object> work) {
        try {
            outcome.set(work.get());
        } catch (Throwable t) {
            outcome.set(t);
        }
    }
}
