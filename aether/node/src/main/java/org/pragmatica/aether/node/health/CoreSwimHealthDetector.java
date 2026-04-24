// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.health;

import org.pragmatica.aether.node.health.fsm.SwimHealthContext;
import org.pragmatica.aether.node.health.fsm.SwimHealthEvents;
import org.pragmatica.aether.node.health.fsm.SwimHealthState;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.HealthSignalSink;
import org.pragmatica.cluster.metrics.PeerObservationBuffer;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.swim.GossipEncryptor;
import org.pragmatica.swim.NettySwimTransport;
import org.pragmatica.swim.SwimConfig;
import org.pragmatica.swim.SwimMember;
import org.pragmatica.swim.SwimMembershipListener;
import org.pragmatica.swim.SwimMessage;
import org.pragmatica.swim.SwimProtocol;
import org.pragmatica.swim.SwimTransport;

import io.netty.channel.EventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Bridges SWIM failure detection to cluster network connection management. SWIM is the sole
/// failure detector — cluster network Ping/Pong keepalive has been removed.
///
/// Cooperative model with QuicClusterNetwork (QCN):
/// - **SWIM -> QCN:** On member FAULTY/LEFT, routes DisconnectNode to close zombie QUIC connections
/// - **QCN -> SWIM:** On QUIC Hello handshake, [`#onNodeConnected`] resets FAULTY state
/// - **QCN owns:** quorum tracking, topology notifications, QUIC transport
/// - **SWIM owns:** failure detection via UDP probing (sole detector)
///
/// SWIM binds its own UDP port (cluster port + [`SwimHealthState#SWIM_PORT_OFFSET`]) for health
/// detection probing.
///
/// This class is a thin adapter: all lifecycle state and per-peer bookkeeping live in the
/// FSM ([`SwimHealthState`] + [`SwimHealthContext`]). Public methods translate external calls
/// into [`SwimHealthEvents`] dispatches; the FSM is the single source of truth for lifecycle
/// transitions (Stopped / Starting / Running / LocalDisconnect).
public final class CoreSwimHealthDetector implements SwimMembershipListener {
    private static final Logger log = LoggerFactory.getLogger(CoreSwimHealthDetector.class);

    private static final SwimConfig CORE_SWIM_CONFIG = SwimConfig.DEFAULT;

    public static final int SWIM_PORT_OFFSET = SwimHealthState.SWIM_PORT_OFFSET;

    private final SwimHealthContext context;

    private CoreSwimHealthDetector(SwimHealthContext context) {
        this.context = context;
    }

