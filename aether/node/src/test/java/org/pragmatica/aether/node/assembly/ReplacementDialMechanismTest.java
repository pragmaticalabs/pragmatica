// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.health.WorkerEndpointDirectory;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.rabia.VoterConfiguration;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.NodeAddress;

import static org.assertj.core.api.Assertions.assertThat;

/// #1200 — the rejoin mechanism of a node that boots into a cluster whose gossip key it does not hold, pinned piece by piece.
///
/// Found by removal on `EmberGossipKeyRotatedJoinTest`: the replacement dials its mint-time `--peers` ITSELF after a 2 s isolation grace
/// ([MembershipAssembly#bootstrapInitiator]), from two redundant feeds of dial targets that need no gossip to decrypt: the FSM descriptors
/// seeded from the configured topology ([MembershipAssembly#seedConfiguredDescriptors], pinned here) and SWIM seeding (pinned in
/// `AetherNodeStartSwimTest`). On the Ember pin, removing either feed alone stays green, removing both is RED, and switching the initiator
/// rule off is RED; these unit pins say the same for each piece, so a change that breaks one is named at the piece.
class ReplacementDialMechanismTest {
    private static final NodeId SELF = new NodeId("aether-node-replacement");
    private static final NodeId PEER_A = new NodeId("aa-1");
    private static final NodeId PEER_B = new NodeId("aa-2");
    private static final long PAST_THE_GRACE_NANOS = AetherNode.PENDING_CORE_ISOLATION_GRACE.nanos() + 1_000_000_000L;

    // ---- the initiator rule: a genesis-pending core dials its configured cores itself once it has been isolated for the grace ----

    @Test
    void bootstrapInitiator_noConfigurationYet_noConnectionToAConfiguredCore_pastTheGrace_initiates() {
        assertThat(initiator(Option.none(), Set.of(), PAST_THE_GRACE_NANOS)).isTrue();
    }

    @Test
    void bootstrapInitiator_beforeTheGraceElapses_doesNotInitiate() {
        assertThat(initiator(Option.none(), Set.of(), 0L)).as("the grace keeps a cold-start candidate from dialing both ways").isFalse();
    }

    @Test
    void bootstrapInitiator_alreadyConnectedToAConfiguredCore_doesNotInitiate() {
        assertThat(initiator(Option.none(), Set.of(PEER_A), PAST_THE_GRACE_NANOS)).isFalse();
    }

    @Test
    void bootstrapInitiator_aConnectionToANodeThatIsNotConfigured_doesNotCountAsConnected() {
        assertThat(initiator(Option.none(), Set.of(new NodeId("stranger")), PAST_THE_GRACE_NANOS)).isTrue();
    }

    @Test
    void bootstrapInitiator_installedConfigurationWithoutSelf_initiates_aStagedCore() {
        assertThat(initiator(Option.some(configuration(PEER_A, PEER_B)), Set.of(PEER_A), 0L)).isTrue();
    }

    @Test
    void bootstrapInitiator_installedConfigurationWithSelf_doesNotInitiate() {
        assertThat(initiator(Option.some(configuration(SELF, PEER_A, PEER_B)), Set.of(), PAST_THE_GRACE_NANOS)).isFalse();
    }

    // ---- feed 1: the configured cores are dial targets from the first moment ----

    @Test
    void seedConfiguredDescriptors_makesEveryConfiguredCoreADialTargetWithItsAddress() {
        var membership = MembershipFsm.membershipFsm();
        var configured = List.of(info(PEER_A, 6000), info(PEER_B, 6001));

        MembershipAssembly.seedConfiguredDescriptors(configured, SELF, false, membership);

        var targets = MembershipAssembly.desiredDialTargets(membership, WorkerEndpointDirectory.workerEndpointDirectory(), _ -> true);

        assertThat(targets.stream().map(NodeInfo::id).collect(Collectors.toSet())).containsExactlyInAnyOrder(PEER_A, PEER_B);
        assertThat(targets.stream().filter(target -> target.id().equals(PEER_B)).findFirst().orElseThrow().address().port()).isEqualTo(6001);
    }

    /// Control: without the seed there is nothing to dial, so the assertion above can fail.
    @Test
    void desiredDialTargets_withoutTheSeed_isEmpty() {
        assertThat(MembershipAssembly.desiredDialTargets(MembershipFsm.membershipFsm(), WorkerEndpointDirectory.workerEndpointDirectory(), _ -> true)).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static boolean initiator(Option<VoterConfiguration> installed, Set<NodeId> connected, long isolatedForNanos) {
        var node = (RabiaNode<KVCommand<AetherKey>>) Mockito.mock(RabiaNode.class);
        var network = Mockito.mock(ClusterNetwork.class);

        Mockito.when(node.voterConfiguration()).thenReturn(installed);
        Mockito.when(node.network()).thenReturn(network);
        Mockito.when(network.connectedPeers()).thenReturn(connected);

        return MembershipAssembly.bootstrapInitiator(node, SELF, Set.of(PEER_A, PEER_B), new AtomicLong(System.nanoTime() - isolatedForNanos));
    }

    private static VoterConfiguration configuration(NodeId... members) {
        return VoterConfiguration.voterConfiguration(1L, List.of(members)).unwrap();
    }

    private static NodeInfo info(NodeId id, int port) {
        return NodeInfo.nodeInfo(id, NodeAddress.nodeAddress("127.0.0.1", port).unwrap());
    }
}
