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
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.utils.TimeSource;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
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
            var observer = TopologyObserver.topologyObserver(baseConfig(), MessageRouter.mutable()).unwrap();

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
            var observer = TopologyObserver.topologyObserver(baseConfig(), MessageRouter.mutable()).unwrap();
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
            var observer = TopologyObserver.topologyObserver(baseConfig(), MessageRouter.mutable()).unwrap();

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
            var observer = TopologyObserver.topologyObserver(baseConfig(), MessageRouter.mutable(), snapshot).unwrap();
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
    }

    /// RC1 Step 2: `publishMembershipDeltas` is the sole emitter of `MembershipDecision`
    /// and carries (logIndex, stampedAt) metadata. Verifies:
    /// - lifecycle-projection walker emits one new variant per terminal transition
    /// - logIndex source comes from `observedRabiaTerm()`
    /// - stampedAt source comes from the injected hlcSupplier
    /// - minority-side observers DO NOT emit (quorum gate from Step 5)
    /// - 6 variants are covered
    @Nested
    class MembershipDecisionEmission {
        record StubView(Set<NodeId> coreMemberIds, Set<NodeId> onDutyMemberIds,
                        int healthyOnDutyCount, int desiredCoreSize,
                        Map<NodeId, LifecycleState> lifecycleStates) implements MembershipView {}

        static final class StatefulSnapshotSource implements GenerationSnapshotSource {
            private final AtomicReference<Option<MembershipView>> viewRef = new AtomicReference<>(Option.none());
            private final AtomicReference<Long> termRef = new AtomicReference<>(0L);

            void set(MembershipView view, long term) {
                viewRef.set(Option.some(view));
                termRef.set(term);
            }

            @Override public Option<MembershipView> currentMembershipView() {
                return viewRef.get();
            }

            @Override public long observedRabiaTerm() {
                return termRef.get();
            }
        }

        private static MessageRouter.MutableRouter routerCapturing(List<MembershipDecision> sink) {
            var router = MessageRouter.mutable();
            router.addRoute(MembershipDecision.NodeJoined.class, sink::add);
            router.addRoute(MembershipDecision.NodeRemoved.class, sink::add);
            router.addRoute(MembershipDecision.NodeDecommissioned.class, sink::add);
            router.addRoute(MembershipDecision.NodeJoining.class, sink::add);
            router.addRoute(MembershipDecision.NodeDraining.class, sink::add);
            router.addRoute(MembershipDecision.NodeFailedDrain.class, sink::add);
            router.addRoute(MembershipDecision.NodeShuttingDown.class, sink::add);
            return router;
        }

        private static StubView viewWithLifecycles(Map<NodeId, LifecycleState> lifecycles) {
            var onDuty = lifecycles.entrySet().stream()
                                       .filter(e -> e.getValue() == LifecycleState.ON_DUTY)
                                       .map(Map.Entry::getKey)
                                       .collect(java.util.stream.Collectors.toUnmodifiableSet());
            return new StubView(lifecycles.keySet(), onDuty, onDuty.size(), lifecycles.size(), lifecycles);
        }

        private static void assertJoinStamps(MembershipDecision.NodeJoined j, long expectedLogIndex, HlcTimestamp expectedHlc) {
            assertThat(j.logIndex()).isEqualTo(expectedLogIndex);
            assertThat(j.stampedAt()).isEqualTo(expectedHlc);
        }

        private static TopologyObserver observerWith(MessageRouter router,
                                                     GenerationSnapshotSource snapshot,
                                                     Supplier<HlcTimestamp> hlcSupplier) {
            return TopologyObserver.topologyObserver(baseConfig(),
                                                     router,
                                                     TimeSource.system(),
                                                     snapshot,
                                                     TopologyObserver.NEVER_DECOMMISSIONED,
                                                     hlcSupplier).unwrap();
        }

        @Test
        void publishMembershipDeltas_stampsLogIndexAndHlc_onNodeJoined() {
            var emissions = new CopyOnWriteArrayList<MembershipDecision>();
            var snapshot = new StatefulSnapshotSource();
            var hlc = new HlcTimestamp(HlcTimestamp.pack(12_345L, 0), new NodeId("node-self"));

            // Seed snapshot before start() so initial publish observes the configured core.
            snapshot.set(viewWithLifecycles(Map.of(SELF, LifecycleState.ON_DUTY,
                                                   PEER_A, LifecycleState.ON_DUTY,
                                                   PEER_B, LifecycleState.ON_DUTY)),
                          42L);

            var observer = observerWith(routerCapturing(emissions), snapshot, () -> hlc);
            observer.start().await();

            // First publish: 3 NodeJoined emissions (initial core set is non-empty against the
            // empty previousCoreMembers seed) PLUS 3 lifecycle decisions if ON_DUTY transitions
            // were emitted — ON_DUTY is intentionally NOT emitted to avoid double-firing.
            var joins = emissions.stream()
                                 .filter(MembershipDecision.NodeJoined.class::isInstance)
                                 .map(MembershipDecision.NodeJoined.class::cast)
                                 .toList();
            assertThat(joins).hasSize(3);
            assertThat(joins).allSatisfy(j -> assertJoinStamps(j, 42L, hlc));
            // ON_DUTY does not produce a lifecycle decision (covered by NodeJoined).
            assertThat(emissions).noneMatch(MembershipDecision.NodeJoining.class::isInstance);
        }

        @Test
        void publishMembershipDeltas_emitsNodeDraining_onLifecycleTransitionToDraining() {
            var emissions = new CopyOnWriteArrayList<MembershipDecision>();
            var snapshot = new StatefulSnapshotSource();
            var hlc = new HlcTimestamp(HlcTimestamp.pack(100L, 0), new NodeId("node-self"));

            snapshot.set(viewWithLifecycles(Map.of(SELF, LifecycleState.ON_DUTY,
                                                   PEER_A, LifecycleState.ON_DUTY,
                                                   PEER_B, LifecycleState.ON_DUTY)),
                          1L);

            var observer = observerWith(routerCapturing(emissions), snapshot, () -> hlc);
            observer.start().await();
            emissions.clear();

            // Transition PEER_A to DRAINING.
            snapshot.set(viewWithLifecycles(Map.of(SELF, LifecycleState.ON_DUTY,
                                                   PEER_A, LifecycleState.DRAINING,
                                                   PEER_B, LifecycleState.ON_DUTY)),
                          2L);
            // Re-route the observation by re-feeding the quorum-state evaluator — calling
            // handleSetClusterSize with the same size is a benign no-op trigger.
            observer.handleSetClusterSize(new TopologyManagementMessage.SetClusterSize(3));

            var drains = emissions.stream()
                                  .filter(MembershipDecision.NodeDraining.class::isInstance)
                                  .map(MembershipDecision.NodeDraining.class::cast)
                                  .toList();
            assertThat(drains).hasSize(1);
            assertThat(drains.getFirst().nodeId()).isEqualTo(PEER_A);
            assertThat(drains.getFirst().logIndex()).isEqualTo(2L);
            assertThat(drains.getFirst().stampedAt()).isEqualTo(hlc);
        }

        @Test
        void publishMembershipDeltas_emitsNodeDecommissioned_onLifecycleTransitionToStopped() {
            // After Step H/I collapse: all terminal lifecycle transitions (former
            // DECOMMISSIONED, FAILED_DRAIN, SHUTTING_DOWN) map to the single STOPPED
            // value and route uniformly to MembershipDecision.NodeDecommissioned.
            // The StopReason discriminator (FORCED / DRAIN_FAILED / GRACEFUL) lives
            // on the slice-side NodeLifecycleValue sidecar — not on the consensus event.
            var emissions = new CopyOnWriteArrayList<MembershipDecision>();
            var snapshot = new StatefulSnapshotSource();
            snapshot.set(viewWithLifecycles(Map.of(SELF, LifecycleState.ON_DUTY,
                                                   PEER_A, LifecycleState.DRAINING,
                                                   PEER_B, LifecycleState.ON_DUTY)),
                          1L);

            var observer = observerWith(routerCapturing(emissions), snapshot, TopologyObserver.ZERO_HLC_SUPPLIER);
            observer.start().await();
            emissions.clear();

            snapshot.set(viewWithLifecycles(Map.of(SELF, LifecycleState.ON_DUTY,
                                                   PEER_A, LifecycleState.STOPPED,
                                                   PEER_B, LifecycleState.ON_DUTY)),
                          2L);
            observer.handleSetClusterSize(new TopologyManagementMessage.SetClusterSize(3));

            assertThat(emissions).anyMatch(MembershipDecision.NodeDecommissioned.class::isInstance);
        }

        @Test
        void publishMembershipDeltas_minoritySide_doesNotEmit() {
            // Drop snapshot to below-quorum count — quorum is 2 (3/2+1) and snapshot
            // contains only 1 core member; quorumEstablished latch stays false.
            var emissions = new CopyOnWriteArrayList<MembershipDecision>();
            var snapshot = new StatefulSnapshotSource();
            snapshot.set(viewWithLifecycles(Map.of(SELF, LifecycleState.ON_DUTY)), 1L);

            var observer = observerWith(routerCapturing(emissions), snapshot, TopologyObserver.ZERO_HLC_SUPPLIER);
            observer.start().await();

            assertThat(emissions)
                .as("non-quorate observer must not emit MembershipDecision events")
                .isEmpty();
        }

        @Test
        void publishMembershipDeltas_emitsOncePerTransition_idempotentOnReplay() {
            var emissions = new CopyOnWriteArrayList<MembershipDecision>();
            var snapshot = new StatefulSnapshotSource();
            snapshot.set(viewWithLifecycles(Map.of(SELF, LifecycleState.ON_DUTY,
                                                   PEER_A, LifecycleState.ON_DUTY,
                                                   PEER_B, LifecycleState.ON_DUTY)),
                          1L);

            var observer = observerWith(routerCapturing(emissions), snapshot, TopologyObserver.ZERO_HLC_SUPPLIER);
            observer.start().await();
            var initial = emissions.size();

            // Trigger another publish with the same view — no new decisions should appear.
            observer.handleSetClusterSize(new TopologyManagementMessage.SetClusterSize(3));

            assertThat(emissions.size())
                .as("repeated snapshot with no delta must not re-emit decisions")
                .isEqualTo(initial);
        }
    }
}
