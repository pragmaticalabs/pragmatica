// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.wiring;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// N-10, raced: start() against retireIfConsumerless(). Invariant: when retire says "consumerless" (the caller will run the
/// shutdown itself), no consumer thread is alive afterwards. Counted violations must be 0 at the head and > 0 with the monitor removed.
class FsmMailboxStartRetireRaceTest {
    @Test
    void retireAndStart_neverLeaveALiveConsumerBesideACallerShutdown() throws Exception {
        var violations = new AtomicInteger();
        var retiredTrue = new AtomicInteger();
        var iterations = 20_000;

        for (int i = 0; i < iterations; i++) {
            var mailbox = FsmMailbox.<Object> fsmMailbox("probe-" + i, _ -> {}, () -> {});
            var barrier = new CyclicBarrier(2);
            var retired = new AtomicBoolean();
            var starter = Thread.ofPlatform().start(() -> {
                await(barrier);
                spin(java.util.concurrent.ThreadLocalRandom.current().nextInt(400));
                mailbox.start();
            });

            await(barrier);
            spin(java.util.concurrent.ThreadLocalRandom.current().nextInt(400));
            retired.set(mailbox.retireIfConsumerless());
            starter.join();
            if (retired.get()) {
                retiredTrue.incrementAndGet();
                if (mailbox.running()) {
                    violations.incrementAndGet();
                }
            }
            mailbox.close();
        }
        assertThat(retiredTrue.get()).as("both orders were exercised").isGreaterThan(0).isLessThan(iterations);
        assertThat(violations.get()).isZero();
    }

    private static void spin(int n) {
        for (int k = 0; k < n; k++) {
            Thread.onSpinWait();
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
