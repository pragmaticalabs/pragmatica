// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.health;

import org.pragmatica.aether.metrics.observation.PeerObservationStore;
import org.pragmatica.aether.node.health.fsm.SwimHealthContext;
import org.pragmatica.aether.node.health.fsm.SwimHealthEvents;
import org.pragmatica.aether.node.health.fsm.SwimHealthState;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.HealthSignalSink;
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
import org.pragmatica.swim.HealthSnapshot;
import org.pragmatica.swim.NettySwimTransport;
import org.pragmatica.swim.SwimConfig;
import org.pragmatica.swim.SwimHealth;
import org.pragmatica.swim.SwimMember;
import org.pragmatica.swim.SwimMembershipListener;
import org.pragmatica.swim.SwimMessage;
import org.pragmatica.swim.SwimObservation;
import org.pragmatica.swim.SwimProtocol;
import org.pragmatica.swim.SwimTransport;
import org.pragmatica.swim.TransportObservation;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import io.netty.channel.EventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


public final class CoreSwimHealthDetector implements SwimMembershipListener {
    private static final Logger log = LoggerFactory.getLogger(CoreSwimHealthDetector.class);
    public static final int SWIM_PORT_OFFSET = SwimHealthState.SWIM_PORT_OFFSET;

    private final SwimHealthContext context;
    private final SwimConfig swimConfig;

    private final List<Consumer<SwimObservation>> pendingObservationListeners = new CopyOnWriteArrayList<>();

    /// Transport-observation emitters pending wiring into the underlying [`SwimProtocol`]
    /// once it is created in `seedAndWrap`. Bridges SWIM's protocol-level FAULTY edge
    /// (the only edge SWIM emits to the cluster-wide `TransportObservation` stream) to
    /// the cluster `MessageRouter`. Wired by the Aether node assembly.
    private final List<SwimProtocol.TransportObservationEmitter> pendingTransportObservationEmitters = new CopyOnWriteArrayList<>();

    private volatile Option<AnnounceJoinCall> pendingAnnounceJoin = none();

    private record AnnounceJoinCall(NodeInfo self,
                                    String clusterName,
                                    long incarnation,
                                    List<InetSocketAddress> seeds) {}

    private CoreSwimHealthDetector(SwimHealthContext context, SwimConfig swimConfig) {
        this.context = context;
        this.swimConfig = swimConfig;
    }

    public static CoreSwimHealthDetector coreSwimHealthDetector(MessageRouter router,
                                                                TopologyConfig topologyConfig,
                                                                Serializer serializer,
                                                                Deserializer deserializer) {
        return coreSwimHealthDetector(router,
                                      topologyConfig,
                                      serializer,
                                      deserializer,
                                      HealthSignalSink.noop(),
                                      () -> Epoch.ZERO,
                                      () -> true,
                                      PeerObservationStore.peerObservationStore(),
                                      SwimConfig.DEFAULT);
    }

    public static CoreSwimHealthDetector coreSwimHealthDetector(MessageRouter router,
                                                                TopologyConfig topologyConfig,
                                                                Serializer serializer,
                                                                Deserializer deserializer,
                                                                HealthSignalSink signalSink,
                                                                Supplier<Epoch> epochSupplier) {
        return coreSwimHealthDetector(router,
                                      topologyConfig,
                                      serializer,
                                      deserializer,
                                      signalSink,
                                      epochSupplier,
                                      () -> true,
                                      PeerObservationStore.peerObservationStore(),
                                      SwimConfig.DEFAULT);
    }

    public static CoreSwimHealthDetector coreSwimHealthDetector(MessageRouter router,
                                                                TopologyConfig topologyConfig,
                                                                Serializer serializer,
                                                                Deserializer deserializer,
                                                                HealthSignalSink signalSink,
                                                                Supplier<Epoch> epochSupplier,
                                                                BooleanSupplier isLeaderSupplier,
                                                                PeerObservationStore observationStore) {
        return coreSwimHealthDetector(router,
                                      topologyConfig,
                                      serializer,
                                      deserializer,
                                      signalSink,
                                      epochSupplier,
                                      isLeaderSupplier,
                                      observationStore,
                                      SwimConfig.DEFAULT);
    }

