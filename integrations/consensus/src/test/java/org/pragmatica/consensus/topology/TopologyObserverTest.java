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
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
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
}
