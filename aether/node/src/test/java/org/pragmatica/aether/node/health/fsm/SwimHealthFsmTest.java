// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.health.fsm;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.metrics.observation.PeerObservationStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmTestHarness;
import org.pragmatica.swim.GossipEncryptor;
import org.pragmatica.swim.SwimConfig;
import org.pragmatica.swim.SwimMember;
import org.pragmatica.swim.SwimMember.MemberState;
import org.pragmatica.swim.SwimMessage;
import org.pragmatica.swim.SwimProtocol;
import org.pragmatica.swim.SwimTransport;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// FSM-level tests for the SWIM health detector. Drives the FSM directly via
/// [`FsmTestHarness`] — no wall-clock sleeps, no UDP sockets.
///
/// Covers the four scenarios required by the refactor plan:
/// 1. Happy path through all four states.
/// 2. CAS contention on `Running → LocalDisconnect`.
/// 3. Events ignored in terminal / inapplicable states.
/// 4. Leader-routing: follower sees faulty-peer-is-current-leader and routes DisconnectNode.
class SwimHealthFsmTest {

    private static final NodeId SELF = new NodeId("node-1");
    private static final NodeId PEER_A = new NodeId("node-2");
    private static final NodeId PEER_B = new NodeId("node-3");

    private final List<NetworkServiceMessage.DisconnectNode> routedDisconnects = new ArrayList<>();
    private FsmTestHarness<SwimHealthState, SwimHealthEvents> harness;
    private AtomicReference<SwimHealthContext> ctxRef;

    private void buildHarness(boolean isLeader) {
        routedDisconnects.clear();
        var router = MessageRouter.mutable();
        router.addRoute(NetworkServiceMessage.DisconnectNode.class, routedDisconnects::add);
        var topology = threeNodeTopology();
        Serializer serializer = Mockito.mock(Serializer.class);
        Deserializer deserializer = Mockito.mock(Deserializer.class);
        ctxRef = new AtomicReference<>();
        harness = FsmTestHarness.<SwimHealthState, SwimHealthEvents>harness(
                "swim-health-test",
                fsm -> buildInitialState(fsm, router, topology, serializer, deserializer, isLeader));
    }

    private SwimHealthState buildInitialState(Fsm<SwimHealthState, SwimHealthEvents> fsm,
                                              MessageRouter router,
                                              TopologyConfig topology,
                                              Serializer serializer,
                                              Deserializer deserializer,
                                              boolean isLeader) {
        var ctx = new SwimHealthContext(fsm,
                                        router,
                                        topology,
                                        serializer,
                                        deserializer,
                                        _ -> {}, // HealthSignalSink no-op
                                        () -> Epoch.ZERO,
                                        () -> isLeader,
                                        PeerObservationStore.peerObservationStore(),
                                        SwimConfig.DEFAULT);
        ctxRef.set(ctx);
        return ctx.stopped();
    }