    public static CoreSwimHealthDetector coreSwimHealthDetector(MessageRouter router,
                                                                TopologyConfig topologyConfig,
                                                                Serializer serializer,
                                                                Deserializer deserializer,
                                                                HealthSignalSink signalSink,
                                                                Supplier<Epoch> epochSupplier,
                                                                BooleanSupplier isLeaderSupplier,
                                                                PeerObservationStore observationStore,
                                                                SwimConfig swimConfig) {
        return coreSwimHealthDetector(router,
                                      topologyConfig,
                                      serializer,
                                      deserializer,
                                      signalSink,
                                      epochSupplier,
                                      isLeaderSupplier,
                                      observationStore,
                                      swimConfig,
                                      () -> true);
    }

    /// Phase-aware overload (D.3 three-phase model, 2026-05-11). `isBootingSupplier`
    /// is the cluster's `ClusterPhaseView.compute() == ClusterPhase.COLD_BOOT` projection
    /// wired from `AetherNode`. When `false` (NORMAL or RECOVERING), SWIM emits
    /// `FaultyObserved` regardless of per-peer `everSeenHealthy`.
    public static CoreSwimHealthDetector coreSwimHealthDetector(MessageRouter router,
                                                                TopologyConfig topologyConfig,
                                                                Serializer serializer,
                                                                Deserializer deserializer,
                                                                HealthSignalSink signalSink,
                                                                Supplier<Epoch> epochSupplier,
                                                                BooleanSupplier isLeaderSupplier,
                                                                PeerObservationStore observationStore,
                                                                SwimConfig swimConfig,
                                                                BooleanSupplier isBootingSupplier) {
        return coreSwimHealthDetector(router,
                                      topologyConfig,
                                      serializer,
                                      deserializer,
                                      signalSink,
                                      epochSupplier,
                                      isLeaderSupplier,
                                      observationStore,
                                      swimConfig,
                                      isBootingSupplier,
                                      _ -> {});
    }

    public static CoreSwimHealthDetector coreSwimHealthDetector(MessageRouter router,
                                                                TopologyConfig topologyConfig,
                                                                Serializer serializer,
                                                                Deserializer deserializer,
                                                                HealthSignalSink signalSink,
                                                                Supplier<Epoch> epochSupplier,
                                                                BooleanSupplier isLeaderSupplier,
                                                                PeerObservationStore observationStore,
                                                                SwimConfig swimConfig,
                                                                BooleanSupplier isBootingSupplier,
                                                                java.util.function.Consumer<NodeId> faultyLeaderEvictor) {
        var ctxHolder = new AtomicReference<SwimHealthContext>();
        Function<Fsm<SwimHealthState, SwimHealthEvents>, SwimHealthState> initialStateFactory = fsm -> buildContextAndStopped(fsm,
                                                                                                                              ctxHolder,
                                                                                                                              router,
                                                                                                                              topologyConfig,
                                                                                                                              serializer,
                                                                                                                              deserializer,
                                                                                                                              signalSink,
                                                                                                                              epochSupplier,
                                                                                                                              isLeaderSupplier,
                                                                                                                              observationStore,
                                                                                                                              swimConfig,
                                                                                                                              isBootingSupplier,
                                                                                                                              faultyLeaderEvictor);
        Fsm.fsm("swim-health",
                topologyConfig.self().id(),
                initialStateFactory);

        return new CoreSwimHealthDetector(ctxHolder.get(), swimConfig);
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
                                                          PeerObservationStore observationStore,
                                                          SwimConfig swimConfig,
                                                          BooleanSupplier isBootingSupplier,
                                                          java.util.function.Consumer<NodeId> faultyLeaderEvictor) {
        var ctx = new SwimHealthContext(fsm,
                                        router,
                                        topologyConfig,
                                        serializer,
                                        deserializer,
                                        signalSink,
                                        epochSupplier,
                                        isLeaderSupplier,
                                        observationStore,
                                        swimConfig,
                                        System::currentTimeMillis,
                                        isBootingSupplier,
                                        faultyLeaderEvictor);
        ctxHolder.set(ctx);

        return ctx.stopped();
    }

