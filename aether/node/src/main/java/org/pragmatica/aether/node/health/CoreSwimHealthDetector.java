package org.pragmatica.aether.node.health;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.consensus.topology.TopologyManagementMessage;
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
import org.pragmatica.swim.SwimMessage;
import org.pragmatica.swim.SwimProtocol;
import org.pragmatica.swim.SwimTransport;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.channel.EventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Bridges SWIM failure detection to cluster network connection management.
/// SWIM is the sole failure detector — cluster network Ping/Pong keepalive has been removed.
///
/// Cooperative model with QuicClusterNetwork (QCN):
/// - **SWIM -> QCN:** On member FAULTY/LEFT, routes DisconnectNode to close zombie QUIC connections
/// - **QCN -> SWIM:** On QUIC Hello handshake, onNodeConnected() resets FAULTY state
/// - **QCN owns:** quorum tracking, topology notifications, QUIC transport
/// - **SWIM owns:** failure detection via UDP probing (sole detector)
///
/// SWIM binds its own UDP port (cluster port + 100) for health detection probing.
public final class CoreSwimHealthDetector implements SwimMembershipListener {
    private static final Logger log = LoggerFactory.getLogger(CoreSwimHealthDetector.class);

    /// Core SWIM config — uses DEFAULT which is tuned for containerized environments.
    /// period=1s, probeTimeout=800ms, suspectTimeout=15s.
    private static final SwimConfig CORE_SWIM_CONFIG = SwimConfig.DEFAULT;

    /// Port offset from cluster port to SWIM port.
    /// Uses +100 to avoid conflicts in Forge/Ember where cluster ports are sequential
    /// (e.g., 6000-6004). With +1, node on port 6001 would conflict with SWIM port of
    /// node on port 6000.
    public static final int SWIM_PORT_OFFSET = 100;

    private final MessageRouter router;
    private final TopologyConfig topologyConfig;
    private final Serializer serializer;
    private final Deserializer deserializer;
    private volatile GossipEncryptor encryptor;
    private final AtomicReference<Option<SwimProtocol>> swimProtocol = new AtomicReference<>(none());
    private final AtomicReference<Option<SwimTransport>> swimTransport = new AtomicReference<>(none());
    private final AtomicInteger faultyCountInWindow = new AtomicInteger();
    private volatile long faultyWindowStart;
    private volatile boolean locallyDisconnected;

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
    /// SWIM port = cluster port + SWIM_PORT_OFFSET (100).
    /// Idempotent: if SWIM is already running, this is a no-op.
    public Promise<Unit> start() {
        return start(none(), GossipEncryptor.none());
    }

    /// Start the SWIM protocol with encryption, optionally reusing a shared EventLoopGroup.
    /// Encryptor is resolved at start time (quorum), not at construction — avoids race
    /// condition where certificate provider isn't initialized yet during node assembly.
    @SuppressWarnings({"JBCT-RET-01", "JBCT-RET-03", "JBCT-EX-01"})
    public Promise<Unit> start(Option<EventLoopGroup> sharedEventLoopGroup, GossipEncryptor gossipEncryptor) {
        this.encryptor = gossipEncryptor;
        if ( swimProtocol.get().isPresent()) {
            log.debug("SWIM already running, skipping start");
            return Promise.success(Unit.unit());
        }
        var selfPort = findSelfPort();
        var swimPort = selfPort + SWIM_PORT_OFFSET;
        var selfHost = findSelfHost();
        var selfAddress = new InetSocketAddress(selfHost, swimPort);
        return createTransport(sharedEventLoopGroup).flatMap(transport -> createAndStartProtocol(transport,
                                                                                                 selfAddress,
                                                                                                 swimPort))
                              .async()
                              .mapToUnit();
    }

    /// Stop the SWIM protocol and transport.
    @SuppressWarnings("JBCT-RET-01") // Lifecycle method — void inherent
    public void stop() {
        swimProtocol.getAndSet(none()).onPresent(SwimProtocol::stop);
        swimTransport.getAndSet(none()).onPresent(SwimTransport::stop);
    }

