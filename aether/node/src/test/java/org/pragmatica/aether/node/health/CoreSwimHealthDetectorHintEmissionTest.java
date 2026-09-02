// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.node.health;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.pragmatica.aether.metrics.observation.PeerObservationStore;
import org.pragmatica.aether.node.health.fsm.SwimHealthEvents;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.cluster.metrics.HealthHintWire;
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
import org.pragmatica.swim.SwimProtocol;
import org.pragmatica.swim.SwimTransport;

import java.net.InetSocketAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Verifies the health-observation writes of `CoreSwimHealthDetector`: SWIM
/// state-transition callbacks push a `PeerHealthObservation` into the node-level
/// `PeerObservationStore`, which drains into the next `ClusterSyncPong` body.
/// `onMemberFaulty`/`onMemberLeft` take the `routeFaulty` path.
///
/// `reportHint` carries no `isLeader` branch, so the write is unconditional: a follower
/// detector records exactly what a leader one does. That per-node store ownership is what
/// lets a freshly-promoted leader read recently-observed hints from its own store instead
/// of waiting for the next pong drain. `DisconnectNode` is routed locally only when the
/// faulty peer IS the current leader; the single-writer rule for KV atoms is unaffected —
/// only the leader writes those, and the store is shared transport-side infrastructure.
///
/// `PeerObservationStore.drainHealth()` is destructive and `store` is shared between
/// `detector` and `followerDetector()`, so each test drains at most once.
class CoreSwimHealthDetectorHintEmissionTest {
    private static final NodeId SELF = new NodeId("node-1");
    private static final NodeId PEER_A = new NodeId("node-2");
    private static final NodeId PEER_B = new NodeId("node-3");

    private PeerObservationStore store;
    private CoreSwimHealthDetector detector;

