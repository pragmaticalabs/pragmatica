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
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Verifies R4 semantics on `TopologyObserver`: the mutation API surface
/// (`registerPeer`, `unregisterPeer`, `markReady`, `markDeparted`,
/// `handleConnectionFailed`, `handleConnectionEstablished`) is now inert.
/// HealthReconciler is the sole writer of `NodeLifecycleKey`; the observer's
/// authoritative readers project `MembershipView` from KV via `GenerationSnapshotSource`.
/// The legacy mutation methods remain on the interface only for compile-time
/// compatibility with R5-pending transport adapters; they perform no state changes.
class TopologyObserverTest {
    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId PEER_A = nodeId("node-a").unwrap();
    private static final NodeId PEER_B = nodeId("node-b").unwrap();
    private static final NodeId NEW_PEER = nodeId("node-new").unwrap();

    private static final NodeInfo INFO_SELF = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("localhost", 6000).unwrap());
    private static final NodeInfo INFO_A = NodeInfo.nodeInfo(PEER_A, NodeAddress.nodeAddress("localhost", 6001).unwrap());
    private static final NodeInfo INFO_B = NodeInfo.nodeInfo(PEER_B, NodeAddress.nodeAddress("localhost", 6002).unwrap());
    private static final NodeInfo INFO_NEW = NodeInfo.nodeInfo(NEW_PEER, NodeAddress.nodeAddress("localhost", 6003).unwrap());

    private static TopologyConfig baseConfig() {
        return new TopologyConfig(SELF,
                                  3,
                                  timeSpan(60).seconds(),
                                  timeSpan(1).seconds(),
                                  List.of(INFO_SELF, INFO_A, INFO_B));
    }

    private static TopologyObserver newObserver(MessageRouter router) {
        return TopologyObserver.topologyObserver(baseConfig(), router).unwrap();
    }

    /// R4: mutation methods are inert no-ops. Topology state is sourced from
    /// `config.coreNodes()` at construction (filtered by the KV-decommissioned
    /// predicate) and from KV `NodeLifecycleKey` changes via the snapshot source.
    @Nested
    class MutationApiInert {
        @Test
        void registerPeer_isNoOp_doesNotMutateTopology() {
            var observer = newObserver(MessageRouter.mutable());
            var before = observer.topology();

            observer.registerPeer(INFO_NEW);

            assertThat(observer.topology())
                .as("R4: registerPeer is inert — topology projection unchanged")
                .isEqualTo(before)
                .doesNotContain(NEW_PEER);
            assertThat(observer.get(NEW_PEER).isEmpty())
                .as("R4: registerPeer must not add unknown nodes")
                .isTrue();
        }

        @Test
        void registerPeer_isNoOp_doesNotRouteConnectNode() {
            var router = MessageRouter.mutable();
            var connectRequests = new CopyOnWriteArrayList<NodeId>();
            router.addRoute(NetworkServiceMessage.ConnectNode.class, msg -> connectRequests.add(msg.node()));

            var observer = newObserver(router);
            observer.start().await();

            observer.registerPeer(INFO_NEW);

            assertThat(connectRequests)
                .as("R4: registerPeer must not emit ConnectNode")
                .doesNotContain(NEW_PEER);
        }

        @Test
        void unregisterPeer_isNoOp_doesNotMutateTopology() {
            var observer = newObserver(MessageRouter.mutable());

            observer.unregisterPeer(PEER_A);

            assertThat(observer.topology())
                .as("R4: unregisterPeer is inert — KV is the sole authority for removal")
                .contains(PEER_A);
        }

        @Test
        void unregisterPeer_isNoOp_doesNotRouteDisconnectNode() {
            var router = MessageRouter.mutable();
            var disconnectRequests = new CopyOnWriteArrayList<NodeId>();
            router.addRoute(NetworkServiceMessage.DisconnectNode.class, msg -> disconnectRequests.add(msg.nodeId()));
            router.addRoute(NetworkServiceMessage.ConnectNode.class, _ -> {});

            var observer = newObserver(router);

            observer.unregisterPeer(PEER_A);

            assertThat(disconnectRequests)
                .as("R4: unregisterPeer must not emit DisconnectNode")
                .doesNotContain(PEER_A);
        }

        @Test
        void markReady_isNoOp_doesNotMutateReadyTracker() {
            var observer = newObserver(MessageRouter.mutable());
            var before = observer.readyNodeCount();

            observer.markReady(NEW_PEER);
            observer.markReady(NEW_PEER, NodeAddress.nodeAddress("localhost", 6003).unwrap());

            assertThat(observer.readyNodeCount())
                .as("R4: markReady is inert — readyNodeCount sourced from KV/snapshot only")
                .isEqualTo(before);
        }

        @Test
        void markDeparted_isNoOp_doesNotMutateReadyTracker() {
            var observer = newObserver(MessageRouter.mutable());
            var before = observer.readyNodeCount();

            observer.markDeparted(PEER_A);

            assertThat(observer.readyNodeCount())
                .as("R4: markDeparted is inert — KV NodeLifecycleKey REMOVE is sole authority")
                .isEqualTo(before);
        }

        @Test
        void handleConnectionFailed_isNoOp_doesNotMutateTopology() {
            var observer = newObserver(MessageRouter.mutable());
            var before = observer.topology();

            observer.handleConnectionFailed(new NetworkServiceMessage.ConnectionFailed(PEER_A, Causes.cause("link reset")));

            assertThat(observer.topology())
                .as("R4: transport-level events do not mutate topology projection")
                .isEqualTo(before);
        }

        @Test
        void handleConnectionEstablished_isNoOp_doesNotMutateTopology() {
            var observer = newObserver(MessageRouter.mutable());
            var before = observer.topology();

            observer.handleConnectionEstablished(new NetworkServiceMessage.ConnectionEstablished(PEER_A));

            assertThat(observer.topology())
                .as("R4: transport-level events do not mutate topology projection")
                .isEqualTo(before);
        }

        @Test
        void unregisterPeer_self_isStillIgnored() {
            var observer = newObserver(MessageRouter.mutable());

            observer.unregisterPeer(SELF);

            assertThat(observer.topology()).contains(SELF);
            assertThat(observer.self().id()).isEqualTo(SELF);
        }
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
            // baseConfig has SELF + PEER_A + PEER_B (clusterSize=3, quorum=2). After
            // construction, `nodeStatesById` already contains all three peers as healthy
            // (legacy fallback path). `start()` triggers the initial publish — peers=2,
            // +1=3 >= quorum=2 → established once.
            var notifications = new CopyOnWriteArrayList<QuorumStateNotification>();
            var router = routerCapturing(notifications);

            var observer = TopologyObserver.topologyObserver(baseConfig(), router).unwrap();
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
        void quicHandshakeFlap_doesNotChangeQuorumState_R4_inert() {
            // R4: transport-level events are inert on TopologyObserver. A QUIC handshake
            // storm that calls handleConnectionFailed/Established many times must not
            // produce any QuorumStateNotification edges — these methods are no-ops.
            var notifications = new CopyOnWriteArrayList<QuorumStateNotification>();
            var router = routerCapturing(notifications);
            router.addRoute(NetworkServiceMessage.ConnectNode.class, _ -> {});
            router.addRoute(NetworkServiceMessage.DisconnectNode.class, _ -> {});
            router.addRoute(NetworkServiceMessage.ListConnectedNodes.class, _ -> {});

            var observer = TopologyObserver.topologyObserver(baseConfig(), router).unwrap();
            observer.start().await();
            // Initial established (peers=2 from config seed crosses up).
            assertThat(notifications).hasSize(1);

            // Simulate the handshake storm: each peer flaps connect/fail/connect 5 times.
            for (var i = 0; i < 5; i++) {
                observer.handleConnectionFailed(new NetworkServiceMessage.ConnectionFailed(PEER_A, Causes.cause("flap " + i)));
                observer.handleConnectionEstablished(new NetworkServiceMessage.ConnectionEstablished(PEER_A));
                observer.handleConnectionFailed(new NetworkServiceMessage.ConnectionFailed(PEER_B, Causes.cause("flap " + i)));
                observer.handleConnectionEstablished(new NetworkServiceMessage.ConnectionEstablished(PEER_B));
            }

            assertThat(notifications)
                .as("R4: transport flaps must not change quorum state")
                .hasSize(1);
        }

        @Test
        void connectionFailed_isTransportOnly_doesNotRouteQuorumTransition() {
            // POST-FIX INVARIANT (R4): transport-level events do not route quorum
            // transitions. With register/unregister also inert, the only way quorum
            // state can change in tests is via a snapshot source (KV-projected).
            var notifications = new CopyOnWriteArrayList<QuorumStateNotification>();
            var router = routerCapturing(notifications);

            var observer = TopologyObserver.topologyObserver(baseConfig(), router).unwrap();
            observer.start().await();
            // Initial published edge from start() — established.
            assertThat(notifications).hasSize(1);

            // Sink other transport-level routes.
            router.addRoute(NetworkServiceMessage.ConnectNode.class, _ -> {});
            router.addRoute(NetworkServiceMessage.DisconnectNode.class, _ -> {});
            router.addRoute(NetworkServiceMessage.ListConnectedNodes.class, _ -> {});

            observer.handleConnectionFailed(new NetworkServiceMessage.ConnectionFailed(PEER_A, Causes.cause("link reset")));
            observer.handleConnectionFailed(new NetworkServiceMessage.ConnectionFailed(PEER_B, Causes.cause("link reset")));

            assertThat(notifications)
                .as("transport-level ConnectionFailed must not fire QuorumStateNotification")
                .hasSize(1);
        }

        @Test
        void constructionWithFullCoreNodes_doesNotRouteUntilStart() {
            // baseConfig() seeds SELF + PEER_A + PEER_B. Constructor does NOT publish;
            // only `start()` may.
            var notifications = new CopyOnWriteArrayList<QuorumStateNotification>();
            var router = routerCapturing(notifications);

            var observer = TopologyObserver.topologyObserver(baseConfig(), router).unwrap();

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
            var delegate = MessageRouter.DelegateRouter.delegate();
            var notifications = new CopyOnWriteArrayList<QuorumStateNotification>();

            var observer = TopologyObserver.topologyObserver(baseConfig(), delegate).unwrap();
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
