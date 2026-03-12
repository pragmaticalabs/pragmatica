package org.pragmatica.aether.node.health;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.swim.GossipEncryptor;
import org.pragmatica.swim.NettySwimTransport;
import org.pragmatica.swim.SwimConfig;
import org.pragmatica.swim.SwimMember;
import org.pragmatica.swim.SwimMembershipListener;
import org.pragmatica.swim.SwimProtocol;
import org.pragmatica.swim.SwimTransport;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Bridges SWIM membership events to TopologyChangeNotification for core-to-core health detection.
///
/// SWIM detects node failures via UDP probing, which is more reliable than TCP disconnect
/// for determining actual node health. TCP disconnects may be transient network glitches;
/// SWIM's probe-suspect-faulty progression provides confirmed failure detection.
@SuppressWarnings({"JBCT-RET-01", "JBCT-RET-03", "JBCT-EX-01"})
public final class CoreSwimHealthDetector implements SwimMembershipListener {
    private static final Logger log = LoggerFactory.getLogger(CoreSwimHealthDetector.class);

    /// Core SWIM config: faster probing for core consensus nodes.
    private static final SwimConfig CORE_SWIM_CONFIG = SwimConfig.swimConfig(Duration.ofMillis(500),
                                                                             Duration.ofMillis(300),
                                                                             3,
                                                                             Duration.ofSeconds(10),
                                                                             8);

    private final MessageRouter router;
    private final TopologyConfig topologyConfig;
    private final Serializer serializer;
    private final Deserializer deserializer;
    private final GossipEncryptor encryptor;
    private final List<NodeId> currentTopology;
    private volatile SwimProtocol swimProtocol;
    private volatile SwimTransport swimTransport;

    private CoreSwimHealthDetector(MessageRouter router,
                                   TopologyConfig topologyConfig,
                                   Serializer serializer,
                                   Deserializer deserializer,
                                   GossipEncryptor encryptor) {
        this.router = router;
        this.topologyConfig = topologyConfig;
        this.serializer = serializer;
        this.deserializer = deserializer;
        this.encryptor = encryptor;
        this.currentTopology = new CopyOnWriteArrayList<>(extractNodeIds(topologyConfig.coreNodes()));
    }

    /// Factory method for creating a CoreSwimHealthDetector with encryption.
    public static CoreSwimHealthDetector coreSwimHealthDetector(MessageRouter router,
                                                                TopologyConfig topologyConfig,
                                                                Serializer serializer,
                                                                Deserializer deserializer,
                                                                GossipEncryptor encryptor) {
        return new CoreSwimHealthDetector(router, topologyConfig, serializer, deserializer, encryptor);
    }

    /// Factory method for creating a CoreSwimHealthDetector without encryption (backward-compatible).
    public static CoreSwimHealthDetector coreSwimHealthDetector(MessageRouter router,
                                                                TopologyConfig topologyConfig,
                                                                Serializer serializer,
                                                                Deserializer deserializer) {
        return coreSwimHealthDetector(router, topologyConfig, serializer, deserializer, GossipEncryptor.none());
    }

    /// Start the SWIM protocol for core node health detection.
    /// SWIM port = cluster port + 1.
    public Promise<Unit> start() {
        var selfPort = findSelfPort();
        var swimPort = selfPort + 1;
        var selfHost = topologyConfig.coreNodes()
                                     .stream()
                                     .filter(n -> n.id()
                                                   .equals(topologyConfig.self()))
                                     .findFirst()
                                     .map(n -> n.address()
                                                .host())
                                     .orElse("localhost");
        var selfAddress = new InetSocketAddress(selfHost, swimPort);
        return NettySwimTransport.nettySwimTransport(serializer, deserializer, encryptor)
                                 .flatMap(transport -> createAndStartProtocol(transport, selfAddress, swimPort))
                                 .async()
                                 .mapToUnit();
    }

    /// Stop the SWIM protocol and transport.
    public void stop() {
        var protocol = swimProtocol;
        if (protocol != null) {
            protocol.stop();
            swimProtocol = null;
        }
        var transport = swimTransport;
        if (transport != null) {
            transport.stop();
            swimTransport = null;
        }
    }

    /// Update the tracked topology view. Called when topology changes are received from other sources.
    public void updateTopology(List<NodeId> topology) {
        currentTopology.clear();
        currentTopology.addAll(topology);
    }

    // ---- SwimMembershipListener ----
    @Override
    public void onMemberJoined(SwimMember member) {
        log.info("SWIM member joined: {}", member.nodeId());
    }

    @Override
    public void onMemberSuspect(SwimMember member) {
        log.warn("SWIM member suspected: {}", member.nodeId());
    }

    @Override
    public void onMemberFaulty(SwimMember member) {
        log.error("SWIM member faulty: {}, routing topology removal", member.nodeId());
        routeNodeRemoved(member.nodeId());
    }

    @Override
    public void onMemberLeft(NodeId leftNodeId) {
        log.warn("SWIM member left: {}, routing topology removal", leftNodeId);
        routeNodeRemoved(leftNodeId);
    }

    // ---- Internal ----
    private void routeNodeRemoved(NodeId nodeId) {
        currentTopology.remove(nodeId);
        var notification = TopologyChangeNotification.nodeRemoved(nodeId, List.copyOf(currentTopology));
        router.route(notification);
    }

    private int findSelfPort() {
        return topologyConfig.coreNodes()
                             .stream()
                             .filter(n -> n.id()
                                           .equals(topologyConfig.self()))
                             .findFirst()
                             .map(n -> n.address()
                                        .port())
                             .orElse(0);
    }

    @SuppressWarnings("JBCT-RET-01")
    private org.pragmatica.lang.Result<SwimProtocol> createAndStartProtocol(SwimTransport transport,
                                                                            InetSocketAddress selfAddress,
                                                                            int swimPort) {
        this.swimTransport = transport;
        // Start transport to bind the UDP port for receiving messages
        transport.start(swimPort,
                        (sender, message) -> {
                            var protocol = swimProtocol;
                            if (protocol != null) {
                                protocol.onMessage(sender, message);
                            }
                        });
        return SwimProtocol.swimProtocol(CORE_SWIM_CONFIG,
                                         transport,
                                         this,
                                         topologyConfig.self(),
                                         selfAddress)
                           .flatMap(SwimProtocol::start)
                           .map(this::storeAndSeed);
    }

    private SwimProtocol storeAndSeed(SwimProtocol protocol) {
        swimProtocol = protocol;
        seedMembers(protocol);
        return protocol;
    }

    private void seedMembers(SwimProtocol protocol) {
        topologyConfig.coreNodes()
                      .stream()
                      .filter(node -> !node.id()
                                           .equals(topologyConfig.self()))
                      .forEach(node -> addSeedMember(protocol, node));
    }

    private static void addSeedMember(SwimProtocol protocol, NodeInfo node) {
        var swimPort = node.address()
                           .port() + 1;
        var swimAddress = new InetSocketAddress(node.address()
                                                    .host(),
                                                swimPort);
        protocol.addSeedMember(node.id(), swimAddress);
    }

    private static List<NodeId> extractNodeIds(List<NodeInfo> nodes) {
        return nodes.stream()
                    .map(NodeInfo::id)
                    .toList();
    }
}
