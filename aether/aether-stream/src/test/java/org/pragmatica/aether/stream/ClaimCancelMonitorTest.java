// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.stream.ConsumerRuntimeState.ConsumerState;
import org.pragmatica.aether.stream.StreamConsumerRuntime.IdlePolicy;
import org.pragmatica.lang.Promise;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #2914: [ConsumerState#claimAdvance] and [ConsumerState#cancel] share ONE monitor, the state. While a thread holds that
/// monitor (standing in for a claim between its cancel check and `beginAdvance`), the other operation must block, not
/// run: otherwise the detach flush can read the empty slot and commit while the append is still to come. The race test
/// cannot reach that window, so this pins the synchronisation itself, by thread state with a deadline.
class ClaimCancelMonitorTest {
    private static final long DEADLINE_SECONDS = 5;

    @Test
    void cancel_blocksWhileTheMonitorIsHeld() throws Exception {
        var state = consumerState();

        assertBlocksWhileHeld(state, state::cancel);
    }

    @Test
    void claimAdvance_blocksWhileTheMonitorIsHeld() throws Exception {
        var state = consumerState();

        assertBlocksWhileHeld(state, () -> state.claimAdvance(0L));
    }

    private static ConsumerState consumerState() {
        return ConsumerState.consumerState(ConsumerConfig.consumerConfig("g"),
                                           (offset, payload, timestamp) -> Promise.unitPromise(),
                                           0L,
                                           IdlePolicy.REAP_WHEN_IDLE);
    }

    private static void assertBlocksWhileHeld(ConsumerState state, Runnable operation) throws Exception {
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holder = Thread.ofPlatform().start(() -> holdMonitor(state, held, release));

        assertThat(held.await(DEADLINE_SECONDS, TimeUnit.SECONDS)).isTrue();

        var other = Thread.ofPlatform().start(operation);
        var seen = awaitBlocked(other);

        release.countDown();
        holder.join(TimeUnit.SECONDS.toMillis(DEADLINE_SECONDS));
        other.join(TimeUnit.SECONDS.toMillis(DEADLINE_SECONDS));
        assertThat(seen).as("operation must block on the state monitor while it is held")
                        .isEqualTo(Thread.State.BLOCKED);
    }

    private static Thread.State awaitBlocked(Thread thread) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEADLINE_SECONDS);

        while (thread.getState() != Thread.State.BLOCKED && thread.isAlive() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        return thread.getState();
    }

    private static void holdMonitor(ConsumerState state, CountDownLatch held, CountDownLatch release) {
        synchronized (state) {
            held.countDown();
            awaitQuietly(release);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
