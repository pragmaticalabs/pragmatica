// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.statemachine.FsmObserver;
import org.pragmatica.swim.SwimConfig;
import org.pragmatica.swim.SwimMember;
import org.pragmatica.swim.SwimMember.MemberState;
import org.pragmatica.swim.SwimMembershipListener;
import org.pragmatica.swim.SwimMessage;
import org.pragmatica.swim.SwimMessage.MembershipUpdate;
import org.pragmatica.swim.SwimMessage.Ping;
import org.pragmatica.swim.SwimProtocol;
import org.pragmatica.swim.SwimTransport;

import static org.assertj.core.api.Assertions.assertThat;

/// Two replacement cores minted by DIFFERENT leaders never see each other's ANNOUNCE (each announces
/// only to the seed list it was minted with) and, arriving with different seeds, never dial each
/// other first either. One learns the other ONLY through a gossiped `MembershipUpdate`.
///
/// Since #1390 a member counts as a core only with a literal `role=core` label, so the gossip has to
/// carry that label: without it the replacement is present and healthy on this node, uncounted by the
/// leader's `coreCountedMembers`, and the leader provisions a phantom extra core. The chain here is the
/// real one — `SwimProtocol` → its observation stream → `AetherNode.routeSwimEdgeToMembershipFsm` →
/// `MembershipFsm` — nothing between the gossip and the core count is stubbed.
class SwimGossipRoleReachTest {
    private static final NodeId SELF = new NodeId("node-self");
    private static final NodeId GOSSIPER = new NodeId("node-gossiper");
    private static final NodeId REPLACEMENT = new NodeId("node-replacement");
    private static final InetSocketAddress SELF_ADDR = new InetSocketAddress("127.0.0.1", 9400);
    private static final InetSocketAddress GOSSIPER_ADDR = new InetSocketAddress("127.0.0.1", 9401);
    private static final InetSocketAddress REPLACEMENT_ADDR = new InetSocketAddress("127.0.0.1", 9402);
    private static final Map<String, String> CORE_LABELS = Map.of(NodeInfo.LABEL_ROLE, "core",
                                                                   NodeInfo.LABEL_SOURCE, "replacement");

    private MembershipFsm membershipFsm;
    private SwimProtocol protocol;
    private long sequence = 0L;

    @BeforeEach
    void setUp() {
        membershipFsm = MembershipFsm.membershipFsm(FsmObserver.noop(),
                                                    System::currentTimeMillis,
                                                    Long.MAX_VALUE,
                                                    TimeSpan.timeSpan(1).hours());
        protocol = SwimProtocol.swimProtocol(SwimConfig.swimConfig(), new NullTransport(), new NullListener(), SELF, SELF_ADDR)
                               .unwrap();
        protocol.addObservationListener(observation -> AetherNode.routeSwimEdgeToMembershipFsm(observation, membershipFsm));
    }

    @Test
    void twoReplacementsMintedByDifferentLeaders_learnedOnlyByGossip_isCoreCounted() {
        gossipAlive(REPLACEMENT, CORE_LABELS);

        assertThat(membershipFsm.memberStates()).containsEntry(REPLACEMENT, "Member");
        assertThat(membershipFsm.coreCountedMembers()).contains(REPLACEMENT);
    }

    /// The control that shows the instrument can tell the two cases apart: the same replacement,
    /// present and healthy, learned by gossip that names no role — #1390's rule holds, it is NOT a core.
    @Test
    void gossipedReplacementWithoutRole_isPresentButNotCoreCounted() {
        gossipAlive(REPLACEMENT, Map.of());

        assertThat(membershipFsm.memberStates()).containsEntry(REPLACEMENT, "Member");
        assertThat(membershipFsm.coreCountedMembers()).doesNotContain(REPLACEMENT);
    }

    @Test
    void replacementGossipedBlankThenWithRole_becomesCoreCounted() {
        gossipAlive(REPLACEMENT, Map.of());
        assertThat(membershipFsm.coreCountedMembers()).doesNotContain(REPLACEMENT);

        gossipAlive(REPLACEMENT, CORE_LABELS);

        assertThat(membershipFsm.coreCountedMembers()).contains(REPLACEMENT);
    }

    private void gossipAlive(NodeId subject, Map<String, String> labels) {
        var update = MembershipUpdate.membershipUpdate(subject, MemberState.ALIVE, 0L, REPLACEMENT_ADDR, 0L, labels);

        protocol.onMessage(GOSSIPER_ADDR, new Ping(GOSSIPER, ++sequence, List.of(update)));
    }

    private static final class NullTransport implements SwimTransport {
        @Override public Promise<Unit> send(InetSocketAddress target, SwimMessage message) {return Promise.success(Unit.unit());}
        @Override public Promise<Unit> start(int port, SwimMessageHandler handler) {return Promise.success(Unit.unit());}
        @Override public Promise<Unit> stop() {return Promise.success(Unit.unit());}
    }

    private static final class NullListener implements SwimMembershipListener {
        @Override public void onMemberJoined(SwimMember member) {}
        @Override public void onMemberSuspect(SwimMember member) {}
        @Override public void onMemberFaulty(SwimMember member, boolean firstHand) {}
        @Override public void onMemberLeft(NodeId nodeId) {}
    }
}
