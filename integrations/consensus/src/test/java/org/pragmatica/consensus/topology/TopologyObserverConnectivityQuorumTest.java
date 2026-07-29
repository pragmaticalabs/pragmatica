/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package org.pragmatica.consensus.topology;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkMessage;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #557 — boot-time quorum must be declared from REACHABILITY, never from DISCOVERY.
///
/// Before the fix, `addNode` inserted every freshly SWIM-discovered peer as
/// `NodeState.healthy(...)` before any connection existed, and `nodeStatesById` had no
/// health-mutating write path at all (`putIfAbsent` / `remove` only; `NodeState.suspected(...)`
/// has no caller). The `health == HEALTHY` filter in `legacyHealthyActivePeerCount` was
/// therefore vacuous and the pre-snapshot quorum count degenerated to
/// `nodeStatesById.size() - 1` — a pure discovery count. Cold start routed
/// `ClusterStateNotification.ACTIVE` while zero QUIC lanes were up, so
/// `RabiaEngine.doClusterConnected` broadcast its one-shot `SyncRequest` into an empty network
/// and the cluster never formed (~1 boot in 12 in CI).
///
/// The fix intersects that count with the most recent
/// `NetworkServiceMessage.ConnectedNodesList` — the transport's post-handshake
/// `phase() == CONNECTED` set, produced by `QuicClusterNetwork.listNodes`, already delivered to
/// `reconcile` on the same tick that evaluates quorum.
///
/// Every test here runs with NO `GenerationSnapshotSource` (view absent → the legacy branch of
/// `haveQuorum`) and NO consensus commit, which is precisely the cold-start window the defect
/// lives in and the catch-22 the fallback exists to break.
class TopologyObserverConnectivityQuorumTest {
    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId PEER_A = nodeId("node-a").unwrap();
    private static final NodeId PEER_B = nodeId("node-b").unwrap();
    private static final NodeId PEER_C = nodeId("node-c").unwrap();
    private static final NodeId PEER_D = nodeId("node-d").unwrap();

    private static final NodeInfo INFO_SELF = info(SELF, 6000);
    private static final NodeInfo INFO_A = info(PEER_A, 6001);
    private static final NodeInfo INFO_B = info(PEER_B, 6002);
    private static final NodeInfo INFO_C = info(PEER_C, 6003);
    private static final NodeInfo INFO_D = info(PEER_D, 6004);

    private static NodeInfo info(NodeId id, int port) {
        return NodeInfo.nodeInfo(id, NodeAddress.nodeAddress("localhost", port).unwrap());
    }

    /// 3 configured cores → `quorumSize() == 2` → self plus ONE observed-connected peer.
    /// The reconcile interval is deliberately longer than any test, so no timer can fire and
    /// every quorum evaluation is driven by an explicit, synchronous structural trigger.
    private static TopologyConfig quiescentConfig() {
        return new TopologyConfig(SELF,
                                  3,
                                  timeSpan(60).seconds(),
                                  timeSpan(1).seconds(),
                                  List.of(INFO_SELF, INFO_A, INFO_B));
    }

    /// Same 3-core denominator, but with a reconcile interval short enough that the periodic
    /// `initReconcile` tick — the only trigger that re-reads the transport observation — fires
    /// several times inside a test.
    private static TopologyConfig tickingConfig() {
        return new TopologyConfig(SELF,
                                  3,
                                  timeSpan(50).millis(),
                                  timeSpan(1).seconds(),
                                  List.of(INFO_SELF, INFO_A, INFO_B));
    }

    /// 5 configured cores → `quorumSize() == 3` → self plus TWO observed-connected peers, so
    /// "some peers connected but not enough" is distinguishable from "none connected".
    private static TopologyConfig tickingFiveNodeConfig() {
        return new TopologyConfig(SELF,
                                  5,
                                  timeSpan(50).millis(),
                                  timeSpan(1).seconds(),
                                  List.of(INFO_SELF, INFO_A, INFO_B, INFO_C, INFO_D));
    }

