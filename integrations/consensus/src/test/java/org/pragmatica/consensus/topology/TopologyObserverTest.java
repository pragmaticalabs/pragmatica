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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkMessage;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;

import java.util.List;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Verifies R5 semantics on `TopologyObserver`: the mutation API surface
/// (`registerPeer`, `unregisterPeer`, `markReady`, `markDeparted`,
/// `handleConnectionFailed`, `handleConnectionEstablished`) is **deleted**.
/// HealthReconciler is the sole writer of `NodeLifecycleKey`; the observer's
/// authoritative readers project `MembershipView` from KV via
/// `GenerationSnapshotSource`. Compile-time absence of the legacy methods is
/// the strongest possible enforcement of spec §4.4 (Layer 3 is read-only).
class TopologyObserverTest {
    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId PEER_A = nodeId("node-a").unwrap();
    private static final NodeId PEER_B = nodeId("node-b").unwrap();

    private static final NodeInfo INFO_SELF = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("localhost", 6000).unwrap());
    private static final NodeInfo INFO_A = NodeInfo.nodeInfo(PEER_A, NodeAddress.nodeAddress("localhost", 6001).unwrap());
    private static final NodeInfo INFO_B = NodeInfo.nodeInfo(PEER_B, NodeAddress.nodeAddress("localhost", 6002).unwrap());

    private static TopologyConfig baseConfig() {
        return new TopologyConfig(SELF,
                                  3,
                                  timeSpan(60).seconds(),
                                  timeSpan(1).seconds(),
                                  List.of(INFO_SELF, INFO_A, INFO_B));
    }

    /// RC1-9 audit Step 5: the legacy `nodeStatesById`-derived count is gone, so tests
    /// that exercise quorum-state edges must seed a synthetic snapshot reflecting the
    /// configured core set.
    private static GenerationSnapshotSource fullQuorumSnapshotSource() {
        record StubView(Set<NodeId> coreMemberIds, Set<NodeId> onDutyMemberIds,
                        int healthyOnDutyCount, int desiredCoreSize) implements MembershipView {}
        var view = new StubView(Set.of(SELF, PEER_A, PEER_B),
                                Set.of(SELF, PEER_A, PEER_B),
                                3, 3);
        return new GenerationSnapshotSource() {
            @Override public Option<MembershipView> currentMembershipView() { return Option.some(view); }
            @Override public long observedRabiaTerm() { return 0L; }
        };
    }

    private static MessageRouter.MutableRouter quietRouter() {
        var router = MessageRouter.mutable();
        router.addRoute(NetworkServiceMessage.ListConnectedNodes.class, _ -> {});
        return router;
    }

    /// v2-architecture §5.4: the QUIC dial set (`nodeStatesById`, exposed via `topology()`)
    /// is populated by SWIM discovery (`handleDiscoveredNodes`) + self ONLY. Static
    /// `config.coreNodes()` is NOT seeded into the dial set — it only feeds SWIM's seed/
    /// ANNOUNCE path (outside this observer) and the configured-core IDENTITY (`coreNodeIds`,
    /// quorum denominator), which remain config-derived.
    @Nested
    class SwimOnlyDialSet {
        @Test
        void construction_dialSetIsSelfOnly_notPrePopulatedFromConfig() {
            // No discovery yet: the dial set must contain ONLY self, never the static
            // configured peers PEER_A / PEER_B.
            var observer = TopologyObserver.topologyObserver(baseConfig(), quietRouter()).unwrap();

            assertThat(observer.topology())
                .as("dial set at construction must be self-only (no static config pre-population)")
                .containsExactly(SELF);
            assertThat(observer.get(PEER_A).isEmpty()).isTrue();
            assertThat(observer.get(PEER_B).isEmpty()).isTrue();
        }

        @Test
        void start_dialSetStillSelfOnly_noStaticReseed() {
            // `start()` triggers `initReconcile`, which no longer re-seeds from config.
            // The dial set stays self-only until SWIM discovery lands.
            var observer = TopologyObserver.topologyObserver(baseConfig(), quietRouter()).unwrap();
            observer.start().await();

            assertThat(observer.topology())
                .as("start() must not static-reseed the dial set")
                .containsExactly(SELF);
        }

        @Test
        void handleDiscoveredNodes_swimDiscoveredPeer_entersDialSetAndIsDialable() {
            // SWIM discovery is the sole writer of the dial set (besides self). A discovered
            // peer must enter `nodeStatesById` (→ topology()) and become dialable.
            var router = MessageRouter.mutable();
            router.addRoute(NetworkServiceMessage.ListConnectedNodes.class, _ -> {});
            var observer = TopologyObserver.topologyObserver(baseConfig(), router).unwrap();
            observer.start().await();

            observer.handleDiscoveredNodes(new NetworkMessage.DiscoveredNodes(SELF, List.of(INFO_A)));

            assertThat(observer.topology())
                .as("SWIM-discovered peer must enter the dial set")
                .contains(SELF, PEER_A);
            assertThat(observer.get(PEER_A).isPresent())
                .as("discovered peer must be dialable (NodeInfo resolvable)")
                .isTrue();
            assertThat(observer.topology())
                .as("a NOT-yet-discovered configured peer must remain absent from the dial set")
                .doesNotContain(PEER_B);
        }

        @Test
        void configuredCoreIdentity_quorumDenominator_remainsConfigDerived() {
            // The configured-core identity / quorum denominator must NOT depend on the
            // discovery-derived dial set. With only self discovered, clusterSize/quorumSize
            // still reflect `config` (3 configured → quorum 2), and `coreNodes()` (the
            // identity fallback) still reflects the configured core set.
            var observer = TopologyObserver.topologyObserver(baseConfig(), quietRouter()).unwrap();

            assertThat(observer.clusterSize())
                .as("quorum denominator (clusterSize) must stay config-derived")
                .isEqualTo(3);
            assertThat(observer.quorumSize())
                .as("quorum size must stay config-derived")
                .isEqualTo(2);
            assertThat(observer.coreNodes())
                .as("configured-core identity (coreNodeIds fallback) must reflect config.coreNodes()")
                .contains(SELF, PEER_A, PEER_B);
        }

        @Test
        void departedDiscoveredPeer_droppedFromSnapshot_isNotReintroducedByReconcile() {
            // A peer SWIM discovered then the snapshot dropped (departed) must not be
            // re-introduced into the dial set by the periodic reconcile (no static reseed).
            // Snapshot omits PEER_A → it is not a core member; the dial set was never static-
            // seeded, so PEER_A is absent and `start()`/reconcile keep it absent.
            record StubView(Set<NodeId> coreMemberIds, Set<NodeId> onDutyMemberIds,
                            int healthyOnDutyCount, int desiredCoreSize) implements MembershipView {}
            var view = new StubView(Set.of(SELF, PEER_B), Set.of(SELF, PEER_B), 2, 3);
            var snapshot = new GenerationSnapshotSource() {
                @Override public Option<MembershipView> currentMembershipView() { return Option.some(view); }
                @Override public long observedRabiaTerm() { return 0L; }
            };
            var observer = TopologyObserver.topologyObserver(baseConfig(), quietRouter(), snapshot).unwrap();
            observer.start().await();

            assertThat(observer.topology())
                .as("departed/un-discovered peer must not be static-reseeded into the dial set")
                .doesNotContain(PEER_A);
        }
    }

    /// `TopologyObserver` is the canonical publisher of `ClusterStateNotification`.
    /// QUIC/Netty transports do not publish quorum state. After R4 the observer
    /// is a pure projection: edge transitions are computed from `MembershipView`
    /// via the snapshot source plus the seeded `nodeStatesById` map (legacy fallback
    /// for tests / non-Aether `RabiaNode` usage where no snapshot source is wired).
    @Nested
    class QuorumStatePublishing {
        private static MessageRouter.MutableRouter routerCapturing(List<ClusterStateNotification> sink) {
            var router = MessageRouter.mutable();
            router.addRoute(NetworkServiceMessage.ListConnectedNodes.class, _ -> {});
            router.addRoute(ClusterStateNotification.class, sink::add);
            return router;
        }

        @Test
        void start_aboveQuorumViaConfig_routesEstablishedExactlyOnce() {
            // RC1-9 audit Step 5: snapshot is the SOLE source of healthy-count truth.
            // Tests must seed a synthetic snapshot reflecting the configured core set;
            // start() then publishes ESTABLISHED based on snapshot.healthyOnDutyCount.
            var notifications = new CopyOnWriteArrayList<ClusterStateNotification>();
            var router = routerCapturing(notifications);

            var observer = TopologyObserver.topologyObserver(baseConfig(), router, fullQuorumSnapshotSource()).unwrap();
            observer.start().await();

            assertThat(notifications)
                .as("start() with full-quorum config publishes established once")
                .hasSize(1);
            assertThat(notifications.getFirst().state())
                .isEqualTo(ClusterStateNotification.State.ACTIVE);
        }

        @Test
        void start_belowQuorum_doesNotRouteEstablished() {
            // Self-only legacy config — peers=0, +1=1 < quorum=2 → no edge fires.
            var notifications = new CopyOnWriteArrayList<ClusterStateNotification>();
            var router = routerCapturing(notifications);
            var config = new TopologyConfig(SELF,
                                            3,
                                            timeSpan(60).seconds(),
                                            timeSpan(1).seconds(),
                                            List.of(INFO_SELF));

            var observer = TopologyObserver.topologyObserver(config, router).unwrap();
            observer.start().await();

            assertThat(notifications)
                .as("start() below quorum must not publish established")
                .isEmpty();
        }

        @Test
        void constructionWithFullCoreNodes_doesNotRouteUntilStart() {
            // RC1-9 audit Step 5: seed the snapshot before construction so the ESTABLISHED
            // edge can be computed at start(). Constructor still does NOT publish; only
            // start() may.
            var notifications = new CopyOnWriteArrayList<ClusterStateNotification>();
            var router = routerCapturing(notifications);

            var observer = TopologyObserver.topologyObserver(baseConfig(), router, fullQuorumSnapshotSource()).unwrap();

            assertThat(notifications)
                .as("construction-time evaluateQuorumState must be deferred until start()")
                .isEmpty();

            observer.start().await();

            assertThat(notifications)
                .as("start() publishes the initial edge after the router is fully wired")
                .hasSize(1);
            assertThat(notifications.getFirst().state())
                .isEqualTo(ClusterStateNotification.State.ACTIVE);
        }

        @Test
        void construction_withPartiallyWiredDelegateRouter_doesNotNpe() {
            // Regression for HEAD 3c66e9e65 NPE: production wiring uses a
            // `MessageRouter.DelegateRouter` whose `delegate` field is null at
            // construction time and is populated by node bootstrap before `start()`.
            // RC1-9 audit Step 5: snapshot must be seeded for ESTABLISHED edge to fire.
            var delegate = MessageRouter.DelegateRouter.delegate();
            var notifications = new CopyOnWriteArrayList<ClusterStateNotification>();

            var observer = TopologyObserver.topologyObserver(baseConfig(), delegate, fullQuorumSnapshotSource()).unwrap();
            assertThat(notifications)
                .as("construction with partially-wired router must not NPE and must not publish")
                .isEmpty();

            var realRouter = routerCapturing(notifications);
            delegate.replaceDelegate(realRouter);

            observer.start().await();

            assertThat(notifications)
                .as("start() with fully-wired router publishes initial established edge")
                .hasSize(1);
            assertThat(notifications.getFirst().state())
                .isEqualTo(ClusterStateNotification.State.ACTIVE);
        }

        /// Quorum-presence rewiring: a router carrying ONLY the dedicated quorum-presence
        /// channel (RabiaEngine stand-in). Records `ClusterStateNotification`s but, unlike
        /// the shared bus, intentionally does NOT route `ListConnectedNodes`.
        private static MessageRouter.MutableRouter quorumPresenceCapturing(List<ClusterStateNotification> sink) {
            var router = MessageRouter.mutable();
            router.addRoute(ClusterStateNotification.class, sink::add);
            return router;
        }

        /// Stateful snapshot for driving quorum gain then loss without inventing new
        /// membership plumbing (mirrors `MembershipDecisionEmission.StatefulSnapshotSource`).
        private static final class MutableSnapshotSource implements GenerationSnapshotSource {
            private final AtomicReference<Option<MembershipView>> viewRef = new AtomicReference<>(Option.none());

            void set(MembershipView view) {
                viewRef.set(Option.some(view));
            }

            @Override public Option<MembershipView> currentMembershipView() {
                return viewRef.get();
            }

            @Override public long observedRabiaTerm() {
                return 0L;
            }
        }

        private static MembershipView viewOf(Set<NodeId> members) {
            record StubView(Set<NodeId> coreMemberIds, Set<NodeId> onDutyMemberIds,
                            int healthyOnDutyCount, int desiredCoreSize) implements MembershipView {}
            return new StubView(members, members, members.size(), members.size());
        }

        @Test
        void evaluateQuorumState_quorumReached_routesClusterStateNotificationOnlyToQuorumPresenceChannel() {
            // Channel isolation: the new 6-arg overload delivers the quorum established/lost
            // ClusterStateNotification edge ONLY to the dedicated quorumPresenceRouter
            // (RabiaEngine stand-in) — never to a shared-bus public consumer. Conversely the
            // shared bus still carries a ClusterStateNotification routed on it directly
            // (ConsensusBridge's stand-in path).
            var publicBus = new CopyOnWriteArrayList<ClusterStateNotification>();
            var quorumChannel = new CopyOnWriteArrayList<ClusterStateNotification>();
            var sharedRouter = routerCapturing(publicBus);
            var quorumPresenceRouter = quorumPresenceCapturing(quorumChannel);

            var snapshot = new MutableSnapshotSource();
            snapshot.set(viewOf(Set.of(SELF, PEER_A, PEER_B)));

            var observer = TopologyObserver.topologyObserver(baseConfig(),
                                                             sharedRouter,
                                                             snapshot,
                                                             TopologyObserver.NEVER_DECOMMISSIONED,
                                                             TopologyObserver.ZERO_HLC_SUPPLIER,
                                                             quorumPresenceRouter)
                                           .unwrap();

            // Drive the quorum-established edge.
            observer.start().await();

            assertThat(quorumChannel)
                .as("quorum-established edge must reach ONLY the dedicated quorum-presence channel")
                .hasSize(1);
            assertThat(quorumChannel.getFirst().state())
                .isEqualTo(ClusterStateNotification.State.ACTIVE);
            assertThat(publicBus)
                .as("the shared bus must receive NO quorum edge from TopologyObserver")
                .isEmpty();

            // Drive the quorum-lost edge: shrink the view below quorum (1 < 2) and re-evaluate.
            snapshot.set(viewOf(Set.of(SELF)));
            observer.handleSetClusterSize(new TopologyManagementMessage.SetClusterSize(3));

            assertThat(quorumChannel)
                .as("quorum-lost edge must also reach ONLY the dedicated quorum-presence channel")
                .hasSize(2);
            assertThat(quorumChannel.getLast().state())
                .isEqualTo(ClusterStateNotification.State.PASSIVE);
            assertThat(publicBus)
                .as("the shared bus must still receive NO quorum edge from TopologyObserver")
                .isEmpty();

            // ConsensusBridge stand-in: a ClusterStateNotification routed directly on the shared
            // bus must still reach the public consumer — the shared path is intact.
            sharedRouter.route(ClusterStateNotification.active());

            assertThat(publicBus)
                .as("shared-bus ClusterStateNotification (ConsensusBridge path) still reaches public consumer")
                .hasSize(1);
            assertThat(publicBus.getFirst().state())
                .isEqualTo(ClusterStateNotification.State.ACTIVE);
        }

        @Test
        void initReconcile_quorumRestoredWithoutStructuralEvent_reEmitsActiveEdge() {
            // Missed-quorum-presence-edge wedge (Hetzner gate 2026-06-13): a transient flap that
            // drops then RESTORES the snapshot healthy count with NO structural membership event
            // (no add/remove/handleSetClusterSize/start) left nothing to re-emit the ACTIVE edge,
            // so Rabia stayed paused → permanent zero-leader wedge. The periodic initReconcile
            // tick now re-runs the CAS-edge-guarded evaluateQuorumState and recovers within one
            // reconcile interval. This test fails if that re-check is removed.
            var config = new TopologyConfig(SELF,
                                            3,
                                            timeSpan(100).millis(),
                                            timeSpan(1).seconds(),
                                            List.of(INFO_SELF));
            var notifications = new CopyOnWriteArrayList<ClusterStateNotification>();
            var router = routerCapturing(notifications);
            var snapshot = new MutableSnapshotSource();
            snapshot.set(viewOf(Set.of(SELF, PEER_A, PEER_B)));

            var observer = TopologyObserver.topologyObserver(config, router, snapshot).unwrap();

            observer.start().await();
            assertThat(notifications)
                .as("start() at full quorum publishes the initial ACTIVE edge")
                .hasSize(1);
            assertThat(notifications.getFirst().state())
                .isEqualTo(ClusterStateNotification.State.ACTIVE);

            // Deterministic PASSIVE via a structural event: drop the snapshot below quorum
            // (healthyOnDuty 1 < quorum 2) and trigger evaluateQuorumState through handleSetClusterSize.
            snapshot.set(viewOf(Set.of(SELF)));
            observer.handleSetClusterSize(new TopologyManagementMessage.SetClusterSize(3));
            assertThat(notifications)
                .as("below-quorum snapshot drives the PASSIVE edge")
                .hasSize(2);
            assertThat(notifications.getLast().state())
                .isEqualTo(ClusterStateNotification.State.PASSIVE);

            // Restore quorum with NO structural event — only the periodic reconcile tick can
            // re-emit the ACTIVE edge. Pre-fix this never happens and the wait times out.
            snapshot.set(viewOf(Set.of(SELF, PEER_A, PEER_B)));

            awaitTrue(() -> notifications.size() == 3,
                      "periodic initReconcile re-emits the ACTIVE edge after a structural-event-free quorum restore");
            assertThat(notifications.getLast().state())
                .as("the re-emitted edge after structural-event-free restore must be ACTIVE")
                .isEqualTo(ClusterStateNotification.State.ACTIVE);

            observer.stop().await();
        }

        /// Bounded poll for the scheduled reconcile tick (no Awaitility in this module): the
        /// re-emitted quorum edge arrives on the SharedScheduler timer thread, so it is observed
        /// asynchronously. Asserts the condition holds within the timeout (~20x the 100ms
        /// reconcile interval).
        private static void awaitTrue(BooleanSupplier condition, String description) {
            var deadline = System.nanoTime() + timeSpan(2).seconds().nanos();
            while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
                sleepBriefly();
            }
            assertThat(condition.getAsBoolean()).as(description).isTrue();
        }

        private static void sleepBriefly() {
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /// Wave-4 adaptation (cluster-topology-overhaul): `MembershipDecision` emission moved OUT
    /// of this observer into the aether `MembershipDeltaProjector`, driven by the
    /// `MembershipFsm` delta edge — the former `MembershipDecisionEmission` nested tests moved
    /// with it (`MembershipDeltaProjectorTest` covers the same contract: logIndex/HLC stamping,
    /// removal emission, minority-side suppression, exactly-once idempotence). What remains on
    /// this observer is the projector-facing prune surface: `pruneDeparted` must drop the
    /// departed peer via the existing `removeNode` path (dial-set + `DisconnectNode` routing +
    /// the evaluator's pre-existing `removeNode` quorum re-eval trigger) and reject self-removal.
    @Nested
    class PruneDeparted {
        private static MessageRouter.MutableRouter pruneRouter(List<NetworkServiceMessage.DisconnectNode> disconnects) {
            var router = MessageRouter.mutable();
            router.addRoute(NetworkServiceMessage.ListConnectedNodes.class, _ -> {});
            router.addRoute(NetworkServiceMessage.ConnectNode.class, _ -> {});
            router.addRoute(ClusterStateNotification.class, _ -> {});
            router.addRoute(NetworkServiceMessage.DisconnectNode.class, disconnects::add);
            return router;
        }

        @Test
        void pruneDeparted_discoveredPeer_leavesDialSetAndRoutesDisconnect() {
            var disconnects = new CopyOnWriteArrayList<NetworkServiceMessage.DisconnectNode>();
            var observer = TopologyObserver.topologyObserver(baseConfig(), pruneRouter(disconnects), fullQuorumSnapshotSource())
                                           .unwrap();
            observer.start().await();
            observer.handleDiscoveredNodes(new NetworkMessage.DiscoveredNodes(SELF, List.of(INFO_A)));
            assertThat(observer.topology()).contains(PEER_A);

            observer.pruneDeparted(PEER_A);

            assertThat(observer.topology())
                .as("pruned peer must leave the dial set via removeNode")
                .doesNotContain(PEER_A);
            assertThat(disconnects)
                .as("prune must route DisconnectNode exactly once for the departed peer")
                .hasSize(1);
            assertThat(disconnects.getFirst().nodeId()).isEqualTo(PEER_A);
        }

        @Test
        void pruneDeparted_self_isRejected() {
            var disconnects = new CopyOnWriteArrayList<NetworkServiceMessage.DisconnectNode>();
            var observer = TopologyObserver.topologyObserver(baseConfig(), pruneRouter(disconnects), fullQuorumSnapshotSource())
                                           .unwrap();
            observer.start().await();

            observer.pruneDeparted(SELF);

            assertThat(observer.topology())
                .as("self-prune must be rejected (removeNode self-guard)")
                .contains(SELF);
            assertThat(disconnects).isEmpty();
        }
    }
}
