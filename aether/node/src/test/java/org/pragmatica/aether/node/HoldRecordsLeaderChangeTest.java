// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.metrics.HeldEnd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.node.HoldRecordsWorld.E1;
import static org.pragmatica.aether.node.HoldRecordsWorld.E2;
import static org.pragmatica.aether.node.HoldRecordsWorld.N;
import static org.pragmatica.aether.node.HoldRecordsWorld.T0;
import static org.pragmatica.aether.node.HoldRecordsWorld.ended;
import static org.pragmatica.aether.node.HoldRecordsWorld.held;
import static org.pragmatica.aether.node.HoldRecordsWorld.ticks;

/// Leader change over ONE landed-event log: a later leader seeds from the log and closes each episode at most once
/// (v3401-1b X4, X5, X11, X12).
class HoldRecordsLeaderChangeTest {
    @Test
    void seedFromLog_supersededCloserNotLanded_newLeaderClosesTheOldEpisodeOnce() {
        var w = new HoldRecordsWorld();
        var a = w.leader();
        w.pong(a, held(E1, T0));
        w.lands = false;
        w.pong(a, held(E2, T0 + 5));
        w.lands = true;
        var b = w.leader();
        b.seedFromLog().await();
        ticks(b, 2);
        assertThat(b.heldLatched(N)).as("B: E1 seeded, E2 unknown to the log").isTrue();

        w.pong(b, held(E2, T0 + 6));

        assertThat(w.closersLanded(E1)).isEqualTo(1);
        assertThat(w.closersLanded(E2)).isZero();
        assertThat(b.heldLatched(N)).isTrue();
    }

    @Test
    void seedFromLog_newerEpisodeFirstReportDelivered_bothClosedOnceNoLatch() {
        var w = new HoldRecordsWorld();
        var a = w.leader();
        w.pong(a, held(E1, T0));
        w.pong(a, ended(E2, T0 + 5, HeldEnd.DELIVERED));
        var b = w.leader();
        b.seedFromLog().await();

        assertThat(w.closersLanded(E1)).isEqualTo(1);
        assertThat(w.closersLanded(E2)).as("E2's first report already ended: no warning raised, no closer").isZero();
        assertThat(w.atRisk(E2)).isZero();
        assertThat(b.heldLatched(N)).isFalse();
        assertThat(a.heldLatched(N)).isFalse();
    }

    @Test
    void seedFromLog_closerNotLandedOnOldLeader_newLeaderClosesOnceFromTheFan() {
        var w = new HoldRecordsWorld();
        var a = w.leader();
        w.pong(a, held(E1, T0));
        w.lands = false;
        w.pong(a, ended(E1, T0 + 1, HeldEnd.DELIVERED));
        w.lands = true;
        var b = w.leader();
        b.seedFromLog().await();
        b.onPong(N);
        ticks(b, 2);

        assertThat(w.closersLanded(E1)).isEqualTo(1);
        assertThat(b.heldLatched(N)).isFalse();
    }

    @Test
    void seedFromLog_seededDeathThenLateReport_closedOnceNotRelatched() {
        var w = new HoldRecordsWorld();
        var a = w.leader();
        w.pong(a, held(E1, T0));
        w.fan.remove(N);
        var b = w.leader();
        b.seedFromLog().await();
        b.onDead(N);
        w.pong(b, held(E1, T0 + 9));
        ticks(b, 2);

        assertThat(w.closersLanded(E1)).isEqualTo(1);
        assertThat(b.heldLatched(N)).isFalse();
    }
}
