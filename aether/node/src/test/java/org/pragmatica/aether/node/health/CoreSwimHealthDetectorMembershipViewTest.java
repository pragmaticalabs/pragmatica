// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.node.health;

import org.pragmatica.aether.node.health.fsm.SwimHealthEvents;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.swim.GossipEncryptor;
import org.pragmatica.swim.SwimConfig;
import org.pragmatica.swim.SwimMember;
import org.pragmatica.swim.SwimMember.MemberState;
import org.pragmatica.swim.SwimMembershipListener;
import org.pragmatica.swim.SwimMessage;
import org.pragmatica.swim.SwimMessage.Ack;
import org.pragmatica.swim.SwimMessage.Announce;
import org.pragmatica.swim.SwimMessage.MembershipUpdate;
import org.pragmatica.swim.SwimProtocol;
import org.pragmatica.swim.SwimTransport;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1803: a core that learned the committed electorate after joining re-asks its peers for the voters its SWIM
/// view lacks. The assertions key on the address of a member that only the re-ask targets: the join announce
/// loop (which keeps running in the background) addresses the configured seeds and nobody else.
class CoreSwimHealthDetectorMembershipViewTest {
    private static final NodeId SELF = new NodeId("aether-replacement");
    private static final NodeId KNOWN = new NodeId("hetzner-core-0");
    private static final NodeId LEADER = new NodeId("aether-new-leader");
    private static final InetSocketAddress SEED = new InetSocketAddress("127.0.0.9", 9101);
    private static final InetSocketAddress KNOWN_SWIM = new InetSocketAddress("127.0.0.2", 9101);

    private final List<SwimTransportCall> sent = new CopyOnWriteArrayList<>();
    private CoreSwimHealthDetector detector;
    private SwimProtocol swim;

    @BeforeEach
    void setUp() {
        var self = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("127.0.0.1", 9001).unwrap());
        var known = NodeInfo.nodeInfo(KNOWN, NodeAddress.nodeAddress("127.0.0.2", 9001).unwrap());
        var topology = new TopologyConfig(SELF, 5, timeSpan(1).seconds(), timeSpan(10).seconds(), List.of(self, known));

        detector = CoreSwimHealthDetector.coreSwimHealthDetector(MessageRouter.mutable(),
                                                                 topology,
                                                                 Mockito.mock(Serializer.class),
                                                                 Mockito.mock(Deserializer.class));
        var ctx = detector.contextForTest();
        var transport = new RecordingTransport();

        ctx.dispatch(new SwimHealthEvents.StartRequested());
        swim = SwimProtocol.swimProtocol(SwimConfig.DEFAULT, transport, noopListener(), SELF, new InetSocketAddress("127.0.0.1", 9101))
                           .unwrap();
        ctx.dispatch(new SwimHealthEvents.ProtocolReady(swim, transport, GossipEncryptor.none()));
        detector.announceJoin(self, "c", 1L, 7L, List.of(SEED));
        swim.onMessage(KNOWN_SWIM,
                       Ack.ack(KNOWN, 0L, List.of(MembershipUpdate.membershipUpdate(KNOWN, MemberState.ALIVE, 1, KNOWN_SWIM))));
        assertThat(swim.members()).as("precondition: one live member is known").containsKey(KNOWN);
    }

    @Test
    void requestMembershipView_electorateNamesAnUnknownVoter_reAsksTheKnownMembers() {
        detector.requestMembershipView(Set.of(SELF, KNOWN, LEADER));

        assertThat(announcedTo(KNOWN_SWIM)).as("an unknown voter: the known live member is re-asked for its view").isNotEmpty();
    }

    @Test
    void requestMembershipView_electorateFullyKnown_doesNotAsk() {
        detector.requestMembershipView(Set.of(SELF, KNOWN));

        assertThat(announcedTo(KNOWN_SWIM)).as("nothing missing: no re-ask").isEmpty();
    }

    @Test
    void requestMembershipView_repeatedForTheSameMissingVoters_isPaced() {
        detector.requestMembershipView(Set.of(SELF, KNOWN, LEADER));
        detector.requestMembershipView(Set.of(SELF, KNOWN, LEADER));
        detector.requestMembershipView(Set.of(SELF, KNOWN, LEADER));

        assertThat(announcedTo(KNOWN_SWIM)).as("three calls inside the minimum interval send once").hasSize(1);
    }

    private List<SwimTransportCall> announcedTo(InetSocketAddress target) {
        return sent.stream()
                   .filter(call -> call.target().equals(target) && call.message() instanceof Announce)
                   .toList();
    }

    private static SwimMembershipListener noopListener() {
        return new SwimMembershipListener() {
            @Override public void onMemberJoined(SwimMember member) {}
            @Override public void onMemberSuspect(SwimMember member) {}
            @Override public void onMemberFaulty(SwimMember member, boolean firstHand) {}
            @Override public void onMemberLeft(NodeId nodeId) {}
        };
    }

    private record SwimTransportCall(InetSocketAddress target, SwimMessage message) {}

    private final class RecordingTransport implements SwimTransport {
        @Override
        public Promise<Unit> send(InetSocketAddress target, SwimMessage message) {
            sent.add(new SwimTransportCall(target, message));

            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> start(int port, SwimMessageHandler handler) {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.unitPromise();
        }
    }
}
