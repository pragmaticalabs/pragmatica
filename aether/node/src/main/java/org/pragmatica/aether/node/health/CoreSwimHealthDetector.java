// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.health;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.HealthHint;
import org.pragmatica.aether.slice.generation.HealthSignal;
import org.pragmatica.aether.slice.generation.HealthSignalSink;
import org.pragmatica.cluster.metrics.HealthHintWire;
import org.pragmatica.cluster.metrics.PeerHealthObservation;
import org.pragmatica.cluster.metrics.PeerObservationBuffer;
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
import org.pragmatica.swim.SwimMessage;
import org.pragmatica.swim.SwimProtocol;
import org.pragmatica.swim.SwimTransport;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

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

    private static final SwimConfig CORE_SWIM_CONFIG = SwimConfig.DEFAULT;

    public static final int SWIM_PORT_OFFSET = 100;

    private final MessageRouter router;
    private final TopologyConfig topologyConfig;
    private final Serializer serializer;
    private final Deserializer deserializer;
    private final HealthSignalSink signalSink;
    private final Supplier<Epoch> epochSupplier;
    private final BooleanSupplier isLeaderSupplier;
    private final PeerObservationBuffer observationBuffer;
    private volatile GossipEncryptor encryptor;

    private final AtomicReference<Option<SwimProtocol>> swimProtocol = new AtomicReference<>(none());

    private final AtomicReference<Option<SwimTransport>> swimTransport = new AtomicReference<>(none());

    private final AtomicBoolean starting = new AtomicBoolean(false);

    private final AtomicInteger faultyCountInWindow = new AtomicInteger();

    private volatile long faultyWindowStart;
    private volatile boolean locallyDisconnected;

    private CoreSwimHealthDetector(MessageRouter router,
                                   TopologyConfig topologyConfig,
                                   Serializer serializer,
                                   Deserializer deserializer,
                                   HealthSignalSink signalSink,
                                   Supplier<Epoch> epochSupplier,
                                   BooleanSupplier isLeaderSupplier,
                                   PeerObservationBuffer observationBuffer) {
        this.router = router;
        this.topologyConfig = topologyConfig;
        this.serializer = serializer;
        this.deserializer = deserializer;
        this.signalSink = signalSink;
        this.epochSupplier = epochSupplier;
        this.isLeaderSupplier = isLeaderSupplier;
        this.observationBuffer = observationBuffer == null
                                ? PeerObservationBuffer.NOOP
                                : observationBuffer;
        this.encryptor = GossipEncryptor.none();
    }

    public static CoreSwimHealthDetector coreSwimHealthDetector(MessageRouter router,
                                                                TopologyConfig topologyConfig,
                                                                Serializer serializer,
                                                                Deserializer deserializer) {
        return new CoreSwimHealthDetector(router,
                                          topologyConfig,
                                          serializer,
                                          deserializer,
                                          HealthSignalSink.noop(),
                                          () -> Epoch.ZERO,
                                          () -> true,
                                          PeerObservationBuffer.NOOP);
    }

    public static CoreSwimHealthDetector coreSwimHealthDetector(MessageRouter router,
                                                                TopologyConfig topologyConfig,
                                                                Serializer serializer,
                                                                Deserializer deserializer,
                                                                HealthSignalSink signalSink,
                                                                Supplier<Epoch> epochSupplier) {
        return new CoreSwimHealthDetector(router,
                                          topologyConfig,
                                          serializer,
                                          deserializer,
                                          signalSink,
                                          epochSupplier,
                                          () -> true,
                                          PeerObservationBuffer.NOOP);
    }

    public static CoreSwimHealthDetector coreSwimHealthDetector(MessageRouter router,
                                                                TopologyConfig topologyConfig,
                                                                Serializer serializer,
                                                                Deserializer deserializer,
                                                                HealthSignalSink signalSink,
                                                                Supplier<Epoch> epochSupplier,
                                                                BooleanSupplier isLeaderSupplier,
                                                                PeerObservationBuffer observationBuffer) {
        return new CoreSwimHealthDetector(router,
                                          topologyConfig,
                                          serializer,
                                          deserializer,
                                          signalSink,
                                          epochSupplier,
                                          isLeaderSupplier,
                                          observationBuffer);
    }

    public Promise<Unit> start() {
        return start(none(), GossipEncryptor.none());
    }

    @SuppressWarnings({"JBCT-RET-01", "JBCT-EX-01"}) public Promise<Unit> start(Option<EventLoopGroup> sharedEventLoopGroup,
                                                                                GossipEncryptor gossipEncryptor) {
        this.encryptor = gossipEncryptor;
        if (!starting.compareAndSet(false, true)) {
            log.debug("SWIM start already in progress, skipping");
            return Promise.success(Unit.unit());
        }
        if (swimProtocol.get().isPresent()) {
            starting.set(false);
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
                              .onFailure(_ -> starting.set(false))
                              .mapToUnit();
    }

    @SuppressWarnings("JBCT-RET-01") public void stop() {
        swimProtocol.getAndSet(none()).onPresent(SwimProtocol::stop);
        swimTransport.getAndSet(none()).onPresent(SwimTransport::stop);
    }

    @SuppressWarnings("JBCT-RET-01") public void onNodeConnected(NodeId nodeId) {
        swimProtocol.get().onPresent(protocol -> readdOrMarkAlive(protocol, nodeId));
        clearLocalDisconnectFlag();
        reportHint(nodeId, HealthHint.HEALTHY);
    }

    @SuppressWarnings("JBCT-RET-01") public void onNodeConnected(NodeInfo peer) {
        swimProtocol.get()
                        .onPresent(protocol -> {
                                       var nodeId = peer.id();
                                       if (protocol.members().containsKey(nodeId)) {protocol.markAlive(nodeId);} else {addAndLogSeedMember(protocol,
                                                                                                                                           nodeId,
                                                                                                                                           toSwimAddress(peer));}
                                   });
        clearLocalDisconnectFlag();
        reportHint(peer.id(), HealthHint.HEALTHY);
    }

    @Override@SuppressWarnings("JBCT-RET-01") public void onMemberJoined(SwimMember member) {
        log.info("SWIM member joined: {}", member.nodeId());
        clearLocalDisconnectFlag();
        reportHint(member.nodeId(), HealthHint.HEALTHY);
    }

    @Override@SuppressWarnings("JBCT-RET-01") public void onMemberSuspect(SwimMember member) {
        log.warn("SWIM member suspected: {}", member.nodeId());
        reportHint(member.nodeId(), HealthHint.SUSPECTED);
    }

    @Override@SuppressWarnings("JBCT-RET-01") public void onMemberFaulty(SwimMember member) {
        if (!isLeaderSupplier.getAsBoolean()) {
            log.warn("SWIM member faulty: {} — follower sensor, buffering observation upstream", member.nodeId());
            bufferHealthObservation(member.nodeId(), HealthHint.FAULTY);
            return;
        }
        if (isLocalDisconnect(member)) {return;}
        log.error("SWIM member faulty: {}, routing DisconnectNode", member.nodeId());
        router.routeAsync(() -> new NetworkServiceMessage.DisconnectNode(member.nodeId()));
        emitLeaderHint(member.nodeId(), HealthHint.FAULTY);
    }

    @Override@SuppressWarnings("JBCT-RET-01") public void onMemberLeft(NodeId leftNodeId) {
        if (!isLeaderSupplier.getAsBoolean()) {
            log.warn("SWIM member left: {} — follower sensor, buffering FAULTY observation upstream", leftNodeId);
            bufferHealthObservation(leftNodeId, HealthHint.FAULTY);
            return;
        }
        log.warn("SWIM member left: {}, routing DisconnectNode", leftNodeId);
        router.routeAsync(() -> new NetworkServiceMessage.DisconnectNode(leftNodeId));
        emitLeaderHint(leftNodeId, HealthHint.FAULTY);
    }

    /// Followers buffer observations upstream via `ClusterSyncPong`; leaders
    /// fire the hint directly into their local `HealthReconciler`.
    private void reportHint(NodeId nodeId, HealthHint hint) {
        if (isLeaderSupplier.getAsBoolean()) {
            emitLeaderHint(nodeId, hint);
        } else {
            bufferHealthObservation(nodeId, hint);
        }
    }

    private void emitLeaderHint(NodeId nodeId, HealthHint hint) {
        signalSink.emit(new HealthSignal.SwimHint(nodeId, hint, epochSupplier.get()));
    }

    private void bufferHealthObservation(NodeId nodeId, HealthHint hint) {
        var epoch = epochSupplier.get();
        observationBuffer.pushHealth(new PeerHealthObservation(nodeId,
                                                               toWire(hint),
                                                               epoch.rabiaTerm(),
                                                               epoch.localCounter()));
    }

    private static HealthHintWire toWire(HealthHint hint) {
        return switch (hint) {
            case HEALTHY -> HealthHintWire.HEALTHY;
            case SUSPECTED -> HealthHintWire.SUSPECTED;
            case FAULTY -> HealthHintWire.FAULTY;
        };
    }

    public boolean isLocallyDisconnected() {
        return locallyDisconnected;
    }

    private boolean isLocalDisconnect(SwimMember member) {
        var now = System.currentTimeMillis();
        var suspectTimeoutMs = CORE_SWIM_CONFIG.suspectTimeout().millis();
        if (now - faultyWindowStart > suspectTimeoutMs) {
            faultyCountInWindow.set(0);
            faultyWindowStart = now;
        }
        var faultyCount = faultyCountInWindow.incrementAndGet();
        var totalMembers = swimProtocol.get().map(p -> p.members().size())
                                           .or(0);
        if (totalMembers > 0 && faultyCount > totalMembers / 2) {
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

    @SuppressWarnings("JBCT-RET-01") private void clearLocalDisconnectFlag() {
        if (locallyDisconnected) {
            locallyDisconnected = false;
            faultyCountInWindow.set(0);
            log.info("Network recovered from local disconnect");
        }
    }

    @SuppressWarnings("JBCT-RET-01") private void readdOrMarkAlive(SwimProtocol protocol, NodeId nodeId) {
        if (protocol.members().containsKey(nodeId)) {protocol.markAlive(nodeId);} else {resolveSwimAddress(nodeId).onPresent(addr -> addAndLogSeedMember(protocol,
                                                                                                                                                         nodeId,
                                                                                                                                                         addr));}
    }

    @SuppressWarnings("JBCT-RET-01") private static void addAndLogSeedMember(SwimProtocol protocol,
                                                                             NodeId nodeId,
                                                                             InetSocketAddress addr) {
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

    private Result<SwimTransport> createTransport(Option<EventLoopGroup> sharedEventLoopGroup) {
        return sharedEventLoopGroup.map(group -> NettySwimTransport.nettySwimTransport(serializer,
                                                                                       deserializer,
                                                                                       encryptor,
                                                                                       group))
        .or(NettySwimTransport.nettySwimTransport(serializer, deserializer, encryptor));
    }

    private Option<NodeInfo> findSelfNode() {
        return Option.from(topologyConfig.coreNodes().stream()
                                                   .filter(this::isSelf)
                                                   .findFirst());
    }

    private int findSelfPort() {
        return findSelfNode().map(n -> n.address().port()).or(0);
    }

    private String findSelfHost() {
        return findSelfNode().map(n -> n.address().host()).or("localhost");
    }

    private boolean isSelf(NodeInfo node) {
        return node.id().equals(topologyConfig.self());
    }

    @SuppressWarnings({"JBCT-RET-01", "JBCT-EX-01"}) private Result<SwimProtocol> createAndStartProtocol(SwimTransport transport,
                                                                                                         InetSocketAddress selfAddress,
                                                                                                         int swimPort) {
        this.swimTransport.set(option(transport));
        return transport.start(swimPort, this::delegateToProtocol).await(timeSpan(5).seconds())
                              .onFailure(cause -> {
                                             log.error("SWIM transport failed to start: {}",
                                                       cause.message());
                                             this.swimTransport.set(none());
                                         })
                              .flatMap(_ -> SwimProtocol.swimProtocol(CORE_SWIM_CONFIG,
                                                                      transport,
                                                                      this,
                                                                      topologyConfig.self(),
                                                                      selfAddress))
                              .flatMap(SwimProtocol::start)
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

    private static void addSeedMember(SwimProtocol protocol, NodeInfo node) {
        var host = node.address().host();
        var swimPort = node.address().port() + SWIM_PORT_OFFSET;
        var swimAddress = InetSocketAddress.createUnresolved(host, swimPort);
        protocol.addSeedMember(node.id(), swimAddress);
    }
}