    private static TopologyConfig threeNodeTopology() {
        var nodeSelf = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("127.0.0.1", 9001).unwrap());
        var nodeA = NodeInfo.nodeInfo(PEER_A, NodeAddress.nodeAddress("127.0.0.2", 9001).unwrap());
        var nodeB = NodeInfo.nodeInfo(PEER_B, NodeAddress.nodeAddress("127.0.0.3", 9001).unwrap());
        return new TopologyConfig(SELF, 3, timeSpan(1).seconds(), timeSpan(10).seconds(),
                                  List.of(nodeSelf, nodeA, nodeB));
    }

    @Nested
    class HappyPath {
        @Test
        void lifecycle_stoppedStartingRunningLocalDisconnectRunningStopped() {
            buildHarness(false); // follower
            var ctx = ctxRef.get();
            assertThat(harness.state()).isInstanceOf(SwimHealthState.Stopped.class);

            harness.dispatch(new SwimHealthEvents.StartRequested());
            assertThat(harness.state()).isInstanceOf(SwimHealthState.Starting.class);

            var swim = swimWithSeeds(PEER_A, PEER_B);
            harness.dispatch(new SwimHealthEvents.ProtocolReady(swim, new StubTransport(),
                                                                 GossipEncryptor.none()));
            assertThat(harness.state()).isInstanceOf(SwimHealthState.Running.class);

            // Trigger local disconnect: 2 members; need > 2/2 == 1 → 2 faulty events.
            harness.dispatch(new SwimHealthEvents.PeerFaulty(faulty(PEER_A)));
            harness.dispatch(new SwimHealthEvents.PeerFaulty(faulty(PEER_B)));
            assertThat(harness.state()).isInstanceOf(SwimHealthState.LocalDisconnect.class);

            // Recovery: PeerConnected
            harness.dispatch(new SwimHealthEvents.PeerConnected(PEER_A, Option.none()));
            assertThat(harness.state()).isInstanceOf(SwimHealthState.Running.class);

            harness.dispatch(new SwimHealthEvents.StopRequested());
            assertThat(harness.state()).isInstanceOf(SwimHealthState.Stopped.class);
            assertThat(ctx).isNotNull();
        }
    }

    @Nested
    class CasContention {
        @Test
        void concurrentQuorumLoss_fromRunning_exactlyOneLocalDisconnectTransition() throws InterruptedException {
            buildHarness(true); // leader, so faulty peers also route DisconnectNode
            // Seed 8 members so threshold > 8/2 == 4 — the 5th concurrent faulty crosses it.
            var seeds = new NodeId[]{new NodeId("peer-0"), new NodeId("peer-1"), new NodeId("peer-2"),
                                      new NodeId("peer-3"), new NodeId("peer-4"), new NodeId("peer-5"),
                                      new NodeId("peer-6"), new NodeId("peer-7")};
            var swim = swimWithSeeds(seeds);
            harness.dispatch(new SwimHealthEvents.StartRequested());
            harness.dispatch(new SwimHealthEvents.ProtocolReady(swim, new StubTransport(),
                                                                 GossipEncryptor.none()));
            assertThat(harness.state()).isInstanceOf(SwimHealthState.Running.class);

            // 8 threads each faulting a unique peer; threshold is > 8/2 == 4, so the 5th
            // concurrent faulty should cross the line. Exactly ONE Running→LocalDisconnect
            // transition must win.
            var events = List.<SwimHealthEvents>of(
                    new SwimHealthEvents.PeerFaulty(faulty(new NodeId("peer-0"))),
                    new SwimHealthEvents.PeerFaulty(faulty(new NodeId("peer-1"))),
                    new SwimHealthEvents.PeerFaulty(faulty(new NodeId("peer-2"))),
                    new SwimHealthEvents.PeerFaulty(faulty(new NodeId("peer-3"))),
                    new SwimHealthEvents.PeerFaulty(faulty(new NodeId("peer-4"))),
                    new SwimHealthEvents.PeerFaulty(faulty(new NodeId("peer-5"))),
                    new SwimHealthEvents.PeerFaulty(faulty(new NodeId("peer-6"))),
                    new SwimHealthEvents.PeerFaulty(faulty(new NodeId("peer-7"))));
            harness.dispatchConcurrently(events);

            // Final state MUST be LocalDisconnect (once threshold crossed, no event drives back).
            assertThat(harness.state()).isInstanceOf(SwimHealthState.LocalDisconnect.class);
            // Exactly ONE Running→LocalDisconnect transition — the winner.
            var toLocalDisconnect = harness.transitions().stream()
                                           .filter(t -> t.from() instanceof SwimHealthState.Running
                                                        && t.to() instanceof SwimHealthState.LocalDisconnect)
                                           .count();
            assertThat(toLocalDisconnect).isEqualTo(1L);
        }
    }

    @Nested
    class IgnoredEvents {
        @Test
        void protocolReady_inStopped_isIgnored() {
            buildHarness(true);
            harness.dispatch(new SwimHealthEvents.ProtocolReady(swimWithSeeds(),
                                                                 new StubTransport(),
                                                                 GossipEncryptor.none()));

            assertThat(harness.state()).isInstanceOf(SwimHealthState.Stopped.class);
            assertThat(harness.transitions()).isEmpty();
            assertThat(harness.ignored()).hasSize(1);
            assertThat(harness.ignored().getFirst().event())
                .isInstanceOf(SwimHealthEvents.ProtocolReady.class);
        }

        @Test
        void startRequested_inStarting_isIgnored() {
            buildHarness(true);
            harness.dispatch(new SwimHealthEvents.StartRequested());
            assertThat(harness.state()).isInstanceOf(SwimHealthState.Starting.class);

            harness.dispatch(new SwimHealthEvents.StartRequested());

            assertThat(harness.state()).isInstanceOf(SwimHealthState.Starting.class);
            // Two dispatches: first transitioned Stopped→Starting, second was ignored.
            assertThat(harness.transitions()).hasSize(1);
            assertThat(harness.ignored()).hasSize(1);
        }
    }

    @Nested
    class HandledObservability {
        @Test
        void peerJoined_inRunning_recordsHandledNotIgnored() {
            buildHarness(true);
            harness.dispatch(new SwimHealthEvents.StartRequested());
            harness.dispatch(new SwimHealthEvents.ProtocolReady(swimWithSeeds(),
                                                                 new StubTransport(),
                                                                 GossipEncryptor.none()));
            assertThat(harness.state()).isInstanceOf(SwimHealthState.Running.class);

            harness.dispatch(new SwimHealthEvents.PeerJoined(faulty(PEER_A)));

            // The arm performs a side effect (resetFaultyWindow + reportHint) without changing
            // state. It MUST be observed as `handled`, not as `ignored`, so dashboards count it.
            assertThat(harness.handled()).hasSize(1);
            assertThat(harness.handled().getFirst().event())
                .isInstanceOf(SwimHealthEvents.PeerJoined.class);
            assertThat(harness.ignored().stream()
                              .filter(i -> i.event() instanceof SwimHealthEvents.PeerJoined))
                .isEmpty();
        }

        @Test
        void peerSuspectAndReportHint_inRunning_recordedAsHandled() {
            buildHarness(true);
            harness.dispatch(new SwimHealthEvents.StartRequested());
            harness.dispatch(new SwimHealthEvents.ProtocolReady(swimWithSeeds(),
                                                                 new StubTransport(),
                                                                 GossipEncryptor.none()));

            harness.dispatch(new SwimHealthEvents.PeerSuspect(faulty(PEER_A)));
            harness.dispatch(new SwimHealthEvents.ReportHint(PEER_B,
                                                              org.pragmatica.aether.slice.generation.HealthHint.HEALTHY));

            // Both arms produce `handled` records, no `ignored`.
            assertThat(harness.handled()).hasSize(2);
            assertThat(harness.handled().get(0).event()).isInstanceOf(SwimHealthEvents.PeerSuspect.class);
            assertThat(harness.handled().get(1).event()).isInstanceOf(SwimHealthEvents.ReportHint.class);
        }
    }

    @Nested
    class LeaderRouting {
        @Test
        void follower_running_peerFaultyIsCurrentLeader_routesDisconnectLocally() throws InterruptedException {
            buildHarness(false); // follower
            harness.dispatch(new SwimHealthEvents.StartRequested());
            harness.dispatch(new SwimHealthEvents.ProtocolReady(swimWithSeeds(),
                                                                 new StubTransport(),
                                                                 GossipEncryptor.none()));
            harness.dispatch(new SwimHealthEvents.LeaderChanged(Option.some(PEER_A)));
            assertThat(harness.state()).isInstanceOf(SwimHealthState.Running.class);
            assertThat(((SwimHealthState.Running) harness.state()).currentLeader())
                .isEqualTo(Option.some(PEER_A));
            routedDisconnects.clear();

            harness.dispatch(new SwimHealthEvents.PeerFaulty(faulty(PEER_A)));
            // routeFaulty → routeDisconnect → routeAsync → Promise.async — give the executor
            // a brief window to publish the DisconnectNode message before asserting (matches
            // the Thread.sleep(100) pattern in CoreSwimReconnectTest).
            Thread.sleep(50);

            // Must route DisconnectNode locally (follower + faulty-is-current-leader path).
            assertThat(routedDisconnects).hasSize(1);
            assertThat(routedDisconnects.getFirst().nodeId()).isEqualTo(PEER_A);
        }

        @Test
        void follower_running_peerFaultyIsNotCurrentLeader_doesNotRouteDisconnect() {
            buildHarness(false); // follower
            harness.dispatch(new SwimHealthEvents.StartRequested());
            harness.dispatch(new SwimHealthEvents.ProtocolReady(swimWithSeeds(),
                                                                 new StubTransport(),
                                                                 GossipEncryptor.none()));
            harness.dispatch(new SwimHealthEvents.LeaderChanged(Option.some(PEER_B)));
            routedDisconnects.clear();

            harness.dispatch(new SwimHealthEvents.PeerFaulty(faulty(PEER_A)));

            // Follower, faulty peer is NOT the leader — buffered upstream, no DisconnectNode.
            assertThat(routedDisconnects).isEmpty();
        }

        @Test
        void leaderChanged_updatesRunningStateCurrentLeader() {
            buildHarness(false);
            harness.dispatch(new SwimHealthEvents.StartRequested());
            harness.dispatch(new SwimHealthEvents.ProtocolReady(swimWithSeeds(),
                                                                 new StubTransport(),
                                                                 GossipEncryptor.none()));
            assertThat(((SwimHealthState.Running) harness.state()).currentLeader())
                .isEqualTo(Option.<NodeId>none());

            harness.dispatch(new SwimHealthEvents.LeaderChanged(Option.some(PEER_A)));

            assertThat(harness.state()).isInstanceOf(SwimHealthState.Running.class);
            assertThat(((SwimHealthState.Running) harness.state()).currentLeader())
                .isEqualTo(Option.some(PEER_A));
        }
    }

    // --- Helpers ---

    private static SwimMember faulty(NodeId peer) {
        return SwimMember.swimMember(peer, MemberState.FAULTY, 0,
                                     new InetSocketAddress("127.0.0.99", 9001));
    }

    /// Build a real SWIM protocol seeded with the listed peers. The protocol is NOT started, so
    /// no tick thread is scheduled; membership is populated purely via `addSeedMember`.
    private static SwimProtocol swimWithSeeds(NodeId... peers) {
        var protocol = SwimProtocol.swimProtocol(SwimConfig.DEFAULT, new StubTransport(),
                                                  noopListener(), SELF,
                                                  new InetSocketAddress("127.0.0.1", 9101)).unwrap();
        for (var peer : peers) {
            protocol.addSeedMember(peer, new InetSocketAddress("127.0.0.99", 9101));
        }
        return protocol;
    }

    private static org.pragmatica.swim.SwimMembershipListener noopListener() {
        return new org.pragmatica.swim.SwimMembershipListener() {
            @Override public void onMemberJoined(SwimMember member) {}
            @Override public void onMemberSuspect(SwimMember member) {}
            @Override public void onMemberFaulty(SwimMember member) {}
            @Override public void onMemberLeft(NodeId nodeId) {}
        };
    }

    /// Minimal SwimTransport stub — all operations return successful Promises; no I/O.
    private static final class StubTransport implements SwimTransport {
        @Override
        public Promise<Unit> send(InetSocketAddress target, SwimMessage message) {
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