    /// Stand-in for `QuicClusterNetwork.listNodes`: answers each `ListConnectedNodes` with the
    /// currently-connected set, synchronously on the caller's thread, exactly as the QUIC
    /// transport does (`MessageRouter.SimpleMutableRouter.dispatchOne` invokes handlers inline).
    /// `connect(...)` is the test's proxy for QUIC lanes reaching `phase() == CONNECTED`.
    private static final class TransportStub {
        private final AtomicReference<List<NodeId>> connected = new AtomicReference<>(List.of());
        private final AtomicReference<Option<TopologyObserver>> observer = new AtomicReference<>(Option.none());

        void attach(TopologyObserver value) {
            observer.set(Option.some(value));
        }

        void connect(NodeId... peers) {
            connected.set(List.of(peers));
        }

        void listNodes(NetworkServiceMessage.ListConnectedNodes ignored) {
            observer.get()
                    .onPresent(this::deliverConnectedNodesList);
        }

        private void deliverConnectedNodesList(TopologyObserver target) {
            target.reconcile(new NetworkServiceMessage.ConnectedNodesList(connected.get()));
        }
    }

    /// Shared bus carrying the quorum edge (the factory overloads used here default
    /// `quorumPresenceRouter` to `router`), with `ConnectNode` black-holed because no real
    /// transport is wired.
    private static MessageRouter.MutableRouter routerCapturing(List<ClusterStateNotification> sink,
                                                                TransportStub transport) {
        var router = MessageRouter.mutable();

        router.addRoute(NetworkServiceMessage.ListConnectedNodes.class, transport::listNodes);
        router.addRoute(NetworkServiceMessage.ConnectNode.class, _ -> {});
        router.addRoute(NetworkServiceMessage.DisconnectNode.class, _ -> {});
        router.addRoute(ClusterStateNotification.class, sink::add);

        return router;
    }

    private static List<ClusterStateNotification.State> statesOf(List<ClusterStateNotification> notifications) {
        return notifications.stream()
                            .map(ClusterStateNotification::state)
                            .toList();
    }

    /// Bounded poll (no Awaitility in this module) — mirrors the helper in `TopologyObserverTest`.
    private static void awaitTrue(BooleanSupplier condition, String description) {
        var deadline = System.nanoTime() + timeSpan(3).seconds().nanos();

        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            sleepBriefly();
        }

