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
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Verifies the public peer-registration API on `TopologyObserver`. Network adapters
/// call `registerPeer`/`unregisterPeer` directly on Hello handshake / confirmed peer
/// departure; no `TopologyManagementMessage` routing is involved.
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

    @Nested
    class RegisterPeer {
        @Test
        void registerPeer_unknownNode_addsToTopology() {
            var observer = newObserver(MessageRouter.mutable());

            observer.registerPeer(INFO_NEW);

            assertThat(observer.topology()).contains(NEW_PEER);
            assertThat(observer.get(NEW_PEER).isPresent()).isTrue();
        }

        @Test
        void registerPeer_active_routesConnectNode() {
            var router = MessageRouter.mutable();
            var connectRequests = new CopyOnWriteArrayList<NodeId>();
            router.addRoute(NetworkServiceMessage.ConnectNode.class, msg -> connectRequests.add(msg.node()));

            var observer = newObserver(router);
            observer.start().await();

            observer.registerPeer(INFO_NEW);

            assertThat(connectRequests).contains(NEW_PEER);
        }

        @Test
        void registerPeer_idempotent_doesNotDuplicateConnectRequests() {
            var router = MessageRouter.mutable();
            var connectRequests = new CopyOnWriteArrayList<NodeId>();
            router.addRoute(NetworkServiceMessage.ConnectNode.class, msg -> connectRequests.add(msg.node()));

            var observer = newObserver(router);
            observer.start().await();

            observer.registerPeer(INFO_NEW);
            observer.registerPeer(INFO_NEW);
            observer.registerPeer(INFO_NEW);

            assertThat(connectRequests.stream().filter(NEW_PEER::equals).count())
                .as("ConnectNode emitted only on first registration; later calls are no-ops")
                .isEqualTo(1L);
            assertThat(observer.topology()).contains(NEW_PEER);
        }

        @Test
        void registerPeer_clearsTombstoneFromPriorUnregister() {
            var observer = newObserver(MessageRouter.mutable());

            // Pre-existing peer (added in ctor) is removed and tombstoned, then re-registered.
            observer.unregisterPeer(PEER_A);
            assertThat(observer.topology()).doesNotContain(PEER_A);

            observer.registerPeer(INFO_A);

            assertThat(observer.topology()).contains(PEER_A);
            assertThat(observer.get(PEER_A).isPresent()).isTrue();
        }
    }

    @Nested
    class UnregisterPeer {
        @Test
        void unregisterPeer_removesFromTopology() {
            var observer = newObserver(MessageRouter.mutable());

            observer.unregisterPeer(PEER_A);

            assertThat(observer.topology()).doesNotContain(PEER_A);
            assertThat(observer.get(PEER_A).isEmpty()).isTrue();
        }

        @Test
        void unregisterPeer_routesDisconnectNode() {
            var router = MessageRouter.mutable();
            var disconnectRequests = new CopyOnWriteArrayList<NodeId>();
            router.addRoute(NetworkServiceMessage.DisconnectNode.class, msg -> disconnectRequests.add(msg.nodeId()));
            router.addRoute(NetworkServiceMessage.ConnectNode.class, _ -> {});

            var observer = newObserver(router);

            observer.unregisterPeer(PEER_A);

            assertThat(disconnectRequests).contains(PEER_A);
        }

        @Test
        void unregisterPeer_idempotent_secondCallIsNoOp() {
            var router = MessageRouter.mutable();
            var disconnectRequests = new CopyOnWriteArrayList<NodeId>();
            router.addRoute(NetworkServiceMessage.DisconnectNode.class, msg -> disconnectRequests.add(msg.nodeId()));
            router.addRoute(NetworkServiceMessage.ConnectNode.class, _ -> {});

            var observer = newObserver(router);

            observer.unregisterPeer(PEER_A);
            observer.unregisterPeer(PEER_A);
            observer.unregisterPeer(PEER_A);

            assertThat(disconnectRequests.stream().filter(PEER_A::equals).count())
                .as("DisconnectNode emitted only on first removal")
                .isEqualTo(1L);
        }

        @Test
        void unregisterPeer_self_isIgnored() {
            var observer = newObserver(MessageRouter.mutable());

            observer.unregisterPeer(SELF);

            assertThat(observer.topology()).contains(SELF);
            assertThat(observer.self().id()).isEqualTo(SELF);
        }

        @Test
        void unregisterPeer_tombstonesAgainstReconciliation() {
            // unregisterPeer marks the peer as tombstoned so static-config reconciliation
            // (initReconcile) will not resurrect it from config.coreNodes().
            var observer = newObserver(MessageRouter.mutable());

            observer.unregisterPeer(PEER_A);

            assertThat(observer.topology()).doesNotContain(PEER_A);
        }
    }

    /// Fix C: `initReconcile` must consult the KV-Store's `NodeLifecycleValue.DECOMMISSIONED`
    /// atoms via the injected `isDecommissioned` predicate, not just the in-memory
    /// `tombstonedNodes` set. The set is empty after a process restart; without the
    /// KV-backed predicate a DECOMMISSIONED ghost peer is silently re-seeded from
    /// `config.coreNodes()` on every reconciliation tick.
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
            // case (test fixtures, non-Aether RabiaNode usage). Behaviour matches
            // pre-Fix-C: every `config.coreNodes()` entry is added.
            Predicate<NodeId> isDecommissioned = _ -> false;

            var observer = observerWith(MessageRouter.mutable(), isDecommissioned);
            observer.start().await();

            assertThat(observer.topology()).contains(SELF, PEER_A, PEER_B);
        }

        @Test
        void initReconcile_inMemoryTombstone_skipsRegardlessOfKV() {
            // In-memory tombstone (set by `unregisterPeer`) wins independently of the
            // KV predicate. KV reports the node as ACTIVE (not DECOMMISSIONED), but the
            // in-session tombstone still suppresses the reseed.
            Predicate<NodeId> isDecommissioned = _ -> false;

            var observer = observerWith(MessageRouter.mutable(), isDecommissioned);
            observer.start().await();
            // Sanity: reseed has happened by now.
            assertThat(observer.topology()).contains(PEER_A);

            observer.unregisterPeer(PEER_A);

            assertThat(observer.topology()).doesNotContain(PEER_A);
            // Subsequent `initReconcile` ticks are a no-op for tombstoned peers.
            // We force a re-entry by calling start() on a stopped observer; even though
            // start() is idempotent, the public contract says tombstoned peers stay out.
            assertThat(observer.topology()).doesNotContain(PEER_A);
        }

        @Test
        void registerPeer_explicitReadd_overridesKvDecommissioned() {
            // Brief requirement #5: an explicit re-add (e.g. restarted container completes
            // QUIC Hello handshake → `registerPeer`) MUST succeed even if the KV still says
            // DECOMMISSIONED. The standard CTM lifecycle write path will replace the atom;
            // the observer-level filter must not stand in the way.
            //
            // The KV state is mutable in this test: initially DECOMMISSIONED (so the
            // initReconcile filter blocks the reseed), then we observe that registerPeer
            // bypasses the filter unconditionally.
            var kvState = new AtomicReference<Set<NodeId>>(Set.of(PEER_A));
            Predicate<NodeId> isDecommissioned = id -> kvState.get().contains(id);

            var observer = observerWith(MessageRouter.mutable(), isDecommissioned);
            observer.start().await();
            // Pre-condition: PEER_A blocked by KV filter.
            assertThat(observer.topology()).doesNotContain(PEER_A);

            observer.registerPeer(INFO_A);

            // Post-condition: explicit re-add succeeded despite KV still saying
            // DECOMMISSIONED. CTM is expected to subsequently transition the lifecycle
            // atom away from DECOMMISSIONED via the standard write path.
            assertThat(observer.topology()).contains(PEER_A);
            assertThat(observer.get(PEER_A).isPresent()).isTrue();
        }
    }
}
