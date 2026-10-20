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
import org.pragmatica.swim.SwimMembershipListener;
import org.pragmatica.swim.SwimMessage;
import org.pragmatica.swim.SwimMessage.Ack;
import org.pragmatica.swim.SwimMessage.Announce;
import org.pragmatica.swim.SwimProtocol;
import org.pragmatica.swim.SwimTransport;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1840: a worker holds its ANNOUNCE to the seeds until its activation directive is committed. Before
/// that it has no community, and the cores' membership scope admits no worker that is not a governor, so
/// every round of the announce loop was traffic they dropped. The assertions key on ANNOUNCE datagrams
/// addressed to the seed, which only the join announce loop sends (the first round fires after 500 ms).
class CoreSwimHealthDetectorAssignedAnnounceTest {
    private static final NodeId SELF = new NodeId("worker-1");
    private static final NodeId SEED_ID = new NodeId("core-1");
    private static final InetSocketAddress SEED = new InetSocketAddress("127.0.0.9", 9101);
    /// Spans two announce rounds (500 ms apart) with margin.
    private static final long QUIET_MILLIS = 1_300;

    private final List<SwimTransportCall> sent = new CopyOnWriteArrayList<>();
    private NodeInfo self;
    private CoreSwimHealthDetector detector;
    private SwimProtocol swim;

    @BeforeEach
    void setUp() {
        self = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("127.0.0.1", 9001).unwrap());
        var topology = new TopologyConfig(SELF, 5, timeSpan(1).seconds(), timeSpan(10).seconds(), List.of(self));

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
    }

    @AfterEach
    void tearDown() {
        swim.stop();
    }

    @Test
    void announceJoinWhenAssigned_beforeAssignment_announcesNothingButSeedsIdentity() throws InterruptedException {
        detector.announceJoinWhenAssigned(self, "c", 5L, 7L, List.of(SEED));

        Thread.sleep(QUIET_MILLIS);

        assertThat(announcesToSeed()).as("no directive yet: nothing announced to the seed").isEmpty();
        assertThat(detector.selfIncarnation()).as("the identity is seeded all the same").isEqualTo(5L);
    }

    @Test
    void markAssigned_afterAHeldAnnounce_releasesItToTheSeeds() {
        detector.announceJoinWhenAssigned(self, "c", 5L, 7L, List.of(SEED));

        detector.markAssigned();

        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(announcesToSeed()).as("released on assignment").isNotEmpty());
        assertThat(announcesToSeed().getFirst().message())
            .as("the held announcement is released unchanged")
            .isEqualTo(Announce.announce(self, "c", 5L, 7L));
    }

    @Test
    void announceJoinWhenAssigned_afterAssignment_announcesAtOnce() {
        detector.markAssigned();

        detector.announceJoinWhenAssigned(self, "c", 5L, 7L, List.of(SEED));

        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(announcesToSeed()).as("already assigned: no hold").isNotEmpty());
    }

    @Test
    void markAssigned_repeated_releasesTheHeldAnnounceOnce() throws InterruptedException {
        detector.announceJoinWhenAssigned(self, "c", 5L, 7L, List.of(SEED));
        detector.markAssigned();
        await().atMost(5, TimeUnit.SECONDS).until(() -> !announcesToSeed().isEmpty());
        swim.onMessage(SEED, Ack.ack(SEED_ID, 0L, List.of()));
        Thread.sleep(200);

        var afterJoinAck = announcesToSeed().size();

        detector.markAssigned();
        Thread.sleep(QUIET_MILLIS);

        assertThat(announcesToSeed()).as("a repeated assignment must not re-arm the acknowledged join").hasSize(afterJoinAck);
    }

    private List<SwimTransportCall> announcesToSeed() {
        return sent.stream()
                   .filter(call -> call.target().equals(SEED) && call.message() instanceof Announce)
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
