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
import java.util.function.Predicate;
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

    /// `initReconcile` consults the KV-Store's `NodeLifecycleValue.DECOMMISSIONED`
    /// atoms via the injected `isDecommissioned` predicate so a process restart
    /// does not silently re-seed a DECOMMISSIONED ghost peer from
    /// `config.coreNodes()`.
    @Nested
    class KvDecommissionedFilter {
        private static TopologyObserver observerWith(MessageRouter router, Predicate<NodeId> isDecommissioned) {
            return TopologyObserver.topologyObserver(baseConfig(), router, isDecommissioned).unwrap();
        }

        @Test
        void initReconcile_decommissionedNodeInKV_skipsConfigReseed() {
            // PEER_A is in `config.coreNodes()` but the KV says it's DECOMMISSIONED.
            // The fresh observer must NOT add it, neither at construction time nor when
            // `start()` triggers `initReconcile`.
            Predicate<NodeId> isDecommissioned = id -> id.equals(PEER_A);

            var observer = observerWith(MessageRouter.mutable(), isDecommissioned);
            observer.start().await();

            assertThat(observer.topology()).doesNotContain(PEER_A);
            assertThat(observer.get(PEER_A).isEmpty()).isTrue();
        }

        @Test
        void initReconcile_activeNodeInKV_addsConfigReseed() {
            // PEER_A is in `config.coreNodes()` and KV does NOT mark it DECOMMISSIONED
            // (e.g. state is ACTIVE). It must be added.
            Predicate<NodeId> isDecommissioned = _ -> false;

            var observer = observerWith(MessageRouter.mutable(), isDecommissioned);
            observer.start().await();

            assertThat(observer.topology()).contains(PEER_A);
            assertThat(observer.get(PEER_A).isPresent()).isTrue();
        }

        @Test
        void initReconcile_noKVAtom_addsConfigReseed_legacyBehavior() {
            // Predicate returns false for every node — the legacy "no KV reader wired"
            // case (test fixtures, non-Aether RabiaNode usage). Every
            // `config.coreNodes()` entry is added.
            Predicate<NodeId> isDecommissioned = _ -> false;

            var observer = observerWith(MessageRouter.mutable(), isDecommissioned);
            observer.start().await();

            assertThat(observer.topology()).contains(SELF, PEER_A, PEER_B);
        }
    }

    /// `TopologyObserver` is the canonical publisher of `QuorumStateNotification`.
    /// QUIC/Netty transports do not publish quorum state. After R4 the observer
    /// is a pure projection: edge transitions are computed from `MembershipView`
    /// via the snapshot source plus the seeded `nodeStatesById` map (legacy fallback
    /// for tests / non-Aether `RabiaNode` usage where no snapshot source is wired).
    @Nested
    class QuorumStatePublishing {
        private static MessageRouter.MutableRouter routerCapturing(List<QuorumStateNotification> sink) {
            var router = MessageRouter.mutable();
            router.addRoute(QuorumStateNotification.class, sink::add);
            return router;
        }

        @Test
        void start_aboveQuorumViaConfig_routesEstablishedExactlyOnce() {
            // RC1-9 audit Step 5: snapshot is the SOLE source of healthy-count truth.
            // Tests must seed a synthetic snapshot reflecting the configured core set;
            // start() then publishes ESTABLISHED based on snapshot.healthyOnDutyCount.
            var notifications = new CopyOnWriteArrayList<QuorumStateNotification>();
            var router = routerCapturing(notifications);

            var observer = TopologyObserver.topologyObserver(baseConfig(), router, fullQuorumSnapshotSource()).unwrap();
            observer.start().await();

            assertThat(notifications)
                .as("start() with full-quorum config publishes established once")
                .hasSize(1);
            assertThat(notifications.getFirst().state())
                .isEqualTo(QuorumStateNotification.State.ESTABLISHED);
        }

        @Test
        void start_belowQuorum_doesNotRouteEstablished() {
            // Self-only legacy config — peers=0, +1=1 < quorum=2 → no edge fires.
            var notifications = new CopyOnWriteArrayList<QuorumStateNotification>();
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
            var notifications = new CopyOnWriteArrayList<QuorumStateNotification>();
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
                .isEqualTo(QuorumStateNotification.State.ESTABLISHED);
        }

        @Test
        void construction_withPartiallyWiredDelegateRouter_doesNotNpe() {
            // Regression for HEAD 3c66e9e65 NPE: production wiring uses a
            // `MessageRouter.DelegateRouter` whose `delegate` field is null at
            // construction time and is populated by node bootstrap before `start()`.
            // RC1-9 audit Step 5: snapshot must be seeded for ESTABLISHED edge to fire.
            var delegate = MessageRouter.DelegateRouter.delegate();
            var notifications = new CopyOnWriteArrayList<QuorumStateNotification>();

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
                .isEqualTo(QuorumStateNotification.State.ESTABLISHED);
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
            var hlc = new HlcTimestamp(HlcTimestamp.pack(12_345L, 0), "node-self");

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
            var hlc = new HlcTimestamp(HlcTimestamp.pack(100L, 0), "node-self");

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
