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
import org.pragmatica.swim.SwimHealth;
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
/// Covers:
/// 1. Happy path through the lifecycle states.
/// 2. Events ignored in terminal / inapplicable states.
/// 3. Leader-routing: follower sees faulty-peer-is-current-leader and routes DisconnectNode.
class SwimHealthFsmTest {

    private static final NodeId SELF = new NodeId("node-1");
    private static final NodeId PEER_A = new NodeId("node-2");
    private static final NodeId PEER_B = new NodeId("node-3");

    private final List<NetworkServiceMessage.DisconnectNode> routedDisconnects = new ArrayList<>();
    private final List<org.pragmatica.aether.slice.generation.HealthSignal.SwimHint> emittedHints = new ArrayList<>();
    private FsmTestHarness<SwimHealthState, SwimHealthEvents> harness;
    private AtomicReference<SwimHealthContext> ctxRef;

    private void buildHarness(boolean isLeader) {
        routedDisconnects.clear();
        emittedHints.clear();
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
                                        recordingSink(), // HealthSignalSink — records emitted hints
                                        () -> Epoch.ZERO,
                                        () -> isLeader,
                                        PeerObservationStore.peerObservationStore(),
                                        SwimConfig.DEFAULT);
        ctxRef.set(ctx);
        return ctx.stopped();
    }

    private org.pragmatica.aether.slice.generation.HealthSignalSink recordingSink() {
        return signal -> {
            if (signal instanceof org.pragmatica.aether.slice.generation.HealthSignal.SwimHint hint) {
                emittedHints.add(hint);
            }
        };
    }

    private java.util.List<org.pragmatica.aether.slice.generation.HealthHint> hintsFor(NodeId peer) {
        return emittedHints.stream()
                           .filter(h -> h.nodeId().equals(peer))
                           .map(org.pragmatica.aether.slice.generation.HealthSignal.SwimHint::state)
                           .toList();
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
        void lifecycle_stoppedStartingRunningStopped() {
            buildHarness(false); // follower
            var ctx = ctxRef.get();
            assertThat(harness.state()).isInstanceOf(SwimHealthState.Stopped.class);

            harness.dispatch(new SwimHealthEvents.StartRequested());
            assertThat(harness.state()).isInstanceOf(SwimHealthState.Starting.class);

            var swim = swimWithSeeds(PEER_A, PEER_B);
            harness.dispatch(new SwimHealthEvents.ProtocolReady(swim, new StubTransport(),
                                                                 GossipEncryptor.none()));
            assertThat(harness.state()).isInstanceOf(SwimHealthState.Running.class);

            // FAULTY peers route a health hint but no longer change FSM state — Running stays Running.
            harness.dispatch(new SwimHealthEvents.PeerFaulty(faulty(PEER_A)));
            harness.dispatch(new SwimHealthEvents.PeerFaulty(faulty(PEER_B)));
            assertThat(harness.state()).isInstanceOf(SwimHealthState.Running.class);

            // PeerConnected keeps Running and re-asserts HEALTHY.
            harness.dispatch(new SwimHealthEvents.PeerConnected(PEER_A, Option.none()));
            assertThat(harness.state()).isInstanceOf(SwimHealthState.Running.class);

            harness.dispatch(new SwimHealthEvents.StopRequested());
            assertThat(harness.state()).isInstanceOf(SwimHealthState.Stopped.class);
            assertThat(ctx).isNotNull();
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

            // The arm performs a side effect (a log line, no HEALTHY hint) without changing
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
        void follower_running_peerFaultyIsCurrentLeader_doesNotRouteDisconnect() throws InterruptedException {
            // RC1-9 audit Step 3: the follower-for-dead-leader `routeDisconnect` branch
            // is gone. Eviction now flows post-consensus via MembershipDecision.NodeRemoved
            // after the leader (re-elected) writes DECOMMISSIONED. The FAULTY observation
            // still drives the leader-side aggregation path (emitLeaderHint
            // + bufferHealthObservation) so re-election can complete.
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
            Thread.sleep(50);

            assertThat(routedDisconnects).isEmpty();
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

    /// Resurrection guard (SWIM dead-node-revival fix). A bare gossip join/announce carries
    /// NO reachability proof — it must NOT assert HEALTHY. HEALTHY arrives only via a real
    /// `PeerConnected` (QUIC connection) or a probe-ack.
    @Nested
    class ResurrectionGuard {
        @Test
        void peerJoined_inRunning_emitsNoHealthyHint() {
            buildHarness(false);
            harness.dispatch(new SwimHealthEvents.StartRequested());
            harness.dispatch(new SwimHealthEvents.ProtocolReady(swimWithSeeds(),
                                                                 new StubTransport(),
                                                                 GossipEncryptor.none()));
            assertThat(harness.state()).isInstanceOf(SwimHealthState.Running.class);

            harness.dispatch(new SwimHealthEvents.PeerJoined(faulty(PEER_A)));

            // Bare join must NOT produce a HEALTHY hint for the unreachable peer.
            assertThat(hintsFor(PEER_A))
                .as("bare gossip PeerJoined must not assert HEALTHY")
                .doesNotContain(org.pragmatica.aether.slice.generation.HealthHint.HEALTHY);
        }

        @Test
        void peerJoined_inStoppedOrStarting_emitsNoHealthyHint() {
            buildHarness(false);
            // Stopped: bare join, no hint.
            harness.dispatch(new SwimHealthEvents.PeerJoined(faulty(PEER_A)));
            // Starting: bare join, no hint.
            harness.dispatch(new SwimHealthEvents.StartRequested());
            harness.dispatch(new SwimHealthEvents.PeerJoined(faulty(PEER_B)));

            assertThat(hintsFor(PEER_A))
                .as("bare join in Stopped must not assert HEALTHY")
                .doesNotContain(org.pragmatica.aether.slice.generation.HealthHint.HEALTHY);
            assertThat(hintsFor(PEER_B))
                .as("bare join in Starting must not assert HEALTHY")
                .doesNotContain(org.pragmatica.aether.slice.generation.HealthHint.HEALTHY);
        }

        @Test
        void peerConnected_inRunning_promotesMembershipToHealthy() {
            // Formation safety, two-plane liveness (commit e69f57a4b): a REAL connection
            // (PeerConnected) for a KNOWN member promotes it ALIVE via
            // SwimProtocol.markAliveFromTransport (tombstone-gated). It ALSO emits a HEALTHY hint
            // to the detector sink, clearing any stale SUSPECTED/FAULTY entry the leader's
            // SwimHintsRegistry still holds for the recovered peer (symmetry with Stopped/Starting).
            buildHarness(false);
            harness.dispatch(new SwimHealthEvents.StartRequested());
            var swim = swimWithSeeds(PEER_A);
            harness.dispatch(new SwimHealthEvents.ProtocolReady(swim,
                                                                 new StubTransport(),
                                                                 GossipEncryptor.none()));
            assertThat(harness.state()).isInstanceOf(SwimHealthState.Running.class);

            harness.dispatch(new SwimHealthEvents.PeerConnected(PEER_A, Option.none()));

            assertThat(swim.healthOf(PEER_A))
                .as("PeerConnected (real reachability) promotes a known member to HEALTHY in SWIM membership — formation depends on it")
                .isEqualTo(SwimHealth.HEALTHY);
            assertThat(hintsFor(PEER_A))
                .as("PeerConnected for a known member also emits a HEALTHY hint to clear stale suspicion")
                .contains(org.pragmatica.aether.slice.generation.HealthHint.HEALTHY);
        }

        @Test
        void runningPeerConnected_knownSuspectMember_emitsHealthyHintClearingSuspicion() {
            // 02-chaos readiness-latency root: a node briefly SWIM-SUSPECTED during kill
            // turbulence gets a SUSPECTED hint in the leader's SwimHintsRegistry. When the node
            // recovers, a real QUIC PeerConnected for that KNOWN member must emit a HEALTHY hint
            // that clears the stale SUSPECTED entry — otherwise the generation projector stays
            // DEGRADED until the hint's TTL expires.
            buildHarness(true);
            harness.dispatch(new SwimHealthEvents.StartRequested());
            var swim = swimWithSeeds(PEER_A);
            harness.dispatch(new SwimHealthEvents.ProtocolReady(swim,
                                                                 new StubTransport(),
                                                                 GossipEncryptor.none()));
            assertThat(harness.state()).isInstanceOf(SwimHealthState.Running.class);

            // The peer is briefly suspected — a SUSPECTED hint is stamped.
            harness.dispatch(new SwimHealthEvents.PeerSuspect(faulty(PEER_A)));
            assertThat(hintsFor(PEER_A))
                .as("PeerSuspect stamps a SUSPECTED hint")
                .contains(org.pragmatica.aether.slice.generation.HealthHint.SUSPECTED);

            // The peer recovers — a real QUIC connection lands.
            harness.dispatch(new SwimHealthEvents.PeerConnected(PEER_A, Option.none()));

            assertThat(hintsFor(PEER_A).getLast())
                .as("PeerConnected for the recovered known member emits HEALTHY, clearing the stale SUSPECTED")
                .isEqualTo(org.pragmatica.aether.slice.generation.HealthHint.HEALTHY);
        }

        @Test
        void bareJoinAfterFaulty_doesNotEraseFaultyEvidence_andDoesNotHeal() {
            // A FAULTY peer rejoining by gossip must NOT be auto-healed: no HEALTHY hint, and
            // the prior FAULTY signal is preserved (the last emitted hint for the peer is FAULTY).
            buildHarness(false);
            harness.dispatch(new SwimHealthEvents.StartRequested());
            // Seed 2 peers so a single PeerFaulty does NOT trip the local-disconnect majority
            // guard (threshold is > totalMembers/2): it routes a real FAULTY hint instead.
            harness.dispatch(new SwimHealthEvents.ProtocolReady(swimWithSeeds(PEER_A, PEER_B),
                                                                 new StubTransport(),
                                                                 GossipEncryptor.none()));
            assertThat(harness.state()).isInstanceOf(SwimHealthState.Running.class);

            // Establish FAULTY evidence for PEER_A.
            harness.dispatch(new SwimHealthEvents.PeerFaulty(faulty(PEER_A)));
            assertThat(harness.state())
                .as("a single faulty peer must not trip the local-disconnect guard with 2 seeds")
                .isInstanceOf(SwimHealthState.Running.class);
            assertThat(hintsFor(PEER_A))
                .as("PeerFaulty must emit a FAULTY hint")
                .contains(org.pragmatica.aether.slice.generation.HealthHint.FAULTY);

            // Stale gossip re-announces PEER_A as joined — must NOT heal it.
            harness.dispatch(new SwimHealthEvents.PeerJoined(faulty(PEER_A)));

            assertThat(hintsFor(PEER_A))
                .as("bare join after FAULTY must not emit HEALTHY (no resurrection)")
                .doesNotContain(org.pragmatica.aether.slice.generation.HealthHint.HEALTHY);
            assertThat(hintsFor(PEER_A).getLast())
                .as("FAULTY evidence preserved: last emitted hint stays FAULTY")
                .isEqualTo(org.pragmatica.aether.slice.generation.HealthHint.FAULTY);
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