    @BeforeEach
    void setUp() {
        store = PeerObservationStore.peerObservationStore();
        var router = MessageRouter.mutable();
        var nodeA = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("127.0.0.1", 9001).unwrap());
        var nodeB = NodeInfo.nodeInfo(PEER_A, NodeAddress.nodeAddress("127.0.0.2", 9001).unwrap());
        var nodeC = NodeInfo.nodeInfo(PEER_B, NodeAddress.nodeAddress("127.0.0.3", 9001).unwrap());
        var topologyConfig = new TopologyConfig(SELF, 3, timeSpan(1).seconds(), timeSpan(10).seconds(),
                                                 List.of(nodeA, nodeB, nodeC));
        Serializer serializer = Mockito.mock(Serializer.class);
        Deserializer deserializer = Mockito.mock(Deserializer.class);
        detector = CoreSwimHealthDetector.coreSwimHealthDetector(router, topologyConfig, serializer, deserializer,
                                                                   () -> Epoch.epoch(7L, 3L),
                                                                   () -> true, store);
        // Drive the FSM to Running so the membership-callback assertions exercise the
        // production-active code path with a live SwimProtocol behind the listener.
        driveToRunning(detector);
    }

    private static void driveToRunning(CoreSwimHealthDetector det) {
        var ctx = det.contextForTest();
        ctx.dispatch(new SwimHealthEvents.StartRequested());
        var swim = SwimProtocol.swimProtocol(SwimConfig.DEFAULT, new StubTransport(),
                                              noopListener(), SELF,
                                              new InetSocketAddress("127.0.0.1", 9101)).unwrap();
        ctx.dispatch(new SwimHealthEvents.ProtocolReady(swim, new StubTransport(), GossipEncryptor.none()));
    }

    private static SwimMembershipListener noopListener() {
        return new SwimMembershipListener() {
            @Override public void onMemberJoined(SwimMember member) {}
            @Override public void onMemberSuspect(SwimMember member) {}
            @Override public void onMemberFaulty(SwimMember member, boolean firstHand) {}
            @Override public void onMemberLeft(NodeId nodeId) {}
        };
    }

    private static final class StubTransport implements SwimTransport {
        @Override public Promise<Unit> send(InetSocketAddress target, SwimMessage message) { return Promise.unitPromise(); }
        @Override public Promise<Unit> start(int port, SwimMessageHandler handler) { return Promise.unitPromise(); }
        @Override public Promise<Unit> stop() { return Promise.unitPromise(); }
    }

    private CoreSwimHealthDetector followerDetector() {
        var router = MessageRouter.mutable();
        var nodeA = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("127.0.0.1", 9001).unwrap());
        var nodeB = NodeInfo.nodeInfo(PEER_A, NodeAddress.nodeAddress("127.0.0.2", 9001).unwrap());
        var nodeC = NodeInfo.nodeInfo(PEER_B, NodeAddress.nodeAddress("127.0.0.3", 9001).unwrap());
        var topologyConfig = new TopologyConfig(SELF, 3, timeSpan(1).seconds(), timeSpan(10).seconds(),
                                                 List.of(nodeA, nodeB, nodeC));
        Serializer serializer = Mockito.mock(Serializer.class);
        Deserializer deserializer = Mockito.mock(Deserializer.class);
        var det = CoreSwimHealthDetector.coreSwimHealthDetector(router, topologyConfig, serializer, deserializer,
                                                                 () -> Epoch.epoch(7L, 3L),
                                                                 () -> false, store);
        driveToRunning(det);
        return det;
    }

    @Nested
    class HintEmissions {
        @Test
        void onMemberJoined_emitsNoHint() {
            // Resurrection guard: a bare SWIM join is gossip, not reachability proof.
            // It must NOT record a HEALTHY observation — HEALTHY arrives only via onNodeConnected
            // (real QUIC connection) or a probe-ack. See SwimHealthState.handlePeerJoined.
            var member = SwimMember.swimMember(PEER_A, new InetSocketAddress("127.0.0.2", 9002));

            detector.onMemberJoined(member);

            assertThat(store.drainHealth())
                .as("bare onMemberJoined must not record a health observation (no HEALTHY resurrection)")
                .isEmpty();
        }

        @Test
        void onMemberSuspect_emitsSuspectedHint() {
            var member = SwimMember.swimMember(PEER_A, MemberState.SUSPECT, 0,
                                                new InetSocketAddress("127.0.0.2", 9002));

            detector.onMemberSuspect(member);

            assertThat(store.drainHealth()).singleElement()
                                           .satisfies(obs -> {
                                               assertThat(obs.peerId()).isEqualTo(PEER_A);
                                               assertThat(obs.hint()).isEqualTo(HealthHintWire.SUSPECTED);
                                           });
        }

        @Test
        void onMemberFaulty_emitsFaultyHint() throws InterruptedException {
            var faultyMember = SwimMember.swimMember(PEER_A, MemberState.FAULTY, 0,
                                                      new InetSocketAddress("127.0.0.2", 9002));

            detector.onMemberFaulty(faultyMember, true);
            Thread.sleep(50); // tolerate any async dispatch

            assertThat(store.drainHealth()).singleElement()
                                           .satisfies(obs -> assertThat(obs.hint()).isEqualTo(HealthHintWire.FAULTY));
        }

        @Test
        void onMemberLeft_emitsFaultyHint() throws InterruptedException {
            detector.onMemberLeft(PEER_B);
            Thread.sleep(50);

            assertThat(store.drainHealth()).singleElement()
                                           .satisfies(obs -> {
                                               assertThat(obs.peerId()).isEqualTo(PEER_B);
                                               assertThat(obs.hint()).isEqualTo(HealthHintWire.FAULTY);
                                           });
        }

        @Test
        void onNodeConnected_promotesMembershipWithoutHealthObservation() {
            // Two-plane liveness (commit e69f57a4b): in Running, a real QUIC connection for a
            // peer that SWIM does not yet track acquires membership inside SwimProtocol
            // (addSeedMember, tombstone-gated) and never reaches ctx.reportHint. HEALTHY flows
            // via SwimProtocol.recordHealthyAndEmit → the SwimObservation stream instead, so the
            // observation store stays empty on connect; the observable contract is that the peer
            // becomes a tracked SWIM member.
            detector.onNodeConnected(PEER_A);

            assertThat(store.drainHealth())
                .as("PeerConnected for an untracked peer in Running must not record a health observation")
                .isEmpty();
            assertThat(detector.currentHealth().map(snapshot -> snapshot.peerHealth().containsKey(PEER_A)).or(false))
                .as("connected peer is now tracked in SWIM membership")
                .isTrue();
        }

        /// Regression: CTM-provisioned replacements join the cluster via QUIC Hello with
        /// NodeIds that are NOT in the static `topologyConfig.coreNodes()` list (e.g.
        /// `aether-core-node-0-XXX`). The id-only `onNodeConnected` overload could not
        /// resolve them via static lookup so SWIM never gained membership for them, and
        /// when their containers later died there was no probe failure to drive REMOVE —
        /// the phantom stayed in `coreNodes` indefinitely, making `coreCount > coreMax`.
        ///
        /// The `onNodeConnected(NodeInfo)` overload carries the address learned at QUIC
        /// Hello time so SWIM can seed the dynamic peer regardless of static config. HEALTHY
        /// flows via the SwimProtocol observation stream (two-plane liveness, e69f57a4b), so
        /// the observable contract is membership acquisition with no health observation.
        @Test
        void onNodeConnected_withDynamicallyLearnedPeer_promotesMembershipWithoutHealthObservation() {
            var dynamic = new NodeId("aether-core-node-0-deadbeef");
            var info = NodeInfo.nodeInfo(dynamic, NodeAddress.nodeAddress("aether-core-node-0-deadbeef", 9001).unwrap());

            detector.onNodeConnected(info);

            assertThat(store.drainHealth())
                .as("dynamic PeerConnected in Running must not record a health observation")
                .isEmpty();
            assertThat(detector.currentHealth().map(snapshot -> snapshot.peerHealth().containsKey(dynamic)).or(false))
                .as("dynamically-learned connected peer is now tracked in SWIM membership")
                .isTrue();
        }
    }

    @Nested
    class DefaultFactory {
        /// Construction smoke check for the four-argument factory: it builds a detector whose
        /// membership callback runs without throwing. That detector owns a private
        /// `PeerObservationStore` with no test accessor, so this pins no observation content.
        @Test
        void defaultFactory_acceptsMemberCallback_withoutThrowing() {
            var router = MessageRouter.mutable();
            var topology = new TopologyConfig(SELF, 1, timeSpan(1).seconds(), timeSpan(10).seconds(),
                                               List.of(NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("127.0.0.1", 9001).unwrap())));
            Serializer serializer = Mockito.mock(Serializer.class);
            Deserializer deserializer = Mockito.mock(Deserializer.class);
            var plain = CoreSwimHealthDetector.coreSwimHealthDetector(router, topology, serializer, deserializer);
            var member = SwimMember.swimMember(PEER_A, new InetSocketAddress("127.0.0.2", 9002));

            assertThatCode(() -> plain.onMemberJoined(member)).doesNotThrowAnyException();
        }
    }

    /// Per-node store ownership: `reportHint` has no `isLeader` branch, so a follower's
    /// callbacks write the same `PeerHealthObservation`s a leader's would — peer, hint and
    /// the observer's epoch. Promoting a follower to leader therefore surfaces the
    /// recently-observed hints immediately, without a fresh pong drain.
    @Nested
    class FollowerStoreWrites {
        @Test
        void onMemberFaulty_follower_writesToStore() {
            var follower = followerDetector();
            var faultyMember = SwimMember.swimMember(PEER_A, MemberState.FAULTY, 0,
                                                      new InetSocketAddress("127.0.0.2", 9002));

            follower.onMemberFaulty(faultyMember, true);

            assertThat(store.drainHealth()).singleElement()
                                           .satisfies(obs -> {
                                               assertThat(obs.peerId()).isEqualTo(PEER_A);
                                               assertThat(obs.hint()).isEqualTo(HealthHintWire.FAULTY);
                                               assertThat(obs.observedEpochTerm()).isEqualTo(7L);
                                               assertThat(obs.observedEpochCounter()).isEqualTo(3L);
                                           });
        }

        @Test
        void onMemberSuspect_follower_writesToStore() {
            var follower = followerDetector();
            var suspect = SwimMember.swimMember(PEER_A, MemberState.SUSPECT, 0,
                                                  new InetSocketAddress("127.0.0.2", 9002));

            follower.onMemberSuspect(suspect);

            assertThat(store.drainHealth()).singleElement()
                                           .satisfies(obs -> assertThat(obs.hint()).isEqualTo(HealthHintWire.SUSPECTED));
        }

        @Test
        void onMemberLeft_follower_writesToStore() {
            var follower = followerDetector();

            follower.onMemberLeft(PEER_B);

            assertThat(store.drainHealth()).singleElement()
                                           .satisfies(obs -> {
                                               assertThat(obs.peerId()).isEqualTo(PEER_B);
                                               assertThat(obs.hint()).isEqualTo(HealthHintWire.FAULTY);
                                           });
        }

        @Test
        void onMemberJoined_follower_writesNothingToStore() {
            // Resurrection guard (follower path): a bare gossip join writes nothing to the
            // store. HEALTHY requires a real connection/probe-ack.
            var follower = followerDetector();
            var member = SwimMember.swimMember(PEER_A, new InetSocketAddress("127.0.0.2", 9002));

            follower.onMemberJoined(member);

            assertThat(store.drainHealth())
                .as("follower bare join must not write a health observation to the store")
                .isEmpty();
        }
    }
}
