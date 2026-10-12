// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Set;

import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.SlFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;


/// The drain loop's whole life (C18, step 4): start, replace, abandon when the drain is withdrawn, complete when the node holds
/// nothing.
class GapDrainTest {
    private FsmHarness draining() {
        var h = threeCores();

        seedTarget(h, ORDERS, 1, 1);
        seedGoal(h, CORE_1, ORDERS, SliceGoal.ACTIVE, G1);
        seedRow(h, CORE_1, ORDERS, SliceObservedState.ACTIVE, G1, 11L);
        h.seams = h.seams.withDraining(Set.of(CORE_1));

        return h;
    }

    /// Red when: a node that stops draining keeps its loop (the original is still unloaded once the replacement is active), or its
    /// step timer survives.
    @Test
    void withdrawnDrain_abandonsTheLoop() {
        var h = draining();

        h.activate();
        assertThat(h.model().drainLoops.contains(CORE_1)).as("the loop started with the reconcile").isTrue();
        assertThat(goalOf(h, CORE_2, ORDERS).isPresent() || goalOf(h, CORE_3, ORDERS).isPresent()).as("a replacement was placed")
                  .isTrue();
        h.clearLog();
        h.deliver(new ClusterInput.SeamsRefreshed(h.seams.withDraining(Set.of())));
        assertThat(h.model().drainLoops.contains(CORE_1)).isFalse();
        assertThat(h.effects(ClusterEffect.Cancel.class)).extracting(ClusterEffect.Cancel::timer)
                  .contains(TimerId.timerId(TimerId.Kind.DRAIN_STEP,
                                            CORE_1.id()));
        h.clearLog();
        h.advanceBySeconds(10);
        assertThat(h.effects(ClusterEffect.Schedule.class)).as("no step of the abandoned loop is scheduled again")
                  .noneMatch(schedule -> schedule.timer()
                                                 .kind() == TimerId.Kind.DRAIN_STEP);
    }

    /// Red when: the loop does not unload the original once a replacement is ACTIVE, or does not end ("Drain complete") when the node
    /// holds nothing any more, or ends while it still holds something.
    @Test
    void drain_replacesThenCompletes() {
        var h = draining();

        h.activate();
        var replacement = goalOf(h, CORE_2, ORDERS).isPresent()
                          ? CORE_2
                          : CORE_3;

        node(h, replacement, 12L).follows(ORDERS);
        h.drain();
        node(h, replacement, 12L).follows(ORDERS);
        h.drain();
        h.advance(2_000L);
        assertThat(goalOf(h, CORE_1, ORDERS)).as("the replacement is ACTIVE: the original is unloaded")
                  .isEqualTo(Option.some(SliceGoal.UNLOADED));
        assertThat(h.model().drainLoops.contains(CORE_1)).as("still holding the unloading instance").isTrue();
        node(h, CORE_1, 11L).follows(ORDERS);
        h.drain();
        h.seedRemove(new SliceObservedKey(CORE_1, ORDERS));
        h.drain();
        h.advance(2_000L);
        h.advance(2_000L);
        assertThat(h.effects(ClusterEffect.Log.class)).anyMatch(log -> log.message()
                                                                          .startsWith("Drain complete for node core-1"));
        assertThat(h.model().drainLoops.contains(CORE_1)).isFalse();
    }

    private static NodeSim node(FsmHarness h, org.pragmatica.consensus.NodeId node, long token) {
        return new NodeSim(h, node, process(token));
    }
}
