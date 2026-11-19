// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.metrics.HeldEnd;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.node.HoldRecordsFixture.eventually;
import static org.pragmatica.aether.node.HoldRecordsWorld.E1;
import static org.pragmatica.aether.node.HoldRecordsWorld.N;
import static org.pragmatica.aether.node.HoldRecordsWorld.T0;
import static org.pragmatica.aether.node.HoldRecordsWorld.UNK;
import static org.pragmatica.aether.node.HoldRecordsWorld.ended;
import static org.pragmatica.aether.node.HoldRecordsWorld.held;
import static org.pragmatica.aether.node.HoldRecordsWorld.heldDigest;
import static org.pragmatica.aether.node.HoldRecordsWorld.ticks;

/// Invariant E: every OPEN episode has all its exits reachable on every leader (a report, the override, DEAD, and once an
/// acceptance landed, LEAVE); exactly one closer per episode whose at-risk warning was raised (H7-H10, v3401-1b X1-X3, X14, X15).
class HoldRecordsEpisodeExitTest {
    @Test
    void onLeft_acceptanceLandedWithoutEndReport_closesOnceAndNoLaterLeaderLatches() {
        var w = new HoldRecordsWorld();
        var a = w.leader();
        w.pong(a, held(E1, T0));
        assertThat(a.acceptLoss(N, E1, UNK).await().isSuccess()).isTrue();
        w.fan.remove(N);
        a.onLeft(N);
        ticks(a, 3);
        var b = w.leader();
        b.seedFromLog().await();

        assertThat(w.closersLanded(E1)).as("closers of E1 after LEAVE with a landed acceptance (H7)").isEqualTo(1);
        assertThat(b.heldLatched(N)).as("new leader latched on a node that left with its loss accepted").isFalse();
    }

    @Test
    void onLeft_openEpisodeWithoutAcceptance_staysLatched() {
        var w = new HoldRecordsWorld();
        var a = w.leader();
        w.pong(a, held(E1, T0));
        a.onLeft(N);
        ticks(a, 2);

        assertThat(a.heldLatched(N)).as("LEAVE alone is no exit (R2)").isTrue();
        assertThat(w.closersLanded(E1)).isZero();
    }

    @Test
    void acceptLoss_seededLatchOfALeftNode_acceptsAndCloses() {
        var w = new HoldRecordsWorld();
        var a = w.leader();
        w.pong(a, held(E1, T0));
        a.onLeft(N);
        w.fan.remove(N);
        var b = w.leader();
        b.seedFromLog().await();
        b.onLeft(N);
        assertThat(b.heldLatched(N)).isTrue();

        var accepted = b.acceptLoss(N, E1, UNK).await();
        ticks(b, 2);

        assertThat(accepted.isSuccess()).as("override of a seeded latch, the only exit of a node that left (H8)").isTrue();
        assertThat(w.closersLanded(E1)).as("the LEAVE edge already passed: closed once").isEqualTo(1);
        assertThat(b.heldLatched(N)).isFalse();
    }

    @Test
    void reset_leadershipRegained_raisesNoSecondCloser() {
        var w = new HoldRecordsWorld();
        var a = w.leader();
        w.pong(a, held(E1, T0));
        a.reset();
        var b = w.leader();
        b.seedFromLog().await();
        w.pong(b, ended(E1, T0 + 10, HeldEnd.DELIVERED));
        eventually(() -> w.closersLanded(E1) == 1);
        b.onLeft(N);
        b.reset();
        w.fan.remove(N);
        a.seedFromLog().await();
        a.onDead(N);
        ticks(a, 2);

        assertThat(a.heldLatched(N)).as("regained leader starts from the log seed (H9)").isFalse();
        assertThat(w.closersLanded(E1)).as("closers of E1 after A regained").isEqualTo(1);
    }

    @Test
    void acceptLoss_deadBeforeFirstObservedReport_refusedNotHeldWithoutWarnings() {
        var w = new HoldRecordsWorld();
        var a = w.leader();
        w.fan.put(N, held(E1, T0));
        a.onDead(N);
        a.tick();
        var accept = a.acceptLoss(N, E1, UNK).await();

        assertThat(a.heldLatched(N)).as("a node declared dead is not latched by a later read of its report").isFalse();
        Object outcome = accept.fold(cause -> cause, value -> value);

        assertThat(outcome)
            .as("no record is built from the FAN on demand (H10)").isInstanceOf(HoldRecords.HoldRefusal.NotHeld.class);
        assertThat(w.atRisk(E1)).isZero();
        assertThat(w.closersLanded(E1)).isZero();
        assertThat(a.decisionFor(N)).isEqualTo(Option.none());
    }

    @Test
    void onPong_holdDeliveredBeforeReportable_endsSilently() {
        var w = new HoldRecordsWorld();
        var a = w.leader();
        w.pong(a, heldDigest(E1, T0, "d"));
        w.now = T0 + 3_000;
        w.pong(a, ended(E1, T0 + 3_000, HeldEnd.DELIVERED));
        ticks(a, 2);

        assertThat(w.atRisk(E1)).as("no at-risk for a hold that ended before it was reportable (H10)").isZero();
        assertThat(w.closersLanded(E1)).as("a closer answers a raised warning only").isZero();
        assertThat(a.heldLatched(N)).isFalse();
    }
}
