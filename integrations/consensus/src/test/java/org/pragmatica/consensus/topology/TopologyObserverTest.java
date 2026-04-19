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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Verifies the public peer-registration API on `TopologyObserver`. These are the
/// methods that network adapters now call directly instead of routing
/// `TopologyManagementMessage.AddNode/RemoveNode` through the message router.
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
        void unregisterPeer_tombstonesNodeAgainstReconciliation() {
            // The tombstone path is the same one exercised by handleRemoveNodeMessage; verify
            // direct unregister sets it by checking the legacy handler delegates symmetrically.
            var observerA = newObserver(MessageRouter.mutable());
            var observerB = newObserver(MessageRouter.mutable());

            observerA.unregisterPeer(PEER_A);
            observerB.handleRemoveNodeMessage(new TopologyManagementMessage.RemoveNode(PEER_A));

            assertThat(observerA.topology()).isEqualTo(observerB.topology());
        }
    }

    @Nested
    class LegacyRouterHandlersDelegateToPublicApi {
        @Test
        void handleAddNodeMessage_delegatesToRegisterPeer() {
            var observer = newObserver(MessageRouter.mutable());

            observer.handleAddNodeMessage(new TopologyManagementMessage.AddNode(INFO_NEW));

            assertThat(observer.topology()).contains(NEW_PEER);
        }

        @Test
        void handleRemoveNodeMessage_delegatesToUnregisterPeer() {
            var observer = newObserver(MessageRouter.mutable());

            observer.handleRemoveNodeMessage(new TopologyManagementMessage.RemoveNode(PEER_A));

            assertThat(observer.topology()).doesNotContain(PEER_A);
        }
    }
}
