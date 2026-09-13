// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.Set;

import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.statemachine.FsmObserver;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The #1050 / #1062 wiring pin, at the REAL seam. `AetherNode.drainGraceLiveness` builds every piece of
/// membership and liveness evidence the CTM consults before an irreversible reap, plus the configured core
/// count that the CTM, the `LeaderReconciler` and the `QuorumLossDetector` share. Each projection is pinned
/// against a real boot-seeded [MembershipFsm], where the counted, tracked and observed projections diverge.
///
/// The configured-count pin is the S2 finding of verify-1057: a count that silently read 0 made every reap
/// quorum-safe (fail-open). The count is now built HERE, from the committed config or the bootstrap topology,
/// so a count forced to 0 reddens this class. The CTM separately treats any count below 1 as NOT quorum-safe.
class DrainGraceLivenessSeamTest {
    private static final NodeId SELF = new NodeId("node-self");
    private static final NodeId PEER_B = new NodeId("node-b");
    private static final NodeId PEER_C = new NodeId("node-c");
    private static final NodeId BOOTING = new NodeId("node-booting");

    private static final long NO_HINT_DECAY = Long.MAX_VALUE;
    private static final TimeSpan BACKSTOP = TimeSpan.timeSpan(40).millis();
    private static final int TOPOLOGY_CORE_NODES = 3;

    private static MembershipFsm bootSeededFsm() {
        var fsm = MembershipFsm.membershipFsm(FsmObserver.noop(),
                                              System::currentTimeMillis,
                                              NO_HINT_DECAY,
                                              BACKSTOP);

        fsm.seed(Set.of(SELF, PEER_B, PEER_C));

        return fsm;
    }

    private static Option<AetherValue.ClusterConfigValue> committedCoreCount(int coreCount) {
        return Option.some(new AetherValue.ClusterConfigValue("",
                                                              "",
                                                              "1.0.0",
                                                              List.of(new AetherValue.TopologyEntry("primary", "core", coreCount)),
                                                              3,
                                                              9,
                                                              "test",
                                                              1L,
                                                              System.currentTimeMillis()));
    }

    /// A drain request moves the target to DEPARTING: it stops COUNTING but is still TRACKED (not DEAD), so
    /// a reap gate sees it as uncounted while the activation replay still protects its instance.
    @Test
    void drainGraceLiveness_afterDrainRequested_countedExcludesTarget_trackedKeepsIt() {
        var fsm = bootSeededFsm();

        fsm.onDrainRequested(PEER_C);

        var liveness = AetherNode.drainGraceLiveness(() -> fsm, Option::none, TOPOLOGY_CORE_NODES, () -> null, _ -> false, () -> null, Set::of);

        assertThat(liveness.coreCountedMembers()
                           .get()).containsExactlyInAnyOrder(SELF, PEER_B);
        assertThat(liveness.trackedMembers()
                           .get()).containsExactlyInAnyOrder(SELF, PEER_B, PEER_C);
    }

    /// The counted projection is the reconciler's COUNTED set, not the observed-reachability one. Armed by
    /// asserting the two diverge in the boot-seed state, so the assertion below cannot pass vacuously.
    @Test
    void drainGraceLiveness_afterBootSeed_readsTheCountedProjection() {
        var fsm = bootSeededFsm();

        assertThat(fsm.coreObservedMembers(SELF)).as("arming: the observed projection must diverge from the counted one")
                                                 .containsExactly(SELF);

        var liveness = AetherNode.drainGraceLiveness(() -> fsm, Option::none, TOPOLOGY_CORE_NODES, () -> null, _ -> false, () -> null, Set::of);

        assertThat(liveness.coreCountedMembers()
                           .get()).containsExactlyInAnyOrder(SELF, PEER_B, PEER_C);
    }

    /// S2: the configured count is the committed `ClusterConfigValue.coreCount` when present, never 0.
    @Test
    void drainGraceLiveness_configuredCoreCount_readsTheCommittedConfig() {
        var liveness = AetherNode.drainGraceLiveness(() -> null, () -> committedCoreCount(5), TOPOLOGY_CORE_NODES, () -> null, _ -> false, () -> null, Set::of);

        assertThat(liveness.configuredCoreCount()
                           .getAsInt()).isEqualTo(5);
    }

    /// S2: before the config is committed, the bootstrap topology size stands in — still never 0.
    @Test
    void drainGraceLiveness_configuredCoreCount_fallsBackToTopologySize() {
        var liveness = AetherNode.drainGraceLiveness(() -> null, Option::none, TOPOLOGY_CORE_NODES, () -> null, _ -> false, () -> null, Set::of);

        assertThat(liveness.configuredCoreCount()
                           .getAsInt()).isEqualTo(TOPOLOGY_CORE_NODES);
    }

    /// Before the FSM, the SWIM detector or the reconciler is published, every projection reads empty or
    /// false, never a throw. The transport view and the retained dispatched set pass through as given.
    @Test
    void drainGraceLiveness_beforePublication_readsEmpty_passingTransportAndRetainedThrough() {
        var liveness = AetherNode.drainGraceLiveness(() -> null,
                                                     Option::none,
                                                     TOPOLOGY_CORE_NODES,
                                                     () -> null,
                                                     PEER_B::equals,
                                                     () -> null,
                                                     () -> Set.of(BOOTING));

        assertThat(liveness.coreCountedMembers()
                           .get()).isEmpty();
        assertThat(liveness.trackedMembers()
                           .get()).isEmpty();
        assertThat(liveness.swimAlive()
                           .test(PEER_B)).isFalse();
        assertThat(liveness.transportConnected()
                           .test(PEER_B)).isTrue();
        assertThat(liveness.inFlightProvisioning()
                           .get()).containsExactly(BOOTING);
    }
}