        assertThat(condition.getAsBoolean()).as(description).isTrue();
    }

    /// Let a fixed number of reconcile intervals elapse so "still not quorate after N ticks"
    /// is a real observation rather than a race the assertion happened to win.
    private static void awaitTicks(int ticks) {
        var deadline = System.nanoTime() + timeSpan(50L * ticks).millis().nanos();

        while (System.nanoTime() < deadline) {
            sleepBriefly();
        }
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Nested
    class DiscoveryAloneIsNotQuorum {
        /// #557 core regression. SWIM discovers both configured peers; the transport has never
        /// reported either as connected. `addNode` evaluates quorum synchronously on each
        /// discovery, and both evaluations must be false. Pre-fix, the first `addNode` alone
        /// pushed the discovery count to 1 (+1 self = 2 >= quorum 2) and routed ACTIVE.
        @Test
        void addNode_discoveredButNeverConnectedPeers_doesNotRouteActive() {
            var notifications = new CopyOnWriteArrayList<ClusterStateNotification>();
            var transport = new TransportStub();
            var observer = TopologyObserver.topologyObserver(quiescentConfig(),
                                                             routerCapturing(notifications, transport))
                                           .unwrap();

            transport.attach(observer);
            observer.start().await();

            observer.handleDiscoveredNodes(new NetworkMessage.DiscoveredNodes(SELF, List.of(INFO_A, INFO_B)));

            assertThat(observer.topology())
                .as("both peers must be in the discovery-derived dial set")
                .contains(SELF, PEER_A, PEER_B);
            assertThat(notifications)
                .as("discovery alone must never establish quorum — no ConnectedNodesList has named these peers")
                .isEmpty();
            assertThat(observer.inQuorum().getAsBoolean())
                .as("the quorum bit must stay false while no peer is observed connected")
                .isFalse();
        }

        /// A peer discovered but never connected must never be counted, however many reconcile
        /// ticks pass — the count is a level read of the observation, not a function of time.
        /// Denominator is 5 (quorum 3), so one connected peer out of four discovered is
        /// deliberately insufficient; adding the second connected peer is what flips it.
        @Test
        void initReconcile_peersDiscoveredButOnlyOneConnected_neverReachesQuorumAcrossManyTicks() {
            var notifications = new CopyOnWriteArrayList<ClusterStateNotification>();
            var transport = new TransportStub();
            var observer = TopologyObserver.topologyObserver(tickingFiveNodeConfig(),
                                                             routerCapturing(notifications, transport))
                                           .unwrap();

            transport.attach(observer);
            observer.start().await();
            observer.handleDiscoveredNodes(new NetworkMessage.DiscoveredNodes(SELF,
                                                                              List.of(INFO_A, INFO_B, INFO_C, INFO_D)));
            transport.connect(PEER_A);

            awaitTicks(20);

            assertThat(notifications)
                .as("4 discovered peers but only 1 observed connected (1+1=2 < quorum 3) must not establish quorum, "
                    + "no matter how many reconcile ticks elapse")
                .isEmpty();

            // Only the arrival of a SECOND observed connection — not further ticks — may flip it.
            transport.connect(PEER_A, PEER_B);

            awaitTrue(() -> notifications.size() == 1,
                      "the second observed connection (2+1=3 >= quorum 3) establishes quorum");
            assertThat(statesOf(notifications)).containsExactly(ClusterStateNotification.State.ACTIVE);

            observer.stop().await();
        }
    }

    @Nested
    class ObservedConnectivityEstablishesQuorum {
        /// Deterministic, timer-free counterpart to the discovery test: the transport has
        /// already reported PEER_A connected when discovery lands, so `addNode`'s synchronous
        /// `evaluateQuorumState` sees an intersection of size 1 (+1 self = 2 >= quorum 2) and
        /// routes ACTIVE exactly once.
        @Test
        void addNode_peerAlreadyReportedConnected_routesActiveExactlyOnce() {
            var notifications = new CopyOnWriteArrayList<ClusterStateNotification>();
            var transport = new TransportStub();
            var observer = TopologyObserver.topologyObserver(quiescentConfig(),
                                                             routerCapturing(notifications, transport))
                                           .unwrap();

            transport.attach(observer);
            observer.start().await();
            assertThat(notifications)
                .as("start() with an empty observation must not establish quorum")
                .isEmpty();

            observer.reconcile(new NetworkServiceMessage.ConnectedNodesList(List.of(PEER_A)));
            observer.handleDiscoveredNodes(new NetworkMessage.DiscoveredNodes(SELF, List.of(INFO_A)));

            assertThat(statesOf(notifications))
                .as("an observed-connected peer establishes quorum, and the CAS edge fires exactly once")
                .containsExactly(ClusterStateNotification.State.ACTIVE);
            assertThat(observer.inQuorum().getAsBoolean()).isTrue();

            // A repeated observation must not re-emit: the edge is CAS-latched.
            observer.reconcile(new NetworkServiceMessage.ConnectedNodesList(List.of(PEER_A)));
            observer.handleDiscoveredNodes(new NetworkMessage.DiscoveredNodes(SELF, List.of(INFO_A)));

            assertThat(notifications)
                .as("re-observing the same connectivity must not re-route ACTIVE")
                .hasSize(1);
        }

        /// The real production sequence, end to end: discovery lands first (no quorum), the QUIC
        /// lane comes up afterwards, and the periodic `initReconcile` tick is what re-reads the
        /// transport observation and flips the edge. `reconcile` deliberately does not call the
        /// evaluator (§3.1: no new path into `evaluateQuorumState`), so this tick is the only
        /// mechanism that can establish quorum here — the test fails if it is removed.
        @Test
        void initReconcile_connectionEstablishedAfterDiscovery_routesActiveOnALaterTick() {
            var notifications = new CopyOnWriteArrayList<ClusterStateNotification>();
            var transport = new TransportStub();
            var observer = TopologyObserver.topologyObserver(tickingConfig(),
                                                             routerCapturing(notifications, transport))
                                           .unwrap();

            transport.attach(observer);
            observer.start().await();
            observer.handleDiscoveredNodes(new NetworkMessage.DiscoveredNodes(SELF, List.of(INFO_A, INFO_B)));

            awaitTicks(6);
            assertThat(notifications)
                .as("discovered-but-unreachable peers must not establish quorum on any tick")
                .isEmpty();

            transport.connect(PEER_A);

            awaitTrue(() -> notifications.size() == 1,
                      "the tick following the observed connection establishes quorum");
            assertThat(statesOf(notifications)).containsExactly(ClusterStateNotification.State.ACTIVE);

            // Many further ticks with unchanged connectivity must not churn the edge.
            awaitTicks(20);
            assertThat(notifications)
                .as("the CAS-latched edge must fire exactly once while connectivity is unchanged")
                .hasSize(1);

            observer.stop().await();
        }

        /// Pins the statement ORDER inside `initReconcile`: the transport observation must be
        /// refreshed (`route(ListConnectedNodes)`) BEFORE quorum is evaluated, not after.
        /// `MessageRouter` dispatch is synchronous, so the refresh completes within the same
        /// tick and the evaluation that follows reads a CURRENT observation. With the two
        /// statements in the opposite order every boot-time quorum decision is taken on a
        /// one-interval-stale set, which doubles the worst-case time to establish quorum and
        /// delays detecting its loss by the same amount.
        ///
        /// Deterministic and timer-free: the reconcile interval is 60s, so the ONLY
        /// `initReconcile` invocation is the one `start()` makes directly. The peer is
        /// discovered before `start()` (where `evaluateQuorumState` short-circuits on
        /// `started == false`) and the transport already has the lane up, so quorum can only be
        /// visible immediately after `start()` if the refresh precedes the evaluation.
        @Test
        void start_transportAlreadyConnected_refreshesObservationBeforeEvaluatingQuorum() {
            var notifications = new CopyOnWriteArrayList<ClusterStateNotification>();
            var transport = new TransportStub();
            var observer = TopologyObserver.topologyObserver(quiescentConfig(),
                                                             routerCapturing(notifications, transport))
                                           .unwrap();

            transport.attach(observer);
            transport.connect(PEER_A);
            observer.handleDiscoveredNodes(new NetworkMessage.DiscoveredNodes(SELF, List.of(INFO_A)));

            observer.start().await();

            assertThat(statesOf(notifications))
                .as("start()'s single initReconcile must refresh the observation, then evaluate — "
                    + "evaluating first would leave quorum undeclared until the next tick")
                .containsExactly(ClusterStateNotification.State.ACTIVE);
        }
    }

    @Nested
    class CatchTwentyTwoGuard {
        /// Regression test for the deadlock documented on `legacyHealthyActivePeerCount` /
        /// `haveQuorum`: the `MembershipView` snapshot is only published after Rabia commits,
        /// and Rabia only leaves `Stopped` on the ACTIVE edge this evaluator produces. The
        /// boot-time count must therefore remain satisfiable from TRANSPORT connectivity ALONE.
        ///
        /// This asserts the full negative context — no membership view is wired
        /// (`GenerationSnapshotSource.noop()`), the observer never leaves `BOOTING`, and reads
        /// are served from `LEGACY` — while quorum still establishes purely because the
        /// transport reported a connected peer. If the #557 intersection were ever tightened to
        /// consult the snapshot, `coreMemberIds`, or any committed value, this test deadlocks.
        @Test
        void haveQuorum_transportConnectivityOnly_establishesWithoutMembershipViewOrConsensusCommit() {
            var notifications = new CopyOnWriteArrayList<ClusterStateNotification>();
            var transport = new TransportStub();
            var observer = TopologyObserver.topologyObserver(tickingConfig(),
                                                             routerCapturing(notifications, transport),
                                                             GenerationSnapshotSource.noop())
                                           .unwrap();

            transport.attach(observer);
            observer.start().await();
            observer.handleDiscoveredNodes(new NetworkMessage.DiscoveredNodes(SELF, List.of(INFO_A, INFO_B)));

            // The only fact that ever becomes true: a QUIC lane reaches CONNECTED.
            transport.connect(PEER_A);

            awaitTrue(() -> notifications.size() == 1,
                      "quorum must establish from transport connectivity alone — no snapshot, no commit");
            assertThat(statesOf(notifications)).containsExactly(ClusterStateNotification.State.ACTIVE);

            assertThat(observer.effectiveMembership().source())
                .as("no membership view may have been consulted — reads are still LEGACY")
                .isEqualTo(EffectiveMembership.Source.LEGACY);
            assertThat(observer.topologyMode())
                .as("the observer must still be BOOTING — nothing was ever committed or projected")
                .isEqualTo(TopologyObserver.TopologyMode.BOOTING);
            assertThat(observer.inQuorum().getAsBoolean())
                .as("MembershipView.strict / MembershipDeltaProjector / ClusterPhaseView read this bit")
                .isTrue();

            observer.stop().await();
        }
    }

    @Nested
    class QuorumLoss {
        /// The observation is a LEVEL, not a latch: when the transport stops reporting the peer
        /// as connected, the count falls below threshold and the `compareAndSet(true, false)`
        /// arm must route PASSIVE. Without this, gating quorum on connectivity would be a
        /// one-way door and a partitioned node would keep claiming quorum.
        @Test
        void initReconcile_observedConnectionLost_routesPassive() {
            var notifications = new CopyOnWriteArrayList<ClusterStateNotification>();
            var transport = new TransportStub();
            var observer = TopologyObserver.topologyObserver(tickingConfig(),
                                                             routerCapturing(notifications, transport))
                                           .unwrap();

            transport.attach(observer);
            observer.start().await();
            observer.handleDiscoveredNodes(new NetworkMessage.DiscoveredNodes(SELF, List.of(INFO_A, INFO_B)));
            transport.connect(PEER_A, PEER_B);

            awaitTrue(() -> notifications.size() == 1, "quorum established from observed connectivity");
            assertThat(statesOf(notifications)).containsExactly(ClusterStateNotification.State.ACTIVE);

            // Both lanes drop: 0 observed peers + 1 self = 1 < quorum 2.
            transport.connect();

            awaitTrue(() -> notifications.size() == 2, "losing every observed connection must drop quorum");
            assertThat(statesOf(notifications))
                .containsExactly(ClusterStateNotification.State.ACTIVE, ClusterStateNotification.State.PASSIVE);
            assertThat(observer.inQuorum().getAsBoolean())
                .as("the quorum bit must follow the loss edge")
                .isFalse();

            // ...and recover on the next tick once connectivity returns.
            transport.connect(PEER_A);

            awaitTrue(() -> notifications.size() == 3, "restored connectivity must re-establish quorum");
            assertThat(statesOf(notifications).getLast()).isEqualTo(ClusterStateNotification.State.ACTIVE);

            observer.stop().await();
        }
    }
}