    public Promise<Unit> start() {
        return start(none(), GossipEncryptor.none());
    }

    @SuppressWarnings({"JBCT-RET-01", "JBCT-EX-01"})
    public Promise<Unit> start(Option<EventLoopGroup> sharedEventLoopGroup, GossipEncryptor gossipEncryptor) {
        context.dispatch(new SwimHealthEvents.StartRequested());
        var selfPort = findSelfPort();
        var swimPort = selfPort + SWIM_PORT_OFFSET;
        var selfHost = findSelfHost();
        var selfAddress = new InetSocketAddress(selfHost, swimPort);

        return createTransport(sharedEventLoopGroup, gossipEncryptor).flatMap(transport -> createAndStartProtocol(transport,
                                                                                                                  selfAddress,
                                                                                                                  swimPort,
                                                                                                                  gossipEncryptor))
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
        return context.fsm()
                      .current();
    }

    SwimHealthContext contextForTest() {
        return context;
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

    @Contract
    public void onLeaderChanged(Option<NodeId> leader) {
        context.dispatch(new SwimHealthEvents.LeaderChanged(leader));
    }

    @Contract
    public void recordTransportHint(TransportObservation hint) {
        protocol().onPresent(p -> p.recordTransportHint(hint.peer(), hint));
    }

    @Contract
    public void addObservationListener(Consumer<SwimObservation> consumer) {
        pendingObservationListeners.add(consumer);
        protocol().onPresent(p -> p.addObservationListener(consumer));
    }

    @Contract
    public void announceJoin(NodeInfo self,
                             String clusterName,
                             long incarnation,
                             List<InetSocketAddress> seeds) {
        pendingAnnounceJoin = option(new AnnounceJoinCall(self, clusterName, incarnation, seeds));
        protocol().onPresent(p -> p.announceJoin(self, clusterName, incarnation, seeds));
    }

    public SwimHealth healthOf(NodeId nodeId) {
        return protocol().map(p -> p.healthOf(nodeId))
                       .or(SwimHealth.UNKNOWN);
    }

    /// Test-only: toggle silent-death fault injection on the SWIM UDP transport plane.
    /// Forwards to the running [`SwimTransport`] so a black-holed node goes silent on the
    /// SWIM plane in addition to QUIC, reproducing genuine silent death across both planes.
    @Contract
    public void setSwimBlackholed(boolean enabled) {
        transport().onPresent(t -> t.blackhole(enabled));
    }

    private Option<SwimTransport> transport() {
        return switch (context.fsm()
                              .current()) {
            case SwimHealthState.Running r -> option(r.transport());
            default -> none();
        };
    }

    /// Register a cluster-wide [`org.pragmatica.consensus.topology.TransportObservation`] emitter.
    /// Invoked from `SwimProtocol.emitFaultyOrUnknown` whenever a peer transitions to FAULTY,
    /// producing `TransportObservation.PeerObservedFaulty` for the cluster `MessageRouter`.
    /// Buffered until the underlying [`SwimProtocol`] is constructed (start path).
    @Contract
    public void addTransportObservationEmitter(SwimProtocol.TransportObservationEmitter emitter) {
        pendingTransportObservationEmitters.add(emitter);
        protocol().onPresent(p -> p.addTransportObservationEmitter(emitter));
    }

    public Option<HealthSnapshot> currentHealth() {
        return protocol().map(SwimProtocol::currentHealth);
    }

    private Option<SwimProtocol> protocol() {
        return switch (context.fsm()
                              .current()) {
            case SwimHealthState.Running r -> option(r.swim());
            default -> none();
        };
    }

    private Result<SwimTransport> createTransport(Option<EventLoopGroup> sharedEventLoopGroup,
                                                  GossipEncryptor encryptor) {
        return sharedEventLoopGroup.map(group -> NettySwimTransport.nettySwimTransport(context.serializer(),
                                                                                       context.deserializer(),
                                                                                       encryptor,
                                                                                       group))
                                   .or(NettySwimTransport.nettySwimTransport(context.serializer(),
                                                                             context.deserializer(),
                                                                             encryptor));
    }

    @SuppressWarnings({"JBCT-RET-01", "JBCT-EX-01"})
    private Result<SwimHealthEvents.ProtocolReady> createAndStartProtocol(SwimTransport transport,
                                                                          InetSocketAddress selfAddress,
                                                                          int swimPort,
                                                                          GossipEncryptor encryptor) {
        return transport.start(swimPort,
                               (sender, message) -> deliverToProtocol(sender, message))
                        .await(timeSpan(5).seconds())
                        .onFailure(cause -> log.error("SWIM transport failed to start: {}",
                                                      cause.message()))
                        .flatMap(_ -> SwimProtocol.swimProtocol(swimConfig,
                                                                transport,
                                                                this,
                                                                context.topologyConfig().self(),
                                                                selfAddress,
                                                                context.isBootingSupplier()))
                        .flatMap(SwimProtocol::start)
                        .map(protocol -> seedAndWrap(protocol, transport, encryptor));
    }

    private SwimHealthEvents.ProtocolReady seedAndWrap(SwimProtocol protocol,
                                                       SwimTransport transport,
                                                       GossipEncryptor encryptor) {
        // Register observation listeners + transport-observation emitters BEFORE seeding members.
        // seedMembers adds the configured peers, each of which fires a `MemberDiscovered` observation
        // (notifyMemberJoined → deliverObservation) that the AetherNode listener routes into the QUIC
        // dial set. If seeding ran first (the prior order), those seed observations fired into an empty
        // observationListeners list and were lost — a last-joining node (which learns its peers only
        // from the static seed set, having missed peers' live ANNOUNCE window) then never populated its
        // dial set and, being the higher NodeId that must initiate, never dialed — wedging cold-start.
        pendingObservationListeners.forEach(protocol::addObservationListener);
        pendingTransportObservationEmitters.forEach(protocol::addTransportObservationEmitter);
        seedMembers(protocol);
        pendingAnnounceJoin.onPresent(call -> protocol.announceJoin(call.self(),
                                                                    call.clusterName(),
                                                                    call.incarnation(),
                                                                    call.seeds()));

        return new SwimHealthEvents.ProtocolReady(protocol, transport, encryptor);
    }

    private void seedMembers(SwimProtocol protocol) {
        context.topologyConfig().coreNodes().stream().filter(node -> !node.id()
                                                                          .equals(context.topologyConfig().self())).forEach(node -> addSeedMember(protocol,
                                                                                                                                                  node));
    }

    private static void addSeedMember(SwimProtocol protocol, NodeInfo node) {
        var host = node.address().host();
        var swimPort = node.address().port() + SWIM_PORT_OFFSET;
        var swimAddress = InetSocketAddress.createUnresolved(host, swimPort);
        protocol.addSeedMember(node.id(), swimAddress);
    }

    private void deliverToProtocol(InetSocketAddress sender, SwimMessage message) {
        if (context.fsm().current() instanceof SwimHealthState.Running running) {
            running.swim().onMessage(sender, message);
        }
    }

    private int findSelfPort() {
        return context.findSelfNode()
                      .map(n -> n.address()
                                 .port())
                      .or(0);
    }

    private String findSelfHost() {
        return context.findSelfNode()
                      .map(n -> n.address()
                                 .host())
                      .or("localhost");
    }
}
