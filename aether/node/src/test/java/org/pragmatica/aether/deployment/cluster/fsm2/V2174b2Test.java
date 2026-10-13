// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureCause;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

/// v-2174b second delta pass at 54324538d0. CTO ruling (session 35): the `join-unmatched` WARN is emitted at most once per unmatched
/// node per episode, "until it matches, leaves, or the leader changes". The leader-change arm, while this FSM stays Active.
class V2174b2Test {
    private static MbWorld unmatchedWorld() {
        var w = MbWorld.world(CORE_1, CORE_2, CORE_3);

        w.admit(CORE_1, 1L, 0x1111L);
        w.admit(CORE_2, 5L, 0x2222L);
        w.admit(CORE_3, 1L, 0x3333L);
        w.directive(CORE_2, ActivationDirectiveValue.core())
         .record(CORE_2, DepartureState.DEPARTED, 5L, 0x2222L, DepartureCause.NODE_REMOVED)
         .activate();

        return w;
    }

    /// Red when: the leader changes (deposed, then this node leads again) and the still-unmatched node's episode carries over, so no
    /// WARN reports it under the new leadership until the next tick.
    @Test
    void joinUnmatched_leaderChange_startsANewEpisode() {
        var w = unmatchedWorld();

        w.h.deliver(new ClusterInput.NodeJoined(CORE_2, w.admissionOf(CORE_2, 5L)));
        assertThat(w.h.warns("join-unmatched")).as("precondition: the episode starts with one WARN").hasSize(1);
        w.h.seed(new SliceTargetKey(ORDERS.base()), SliceTargetValue.sliceTargetValue(ORDERS.version(), 1, 1));
        w.h.drain();
        assertThat(w.h.warns("join-unmatched")).as("precondition: a KV drain does not re-emit").hasSize(1);
        w.h.commitLeader(CORE_3, 50L);
        w.h.drain();
        w.h.commitLeader(FsmHarness.SELF, 51L);
        w.h.drain();
        w.h.seed(new SliceTargetKey(ORDERS.base()), SliceTargetValue.sliceTargetValue(ORDERS.version(), 2, 1));
        w.h.drain();
        assertThat(w.h.model().pendingJoins.containsKey(CORE_2)).as("precondition: still unmatched and pending").isTrue();
        assertThat(w.h.warns("join-unmatched")).as("the leader changed: a new episode, one more WARN").hasSize(2);
    }
}
