// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.CommunityState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.SlFixtures.*;

/// Placement policies (B-076, B-033, R-3.3.4): the audience of a target is the policy applied to the allocation pool of cores and
/// of the placeable workers of ACTIVE communities.
class GapPlacementTest {
    private static final NodeId W1 = node("worker-1");
    private static final NodeId W2 = node("worker-2");
    private static final Set<NodeId> ALL = Set.of(CORE_1, CORE_2, CORE_3, W1, W2);

    private static MbWorld clusterWithWorkers(String placement, int instances) {
        var w = MbWorld.worldWithFloor(2, CORE_1, CORE_2, CORE_3)
                        .target(ORDERS, instances, placement)
                        .directive(W1, worker("east-w-0"))
                        .directive(W2, worker("east-w-0"))
                        .community("east-w-0", 3, CommunityState.ACTIVE)
                        .seams(seams -> seams.withReady(ALL).withLocalAlive(Option.some(Set.of(W1, W2))))
                        .activate();

        w.announce("east-w-0", W1, W1, W2);
        return w;
    }

    /// Red when: WORKERS_ONLY places on a core, or on a worker of a community that is not ACTIVE, or leaves a worker out.
    @Test
    void workersOnly_placesOnTheCommunityWorkers() {
        var w = clusterWithWorkers("WORKERS_ONLY", 2);

        w.h.deliver(new ClusterInput.Reconcile());

        assertThat(hosts(w.h, ORDERS)).containsExactly(W1, W2);
    }

    /// Red when: ALL excludes the workers or the cores from the audience.
    @Test
    void all_usesCoresAndWorkers() {
        var w = clusterWithWorkers("ALL", 5);

        w.h.deliver(new ClusterInput.Reconcile());

        assertThat(hosts(w.h, ORDERS)).containsExactlyInAnyOrder(CORE_1, CORE_2, CORE_3, W1, W2);
    }

    /// Red when: WORKERS_PREFERRED with no placeable worker does not fall back to the cores.
    @Test
    void workersPreferred_withNoWorkers_fallsBackToTheCores() {
        var w = MbWorld.world(CORE_1, CORE_2, CORE_3).target(ORDERS, 2, "WORKERS_PREFERRED").activate();

        assertThat(hosts(w.h, ORDERS)).containsExactly(CORE_1, CORE_2);
    }

    /// Red when: an unparsable placement is placed anyway (as CORE_ONLY, say) instead of being reported and left alone.
    @Test
    void invalidPlacement_isReported_andNothingIsPlaced() {
        var w = MbWorld.world(CORE_1, CORE_2, CORE_3).target(ORDERS, 2, "SIDEWAYS").activate();

        assertThat(w.h.warns("invalid-placement")).hasSize(1);
        assertThat(w.h.submits()).isEmpty();
    }

    /// Red when: an instance outside the audience (on a worker, under CORE_ONLY) is retired before the instance in the audience is
    /// ACTIVE (load before unload, B-033), or is not retired afterwards.
    @Test
    void displacedInstance_isRetired_onlyAfterTheReplacementIsActive() {
        var w = MbWorld.worldWithFloor(2, CORE_1, CORE_2, CORE_3)
                        .target(ORDERS, 1, "CORE_ONLY")
                        .directive(W1, worker("east-w-0"))
                        .community("east-w-0", 3, CommunityState.ACTIVE)
                        .instance(W1, ORDERS, SliceGoal.ACTIVE, SliceObservedState.ACTIVE, G1, 21L)
                        .seams(seams -> seams.withReady(ALL).withLocalAlive(Option.some(Set.of(W1))))
                        .activate();

        assertThat(goalOf(w.h, CORE_1, ORDERS)).as("the replacement is loaded first").isEqualTo(Option.some(SliceGoal.LOADED));
        assertThat(goalOf(w.h, W1, ORDERS)).as("the worker's instance is kept while the replacement loads").isEqualTo(Option.some(SliceGoal.ACTIVE));

        new NodeSim(w.h, CORE_1, process(11L)).follows(ORDERS);
        w.h.drain();
        new NodeSim(w.h, CORE_1, process(11L)).follows(ORDERS);
        w.h.drain();
        w.h.advance(30_000L);

        assertThat(goalOf(w.h, W1, ORDERS)).as("the replacement is ACTIVE: the displaced instance goes").isEqualTo(Option.some(SliceGoal.UNLOADED));
    }
}
