// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.metrics.HeldEnd;
import org.pragmatica.cluster.metrics.HeldReport;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.node.HoldRecordsWorld.E1;
import static org.pragmatica.aether.node.HoldRecordsWorld.E2;
import static org.pragmatica.aether.node.HoldRecordsWorld.E3;
import static org.pragmatica.aether.node.HoldRecordsWorld.N;
import static org.pragmatica.aether.node.HoldRecordsWorld.T0;
import static org.pragmatica.aether.node.HoldRecordsWorld.ended;
import static org.pragmatica.aether.node.HoldRecordsWorld.held;
import static org.pragmatica.aether.node.HoldRecordsWorld.ticks;

/// Reports of several episodes arriving out of order, late, or faster than the tick: the older episode closes once, the
/// newest stays open (v3401-1b X6, X7, X8, X13).
class HoldRecordsEpisodeOrderTest {
    @Test
    void onPong_interleavedOldNewOldNew_oldEpisodeClosedOnceNewestOpen() {
        var w = new HoldRecordsWorld();
        var a = w.leader();
        w.pong(a, held(E1, T0));
        w.pong(a, held(E2, T0 + 5));
        w.pong(a, held(E1, T0 + 6));
        w.pong(a, held(E2, T0 + 7));
        w.pong(a, ended(E1, T0 + 8, HeldEnd.DELIVERED));
        ticks(a, 2);

        assertThat(w.closersLanded(E1)).isEqualTo(1);
        assertThat(w.closersLanded(E2)).isZero();
        assertThat(w.atRisk(E1)).isEqualTo(1);
        assertThat(a.heldLatched(N)).isTrue();
        assertThat(a.lastReport(N).map(HeldReport::episode)).isEqualTo(Option.some(E2));
    }

    @Test
    void tick_threeEpisodesWithinOneTick_unseenMiddleEpisodeRaisesNothing() {
        var w = new HoldRecordsWorld();
        var a = w.leader();
        w.pong(a, held(E1, T0));
        w.fan.put(N, held(E2, T0 + 1));
        w.fan.put(N, held(E3, T0 + 2));
        a.tick();
        w.fan.put(N, held(E2, T0 + 3));
        a.tick();

        assertThat(w.closersLanded(E1)).isEqualTo(1);
        assertThat(w.closersLanded(E2)).isZero();
        assertThat(w.atRisk(E2)).isZero();
        assertThat(a.lastReport(N).map(HeldReport::episode)).isEqualTo(Option.some(E3));
    }

    @Test
    void onDead_restartWithNewEpisodeThenLateOldDelivery_oldClosedOnce() {
        var w = new HoldRecordsWorld();
        var a = w.leader();
        w.pong(a, held(E1, T0));
        a.onDead(N);
        w.pong(a, held(E2, T0 + 5));
        w.pong(a, ended(E1, T0 + 6, HeldEnd.DELIVERED));
        ticks(a, 2);

        assertThat(w.closersLanded(E1)).isEqualTo(1);
        assertThat(w.closersLanded(E2)).isZero();
        assertThat(a.heldLatched(N)).isTrue();
    }

    @Test
    void onDead_closerLandsLateAfterNewerEpisode_oldClosedOnce() {
        var w = new HoldRecordsWorld();
        var a = w.leader();
        w.pong(a, held(E1, T0));
        w.holdOpen = true;
        a.onDead(N);
        w.pong(a, held(E2, T0 + 5));
        a.tick();
        w.holdOpen = false;
        w.settle();
        ticks(a, 3);

        assertThat(w.closersLanded(E1)).isEqualTo(1);
        assertThat(w.closersLanded(E2)).isZero();
        assertThat(a.heldLatched(N)).isTrue();
    }
}