    /// Notify SWIM that a TCP connection was established to a peer.
    /// Resets FAULTY/SUSPECT state so SWIM can detect future departures.
    /// If the member was removed from SWIM during a mass-faulty event, re-adds it as a seed.
    /// A completed TCP Hello handshake is proof the node is alive.
    @SuppressWarnings("JBCT-RET-01") // Event callback — void inherent
    public void onNodeConnected(NodeId nodeId) {
        swimProtocol.get().onPresent(protocol -> readdOrMarkAlive(protocol, nodeId));
        clearLocalDisconnectFlag();
    }

    // ---- SwimMembershipListener (void callbacks required by interface) ----
    @Override
    @SuppressWarnings("JBCT-RET-01")
    public void onMemberJoined(SwimMember member) {
        log.info("SWIM member joined: {}", member.nodeId());
        clearLocalDisconnectFlag();
    }

    @Override
    @SuppressWarnings("JBCT-RET-01")
    public void onMemberSuspect(SwimMember member) {
        log.warn("SWIM member suspected: {}", member.nodeId());
    }

    @Override
    @SuppressWarnings("JBCT-RET-01")
    public void onMemberFaulty(SwimMember member) {
        if ( isLocalDisconnect(member)) {
        return;}
        log.error("SWIM member faulty: {}, routing DisconnectNode and RemoveNode", member.nodeId());
        router.routeAsync(() -> new NetworkServiceMessage.DisconnectNode(member.nodeId()));
        router.routeAsync(() -> new TopologyManagementMessage.RemoveNode(member.nodeId()));
    }

    @Override
    @SuppressWarnings("JBCT-RET-01")
    public void onMemberLeft(NodeId leftNodeId) {
        log.warn("SWIM member left: {}, routing DisconnectNode", leftNodeId);
        router.routeAsync(() -> new NetworkServiceMessage.DisconnectNode(leftNodeId));
    }

    /// Whether SWIM detected a local network disconnect (>50% peers FAULTY in one window).
    /// Exposed for testing and diagnostics.
    public boolean isLocallyDisconnected() {
        return locallyDisconnected;
    }

    // ---- Mass-faulty detection ----
    private boolean isLocalDisconnect(SwimMember member) {
        var now = System.currentTimeMillis();
        var suspectTimeoutMs = CORE_SWIM_CONFIG.suspectTimeout().millis();
        if ( now - faultyWindowStart > suspectTimeoutMs) {
            faultyCountInWindow.set(0);
            faultyWindowStart = now;
        }
        var faultyCount = faultyCountInWindow.incrementAndGet();
        var totalMembers = swimProtocol.get().map(p -> p.members().size())
                                           .or(0);
        if ( totalMembers > 0 && faultyCount > totalMembers / 2) {
            locallyDisconnected = true;
            log.warn("Local disconnect detected: {}/{} peers FAULTY within {}ms — suppressing topology drain for {}",
                     faultyCount,
                     totalMembers,
                     suspectTimeoutMs,
                     member.nodeId().id());
            return true;
        }
        return false;
    }

    @SuppressWarnings("JBCT-RET-01") // State mutation helper — void inherent
    private void clearLocalDisconnectFlag() {
        if ( locallyDisconnected) {
            locallyDisconnected = false;
            faultyCountInWindow.set(0);
            log.info("Network recovered from local disconnect");
        }
    }

    // ---- SWIM reconnect ----
    @SuppressWarnings("JBCT-RET-01") // Void callback delegating to protocol
    private void readdOrMarkAlive(SwimProtocol protocol, NodeId nodeId) {
        if ( protocol.members().containsKey(nodeId)) {
        protocol.markAlive(nodeId);} else
        {
        resolveSwimAddress(nodeId).onPresent(addr -> addAndLogSeedMember(protocol, nodeId, addr));}
    }

