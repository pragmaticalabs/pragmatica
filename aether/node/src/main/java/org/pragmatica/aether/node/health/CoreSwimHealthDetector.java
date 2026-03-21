package org.pragmatica.aether.node.health;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
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

import io.netty.channel.EventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Bridges SWIM failure detection to NCN channel management.
/// SWIM is the sole failure detector — NCN's Ping/Pong keepalive has been removed.
///
/// Cooperative model with NettyClusterNetwork (NCN):
/// - **SWIM → NCN:** On member FAULTY/LEFT, routes DisconnectNode to close zombie TCP channels
/// - **NCN → SWIM:** On TCP Hello handshake, onNodeConnected() resets FAULTY state
/// - **NCN owns:** quorum tracking, topology notifications, TCP transport
/// - **SWIM owns:** failure detection via UDP probing (sole detector)
///
/// Thread pool sharing: when started with a shared EventLoopGroup (from Server's workerGroup),
/// SWIM's UDP channel runs on the same thread pool as NCN's TCP channels. This eliminates
/// the separate NioEventLoopGroup(1) that SWIM previously created. SWIM still binds its own
/// UDP port — future work will move the UDP binding into Server itself.
@SuppressWarnings({"JBCT-RET-01", "JBCT-RET-03", "JBCT-EX-01"})
public final class CoreSwimHealthDetector implements SwimMembershipListener {
    private static final Logger log = LoggerFactory.getLogger(CoreSwimHealthDetector.class);

    /// Core SWIM config — uses DEFAULT which is tuned for containerized environments.
    /// period=1s, probeTimeout=800ms, suspectTimeout=15s.
    private static final SwimConfig CORE_SWIM_CONFIG = SwimConfig.DEFAULT;

    private final MessageRouter router;
    private final TopologyConfig topologyConfig;
    private final Serializer serializer;
    private final Deserializer deserializer;
    private volatile GossipEncryptor encryptor;
    private volatile SwimProtocol swimProtocol;
    private volatile SwimTransport swimTransport;

    private CoreSwimHealthDetector(MessageRouter router,
                                   TopologyConfig topologyConfig,
                                   Serializer serializer,
                                   Deserializer deserializer) {
        this.router = router;
        this.topologyConfig = topologyConfig;
        this.serializer = serializer;
        this.deserializer = deserializer;
        this.encryptor = GossipEncryptor.none();
    }

    /// Factory method.
    public static CoreSwimHealthDetector coreSwimHealthDetector(MessageRouter router,
                                                                TopologyConfig topologyConfig,
                                                                Serializer serializer,
                                                                Deserializer deserializer) {
        return new CoreSwimHealthDetector(router, topologyConfig, serializer, deserializer);
    }

    /// Start the SWIM protocol for core node health detection.
    /// SWIM port = cluster port + 1.
    /// Idempotent: if SWIM is already running, this is a no-op.
    public Promise<Unit> start() {
        return start(Option.empty(), GossipEncryptor.none());
    }

    /// Start the SWIM protocol with encryption, optionally reusing a shared EventLoopGroup.
    /// Encryptor is resolved at start time (quorum), not at construction — avoids race
    /// condition where certificate provider isn't initialized yet during node assembly.
    public Promise<Unit> start(Option<EventLoopGroup> sharedEventLoopGroup, GossipEncryptor gossipEncryptor) {
        this.encryptor = gossipEncryptor;
        if (swimProtocol != null) {
            log.debug("SWIM already running, skipping start");
            return Promise.success(Unit.unit());
        }
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
        return createTransport(sharedEventLoopGroup).flatMap(transport -> createAndStartProtocol(transport,
                                                                                                 selfAddress,
                                                                                                 swimPort))
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

    /// Notify SWIM that a TCP connection was established to a peer.
    /// Resets FAULTY/SUSPECT state so SWIM can detect future departures.
    /// A completed TCP Hello handshake is proof the node is alive.
    public void onNodeConnected(NodeId nodeId) {
        var protocol = swimProtocol;
        if (protocol != null) {
            protocol.markAlive(nodeId);
        }
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
        log.error("SWIM member faulty: {}, routing DisconnectNode", member.nodeId());
        router.routeAsync(() -> new NetworkServiceMessage.DisconnectNode(member.nodeId()));
    }

    @Override
    public void onMemberLeft(NodeId leftNodeId) {
        log.warn("SWIM member left: {}, routing DisconnectNode", leftNodeId);
        router.routeAsync(() -> new NetworkServiceMessage.DisconnectNode(leftNodeId));
    }

    // ---- Internal ----
    private Result<SwimTransport> createTransport(Option<EventLoopGroup> sharedEventLoopGroup) {
        return sharedEventLoopGroup.map(group -> NettySwimTransport.nettySwimTransport(serializer,
                                                                                       deserializer,
                                                                                       encryptor,
                                                                                       group))
                                   .or(NettySwimTransport.nettySwimTransport(serializer, deserializer, encryptor));
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
    private Result<SwimProtocol> createAndStartProtocol(SwimTransport transport,
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
}
