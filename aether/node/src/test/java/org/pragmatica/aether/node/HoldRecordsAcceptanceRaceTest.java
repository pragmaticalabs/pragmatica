// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.metrics.HeldEnd;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.node.HoldRecordsWorld.E1;
import static org.pragmatica.aether.node.HoldRecordsWorld.E2;
import static org.pragmatica.aether.node.HoldRecordsWorld.N;
import static org.pragmatica.aether.node.HoldRecordsWorld.T0;
import static org.pragmatica.aether.node.HoldRecordsWorld.UNK;
import static org.pragmatica.aether.node.HoldRecordsWorld.ended;
import static org.pragmatica.aether.node.HoldRecordsWorld.held;
import static org.pragmatica.aether.node.HoldRecordsWorld.ticks;

/// The override racing the other exits: one closer per episode whatever wins (v3401-1b X9, X10, C1).
class HoldRecordsAcceptanceRaceTest {
    private static final int ROUNDS = 300;

    @Test
    void acceptLoss_inFlightWhileDeathArrives_refusedClosedOnceNoDecision() {
        var w = new HoldRecordsWorld();
        var a = w.leader();
        w.pong(a, held(E1, T0));
        w.holdOpen = true;
        var call = a.acceptLoss(N, E1, UNK);
        a.onDead(N);
        w.holdOpen = false;
        w.settle();
        var result = call.await();
        ticks(a, 2);
        w.pong(a, held(E2, T0 + 5));

        assertThat(result.isFailure()).isTrue();
        assertThat(w.closersLanded(E1)).isEqualTo(1);
        assertThat(a.decisionFor(N)).isEqualTo(Option.none());
    }

    @Test
    void acceptLoss_landedThenNewerEpisode_oldClosedOnceNoDecisionForNewer() {
        var w = new HoldRecordsWorld();
        var a = w.leader();
        w.pong(a, held(E1, T0));
        assertThat(a.acceptLoss(N, E1, UNK).await().isSuccess()).isTrue();
        w.pong(a, held(E2, T0 + 5));
        ticks(a, 2);

        assertThat(w.closersLanded(E1)).isEqualTo(1);
        assertThat(a.decisionFor(N)).isEqualTo(Option.none());
        assertThat(a.heldLatched(N)).isTrue();
    }

    @Test
    void edges_fiveConcurrentEdgesOverManyRounds_exactlyOneCloser() throws InterruptedException {
        var bad = new ArrayList<String>();
        for (int round = 0; round < ROUNDS; round++) {
            round(round, bad);
        }
        assertThat(bad).as("rounds with closers(E1) != 1 or an error").isEmpty();
    }

    private static void round(int round, List<String> bad) throws InterruptedException {
        var w = new HoldRecordsWorld();
        var a = w.leader();
        w.pong(a, held(E1, T0));
        var start = new CountDownLatch(1);
        var err = new AtomicReference<Throwable>();
        List<Runnable> ops = List.of(() -> w.pong(a, ended(E1, T0 + 1, HeldEnd.DELIVERED)),
                                     () -> a.onDead(N),
                                     () -> a.acceptLoss(N, E1, UNK).await(),
                                     a::tick,
                                     () -> w.pong(a, held(E2, T0 + 2)));
        var threads = ops.stream().map(op -> Thread.ofPlatform().start(() -> gated(start, op, err))).toList();
        start.countDown();
        for (var thread : threads) {
            thread.join(5_000);
        }
        ticks(a, 3);
        if (w.closersLanded(E1) != 1 || err.get() != null) {
            bad.add("round " + round + " closersE1=" + w.closersLanded(E1) + " err=" + err.get());
        }
    }

    private static void gated(CountDownLatch start, Runnable op, AtomicReference<Throwable> err) {
        try {
            start.await();
            op.run();
        } catch (Throwable e) {
            err.set(e);
        }
    }
}