    @SuppressWarnings("JBCT-RET-01") // Logging side-effect helper
    private static void addAndLogSeedMember(SwimProtocol protocol, NodeId nodeId, InetSocketAddress addr) {
        protocol.addSeedMember(nodeId, addr);
        log.info("Re-added SWIM member {} at {} after disconnect recovery", nodeId.id(), addr);
    }

    private Option<InetSocketAddress> resolveSwimAddress(NodeId nodeId) {
        return Option.from(topologyConfig.coreNodes().stream()
                                                   .filter(node -> node.id().equals(nodeId))
                                                   .map(CoreSwimHealthDetector::toSwimAddress)
                                                   .findFirst());
    }

    private static InetSocketAddress toSwimAddress(NodeInfo node) {
        return InetSocketAddress.createUnresolved(node.address().host(),
                                                  node.address().port() + SWIM_PORT_OFFSET);
    }

    // ---- Internal ----
    private Result<SwimTransport> createTransport(Option<EventLoopGroup> sharedEventLoopGroup) {
        return sharedEventLoopGroup.map(group -> NettySwimTransport.nettySwimTransport(serializer,
                                                                                       deserializer,
                                                                                       encryptor,
                                                                                       group))
        .or(NettySwimTransport.nettySwimTransport(serializer, deserializer, encryptor));
    }

    /// Finds self NodeInfo from topology, wrapping java.util.Optional at adapter boundary.
    private Option<NodeInfo> findSelfNode() {
        return Option.from(topologyConfig.coreNodes().stream()
                                                   .filter(this::isSelf)
                                                   .findFirst());
    }

    private int findSelfPort() {
        return findSelfNode().map(n -> n.address().port())
                           .or(0);
    }

    private String findSelfHost() {
        return findSelfNode().map(n -> n.address().host())
                           .or("localhost");
    }

    private boolean isSelf(NodeInfo node) {
        return node.id().equals(topologyConfig.self());
    }

    @SuppressWarnings({"JBCT-RET-01", "JBCT-EX-01"})
    private Result<SwimProtocol> createAndStartProtocol(SwimTransport transport,
                                                        InetSocketAddress selfAddress,
                                                        int swimPort) {
        this.swimTransport.set(option(transport));
        transport.start(swimPort, this::delegateToProtocol).await(timeSpan(5).seconds())
                       .onFailure(cause -> log.error("SWIM transport failed to start: {}",
                                                     cause.message()));
        return SwimProtocol.swimProtocol(CORE_SWIM_CONFIG,
                                         transport,
                                         this,
                                         topologyConfig.self(),
                                         selfAddress).flatMap(SwimProtocol::start)
                                        .map(this::storeAndSeed);
    }

    private void delegateToProtocol(InetSocketAddress sender, SwimMessage message) {
        swimProtocol.get().onPresent(protocol -> protocol.onMessage(sender, message));
    }

    private SwimProtocol storeAndSeed(SwimProtocol protocol) {
        swimProtocol.set(option(protocol));
        seedMembers(protocol);
        return protocol;
    }

    private void seedMembers(SwimProtocol protocol) {
        topologyConfig.coreNodes().stream()
                                .filter(node -> !node.id().equals(topologyConfig.self()))
                                .forEach(node -> addSeedMember(protocol, node));
    }

    /// Store seed member with unresolved address — async DNS resolver handles resolution at send time.
    private static void addSeedMember(SwimProtocol protocol, NodeInfo node) {
        var host = node.address().host();
        var swimPort = node.address().port() + SWIM_PORT_OFFSET;
        // Store as UNRESOLVED — Netty's DnsNameResolver in NettySwimTransport resolves at send time.
        // This eliminates stale IPs and handles containers whose DNS entries appear after SWIM starts.
        var swimAddress = InetSocketAddress.createUnresolved(host, swimPort);
        protocol.addSeedMember(node.id(), swimAddress);
    }
}
