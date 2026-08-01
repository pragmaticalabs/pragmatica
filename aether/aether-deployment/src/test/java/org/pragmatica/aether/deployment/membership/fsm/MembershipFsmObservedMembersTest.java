// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.statemachine.FsmObserver;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/// #557 / #558 — the OBSERVED-REACHABILITY projection [`MembershipFsm#coreObservedMembers`].
///
/// [`MembershipFsm#seed`] promotes the whole CONFIGURED core set to MEMBER at wiring time, so
/// [`MembershipFsm#coreCountedMembers`] reports every core before a single packet has moved.
/// Feeding that set to the quorum numerator made boot-time quorum a statement about configuration
/// rather than reachability — a five-node cluster declared quorum with zero QUIC connections and
/// broadcast its `SyncRequest` into an empty network.
///
/// These tests pin the divergence between the two projections during the boot window, and pin the
/// latch semantics that keep the observed set from flapping once formation is done.
class MembershipFsmObservedMembersTest {
    private static final NodeId SELF = new NodeId("node-self");
    private static final NodeId PEER_B = new NodeId("node-b");
    private static final NodeId PEER_C = new NodeId("node-c");
    private static final NodeId WORKER_D = new NodeId("node-worker-d");

    private static final TimeSpan SHORT_BACKSTOP = TimeSpan.timeSpan(40).millis();
    private static final long NO_HINT_DECAY = Long.MAX_VALUE;

    private static MembershipFsm bootSeededCluster() {
        var fsm = MembershipFsm.membershipFsm(FsmObserver.noop(),
                                              System::currentTimeMillis,
                                              NO_HINT_DECAY,
                                              SHORT_BACKSTOP);

        fsm.seed(Set.of(SELF, PEER_B, PEER_C));

        return fsm;
    }

    private static NodeInfo labeledInfo(NodeId id, Map<String, String> labels) {
        return NodeInfo.nodeInfo(id, NodeAddress.nodeAddress("host-x", 6000).unwrap(), labels);
    }

    /// THE regression. Before the fix this returned all three seeded ids, which is what let
    /// `TopologyObserver` declare quorum against a network that had not connected.
    @Test
    void coreObservedMembers_afterBootSeedWithNoObservation_containsOnlySelf() {
        var fsm = bootSeededCluster();

        assertThat(fsm.coreCountedMembers()).containsExactlyInAnyOrder(SELF, PEER_B, PEER_C);
        assertThat(fsm.coreObservedMembers(SELF)).containsExactly(SELF);
    }

    /// Self is reachable by definition and never SWIM-observes itself, so it must be counted
    /// without evidence — otherwise a 5-node cluster would need 3 observed PEERS plus self, one
    /// node more than quorum actually requires.
    @Test
    void coreObservedMembers_selfNeverObserved_isStillIncluded() {
        var fsm = bootSeededCluster();

        assertThat(fsm.coreObservedMembers(SELF)).contains(SELF);
    }

    @Test
    void coreObservedMembers_afterSwimHealthy_includesObservedPeer() {
        var fsm = bootSeededCluster();

        fsm.onSwimHealthy(PEER_B, 1L);

        assertThat(fsm.coreObservedMembers(SELF)).containsExactlyInAnyOrder(SELF, PEER_B);
    }

    /// The real boot window: a seeded member whose QUIC handshake completes before SWIM has
    /// admitted it. `nttConnectTap` routes that into the FSM as `PeerConnected`, which is
    /// first-hand reachability evidence even though transport never promotes toward MEMBER.
    @Test
    void coreObservedMembers_afterPeerConnectedWithoutSwim_includesConnectedPeer() {
        var fsm = bootSeededCluster();

        fsm.onPeerConnected(PEER_B);

        assertThat(fsm.coreObservedMembers(SELF)).containsExactlyInAnyOrder(SELF, PEER_B);
    }

    /// The latch must survive a transport flap. A LIVE reachability signal here would drop the
    /// peer out of the quorum numerator on every disconnect and emit spurious PASSIVE edges.
    @Test
    void coreObservedMembers_afterConnectThenDisconnect_retainsPeer() {
        var fsm = bootSeededCluster();

        fsm.onPeerConnected(PEER_B);
        fsm.onPeerDisconnected(PEER_B);

        assertThat(fsm.coreObservedMembers(SELF)).contains(PEER_B);
    }

    /// The latch must also survive SWIM doubt: SUSPECT still counts toward membership, so it must
    /// still count toward the observed set. `healthyStreak` resets here — which is exactly why the
    /// live streak could not be used as the discriminator.
    @Test
    void coreObservedMembers_afterHealthyThenSuspect_retainsPeer() {
        var fsm = bootSeededCluster();

        fsm.onSwimHealthy(PEER_B, 1L);
        fsm.onSwimSuspect(PEER_B, 2L);

        assertThat(fsm.coreCountedMembers()).contains(PEER_B);
        assertThat(fsm.coreObservedMembers(SELF)).contains(PEER_B);
    }

    /// Role scoping is inherited from the counting projection — a worker must never inflate the
    /// quorum numerator even once it is genuinely observed.
    @Test
    void coreObservedMembers_observedWorkerRole_isExcluded() {
        var fsm = bootSeededCluster();

        fsm.onSwimHealthy(WORKER_D, 1L);
        fsm.onMemberDescriptor(labeledInfo(WORKER_D, Map.of(NodeInfo.LABEL_ROLE, "worker")));

        assertThat(fsm.coreObservedMembers(SELF)).doesNotContain(WORKER_D);
    }

    /// Once every peer is observed the projection converges on the counting one, so this is a
    /// boot-time gate only and imposes nothing on steady state.
    @Test
    void coreObservedMembers_afterAllPeersObserved_equalsCoreCountedMembers() {
        var fsm = bootSeededCluster();

        fsm.onSwimHealthy(PEER_B, 1L);
        fsm.onSwimHealthy(PEER_C, 1L);

        assertThat(fsm.coreObservedMembers(SELF)).containsExactlyInAnyOrderElementsOf(fsm.coreCountedMembers());
    }

    /// The `QuorumLossDetector` numerator. The plain strict count is seed-derived, so it armed the
    /// detector's arm-after-first-quorum latch on CONFIGURATION during construction — spending the
    /// cold-start guard before the cluster had formed.
    @Test
    void strictCoreObservedMemberCount_afterBootSeedWithNoObservation_countsOnlySelf() {
        var fsm = bootSeededCluster();

        assertThat(fsm.strictCoreMemberCount()).isEqualTo(3);
        assertThat(fsm.strictCoreObservedMemberCount(SELF)).isEqualTo(1);
    }

    @Test
    void strictCoreObservedMemberCount_afterPeersObserved_reachesQuorum() {
        var fsm = bootSeededCluster();

        fsm.onSwimHealthy(PEER_B, 1L);
        fsm.onPeerConnected(PEER_C);

        assertThat(fsm.strictCoreObservedMemberCount(SELF)).isEqualTo(3);
    }
}