    public static CoreSwimHealthDetector coreSwimHealthDetector(MessageRouter router,
                                                                TopologyConfig topologyConfig,
                                                                Serializer serializer,
                                                                Deserializer deserializer) {
        return coreSwimHealthDetector(router, topologyConfig, serializer, deserializer,
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
        return coreSwimHealthDetector(router, topologyConfig, serializer, deserializer,
                                      signalSink, epochSupplier,
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
        var buffer = observationBuffer == null ? PeerObservationBuffer.NOOP : observationBuffer;
        var ctxHolder = new AtomicReference<SwimHealthContext>();
        Function<Fsm<SwimHealthState, SwimHealthEvents>, SwimHealthState> initialStateFactory =
                fsm -> buildContextAndStopped(fsm, ctxHolder, router, topologyConfig, serializer,
                                              deserializer, signalSink, epochSupplier,
                                              isLeaderSupplier, buffer);
        Fsm.fsm("swim-health", topologyConfig.self().id(), initialStateFactory);
        return new CoreSwimHealthDetector(ctxHolder.get());
    }

    private static SwimHealthState buildContextAndStopped(Fsm<SwimHealthState, SwimHealthEvents> fsm,
                                                          AtomicReference<SwimHealthContext> ctxHolder,
                                                          MessageRouter router,
                                                          TopologyConfig topologyConfig,
                                                          Serializer serializer,
                                                          Deserializer deserializer,
                                                          HealthSignalSink signalSink,
                                                          Supplier<Epoch> epochSupplier,
                                                          BooleanSupplier isLeaderSupplier,
                                                          PeerObservationBuffer buffer) {
        var ctx = new SwimHealthContext(fsm, router, topologyConfig, serializer, deserializer,
                                        signalSink, epochSupplier, isLeaderSupplier, buffer,
                                        CORE_SWIM_CONFIG);
        ctxHolder.set(ctx);
        return ctx.stopped();
    }

    public Promise<Unit> start() {
        return start(none(), GossipEncryptor.none());
    }

    @SuppressWarnings({"JBCT-RET-01", "JBCT-EX-01"})
    public Promise<Unit> start(Option<EventLoopGroup> sharedEventLoopGroup,
                               GossipEncryptor gossipEncryptor) {
        // Always dispatch — the FSM's Stopped.handle(StartRequested) decides whether to transition
        // to Starting; any other state ignores the event. Removing the external TOCTOU guards
        // keeps the FSM as the single source of truth.
        context.dispatch(new SwimHealthEvents.StartRequested());
        var selfPort = findSelfPort();
        var swimPort = selfPort + SWIM_PORT_OFFSET;
        var selfHost = findSelfHost();
        var selfAddress = new InetSocketAddress(selfHost, swimPort);
        return createTransport(sharedEventLoopGroup, gossipEncryptor)
                .flatMap(transport -> createAndStartProtocol(transport, selfAddress, swimPort, gossipEncryptor))
                .async()
                .onSuccess(context::dispatch)
                .onFailure(_ -> context.dispatch(new SwimHealthEvents.StartFailed()))
                .mapToUnit();
    }

    @Contract
    public void stop() {
        context.dispatch(new SwimHealthEvents.StopRequested());
    }

    public SwimHealthState lifecycleState() {
        return context.fsm().current();
    }

    @Contract
    public void onNodeConnected(NodeId nodeId) {
        context.dispatch(new SwimHealthEvents.PeerConnected(nodeId, none()));
    }

    @Contract
    public void onNodeConnected(NodeInfo peer) {
        context.dispatch(new SwimHealthEvents.PeerConnected(peer.id(), option(peer)));
    }

    @Override
    @Contract
    public void onMemberJoined(SwimMember member) {
        context.dispatch(new SwimHealthEvents.PeerJoined(member));
    }

    @Override
    @Contract
    public void onMemberSuspect(SwimMember member) {
        log.warn("SWIM member suspected: {}", member.nodeId());
        context.dispatch(new SwimHealthEvents.PeerSuspect(member));
    }

    @Override
    @Contract
    public void onMemberFaulty(SwimMember member) {
        context.dispatch(new SwimHealthEvents.PeerFaulty(member));
    }

    @Override
    @Contract
    public void onMemberLeft(NodeId leftNodeId) {
        context.dispatch(new SwimHealthEvents.PeerLeft(leftNodeId));
    }

    /// Update the authoritative leader snapshot on the FSM's `Running` / `LocalDisconnect` state.
    /// Callers should invoke this whenever [`LeaderNotification.LeaderChange`] fires so the
    /// follower FAULTY path (see [`SwimHealthState.Running`]) can correctly detect
    /// "faulty peer IS current leader" via `state.currentLeader` — no external atomic reads
    /// during event handling.
    @Contract
    public void onLeaderChanged(Option<NodeId> leader) {
        context.dispatch(new SwimHealthEvents.LeaderChanged(leader));
    }

    public boolean isLocallyDisconnected() {
        return context.fsm().current() instanceof SwimHealthState.LocalDisconnect;
    }

    // --- Internals for start() I/O pipeline ---

    private Result<SwimTransport> createTransport(Option<EventLoopGroup> sharedEventLoopGroup,
                                                  GossipEncryptor encryptor) {
        return sharedEventLoopGroup.map(group -> NettySwimTransport.nettySwimTransport(context.serializer(),
                                                                                       context.deserializer(),
                                                                                       encryptor, group))
                                   .or(NettySwimTransport.nettySwimTransport(context.serializer(),
                                                                             context.deserializer(),
                                                                             encryptor));
    }

    @SuppressWarnings({"JBCT-RET-01", "JBCT-EX-01"})
    private Result<SwimHealthEvents.ProtocolReady> createAndStartProtocol(SwimTransport transport,
                                                                          InetSocketAddress selfAddress,
                                                                          int swimPort,
                                                                          GossipEncryptor encryptor) {
        return transport.start(swimPort, (sender, message) -> deliverToProtocol(sender, message))
                        .await(timeSpan(5).seconds())
                        .onFailure(cause -> log.error("SWIM transport failed to start: {}", cause.message()))
                        .flatMap(_ -> SwimProtocol.swimProtocol(CORE_SWIM_CONFIG, transport, this,
                                                                context.topologyConfig().self(), selfAddress))
                        .flatMap(SwimProtocol::start)
                        .map(protocol -> seedAndWrap(protocol, transport, encryptor));
    }

    private SwimHealthEvents.ProtocolReady seedAndWrap(SwimProtocol protocol,
                                                       SwimTransport transport,
                                                       GossipEncryptor encryptor) {
        seedMembers(protocol);
        return new SwimHealthEvents.ProtocolReady(protocol, transport, encryptor);
    }

    private void seedMembers(SwimProtocol protocol) {
        context.topologyConfig().coreNodes().stream()
               .filter(node -> !node.id().equals(context.topologyConfig().self()))
               .forEach(node -> addSeedMember(protocol, node));
    }

    private static void addSeedMember(SwimProtocol protocol, NodeInfo node) {
        var host = node.address().host();
        var swimPort = node.address().port() + SWIM_PORT_OFFSET;
        var swimAddress = InetSocketAddress.createUnresolved(host, swimPort);
        protocol.addSeedMember(node.id(), swimAddress);
    }

    /// Route an inbound SWIM datagram to the live protocol, if present. During `Starting` the
    /// protocol does not yet exist on the state record — inbound datagrams are silently dropped
    /// (SWIM's retry is authoritative).
    private void deliverToProtocol(InetSocketAddress sender, SwimMessage message) {
        if (context.fsm().current() instanceof SwimHealthState.Running running) {
            running.swim().onMessage(sender, message);
            return;
        }
        if (context.fsm().current() instanceof SwimHealthState.LocalDisconnect ld) {
            ld.swim().onMessage(sender, message);
        }
    }

    // --- Topology lookup helpers (moved here; context exposes findSelfNode) ---

    private int findSelfPort() {
        return context.findSelfNode().map(n -> n.address().port()).or(0);
    }

    private String findSelfHost() {
        return context.findSelfNode().map(n -> n.address().host()).or("localhost");
    }

}
