package org.pragmatica.cluster.net;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkMessage;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;

import java.net.InetSocketAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.consensus.NodeId.randomNodeId;
import static org.pragmatica.consensus.topology.TopologyObserver.topologyObserver;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

class ClusterTopologyManagerIT {
    private final MessageRouter.MutableRouter router = MessageRouter.mutable();

    private TopologyObserver topologyManager;
    private NodeId nodeId1;
    private NodeId nodeId2;
    private NodeId nodeId3;
    private NodeInfo nodeInfo1;
    private NodeInfo nodeInfo2;
    private InetSocketAddress socketAddress1;
    private InetSocketAddress socketAddress2;
    private InetSocketAddress socketAddress3;

    @BeforeEach
    void setUp() {
        nodeId1 = randomNodeId();
        nodeId2 = randomNodeId();
        nodeId3 = randomNodeId();

        socketAddress1 = new InetSocketAddress("localhost", 8080);
        socketAddress2 = new InetSocketAddress("127.0.0.1", 8082);
        socketAddress3 = new InetSocketAddress("127.0.0.1", 8083);

        nodeInfo1 = NodeInfo.nodeInfo(nodeId1, NodeAddress.nodeAddress(socketAddress1).unwrap());
        nodeInfo2 = NodeInfo.nodeInfo(nodeId2, NodeAddress.nodeAddress(socketAddress2).unwrap());

        var config = new TopologyConfig(nodeId1,
                                        2,
                                        timeSpan(100).hours(),
                                        TimeSpan.timeSpan(10).seconds(),
                                        List.of(nodeInfo1, nodeInfo2));

        topologyObserver(config, router)
            .onFailure(cause -> fail("Failed to create topology manager: " + cause.message()))
            .onSuccess(tcpManager -> {
                topologyManager = tcpManager;

                // Register routes for @MessageReceiver methods
                router.addRoute(NetworkMessage.DiscoverNodes.class, tcpManager::handleDiscoverNodes);
                router.addRoute(NetworkMessage.DiscoveredNodes.class, tcpManager::handleDiscoveredNodes);
                router.addRoute(NetworkServiceMessage.ConnectedNodesList.class, tcpManager::reconcile);
            });
    }

    @Test
    void getReturnsNoneForNonExistentNode() {
        assertEquals(Option.none(), topologyManager.get(nodeId3));
    }

    @Test
    void getReturnsSelfAndPeersOnlyOnceDiscovered() {
        // Contract (TopologyObserver.java:405-407, a97a9e753c): a configured core peer is NOT seeded into the dial set; it
        // resolves only after SWIM discovery (`handleDiscoveredNodes`). Self is present from construction.
        assertEquals(Option.some(nodeInfo1), topologyManager.get(nodeId1));
        assertEquals(Option.none(), topologyManager.get(nodeId2));

        topologyManager.handleDiscoveredNodes(new NetworkMessage.DiscoveredNodes(nodeId1, List.of(nodeInfo2)));

        assertEquals(Option.some(nodeInfo2), topologyManager.get(nodeId2));
    }

    @Test
    void reverseLookupReturnsNoneForNonExistentAddress() {
        assertEquals(Option.none(), topologyManager.reverseLookup(socketAddress3));
    }

    @Test
    void reverseLookupResolvesSelfAndPeersOnlyOnceDiscovered() {
        // Contract as in getReturnsSelfAndPeersOnlyOnceDiscovered: a configured core peer's address resolves after discovery only.
        assertEquals(Option.some(nodeId1), topologyManager.reverseLookup(socketAddress1));
        assertEquals(Option.none(), topologyManager.reverseLookup(socketAddress2));

        topologyManager.handleDiscoveredNodes(new NetworkMessage.DiscoveredNodes(nodeId1, List.of(nodeInfo2)));

        assertEquals(Option.some(nodeId2), topologyManager.reverseLookup(socketAddress2));
    }

    @Test
    void clusterSizeReturnsCorrectSize() {
        assertEquals(2, topologyManager.clusterSize());
    }
}