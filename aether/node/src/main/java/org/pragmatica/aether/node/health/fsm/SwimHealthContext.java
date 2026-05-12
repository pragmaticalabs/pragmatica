// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.health.fsm;

import org.pragmatica.aether.metrics.observation.PeerObservationStore;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.HealthHint;
import org.pragmatica.aether.slice.generation.HealthSignal;
import org.pragmatica.aether.slice.generation.HealthSignalSink;
import org.pragmatica.cluster.metrics.HealthHintWire;
import org.pragmatica.cluster.metrics.PeerHealthObservation;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.swim.SwimConfig;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;


public final class SwimHealthContext {
    private final Fsm<SwimHealthState, SwimHealthEvents> fsm;
    private final MessageRouter router;
    private final TopologyConfig topologyConfig;
    private final Serializer serializer;
    private final Deserializer deserializer;
    private final HealthSignalSink signalSink;
    private final Supplier<Epoch> epochSupplier;
    private final BooleanSupplier isLeaderSupplier;
    private final PeerObservationStore observationStore;
    private final SwimConfig swimConfig;
    private final LongSupplier clock;
    /// Phase-aware SWIM cold-boot suppression (D.3, 2026-05-11). `true` when the
    /// cluster is in `COLD_BOOT` phase (FAULTY for never-HEALTHY peers should be
    /// suppressed to `UnknownObserved`); `false` in `NORMAL` and `RECOVERING` (always
    /// emit `FaultyObserved` regardless of `everSeenHealthy`). Production wiring
    /// sources this from `ClusterPhaseView.compute() == ClusterPhase.COLD_BOOT` — the
    /// `RECOVERING` branch is the critical compose-restart fix where peers were
    /// previously visible-and-Healthy. Default `() -> true` preserves legacy behavior
    /// for unit tests that don't wire a phase.
    private final BooleanSupplier isBootingSupplier;

    /// Targeted leader-faulty evictor (2026-05-09) — narrow re-introduction of the
    /// SWIM-FAULTY-to-disconnect bridge that audit Step 3 removed for general peers.
    /// Fires ONLY when the FAULTY target equals the current cluster leader. Reason:
    /// post-Step-3 the eviction path is consensus-driven (DECOMMISSIONED write →
    /// snapshot delta → NodeRemoved → disconnect), but consensus.apply itself depends
    /// on reliable-broadcast progressing — and the broadcast queues sends to the
    /// dead-but-still-QUIC-connected leader indefinitely on cloud Container, where
    /// QUIC's own inactivity timeout is sluggish (Docker network-namespace teardown
    /// delays the kernel-level socket close). Catch-22 broken locally per node by
    /// disconnecting the QUIC peer the moment SWIM marks it FAULTY-and-leader. Only
    /// the leader case is bridged — general FAULTY peers continue through the
    /// post-consensus path, preserving Step 3's elimination of the N+1 fan-out
    /// cascade. Transport hygiene (DisconnectNode) is NOT subject to the
    /// single-writer rule, so concurrent eviction calls from N surviving nodes are
    /// idempotent at the QUIC layer (`peer.evict` is CONNECTED→EVICTED, no-op
    /// otherwise). Default `_ -> {}` preserves legacy behavior for unit tests.
    private final java.util.function.Consumer<NodeId> faultyLeaderEvictor;

    private final AtomicInteger faultyCountInWindow = new AtomicInteger();

    private final AtomicLong faultyWindowStart = new AtomicLong();

    private final SwimHealthState stopped;
    private final SwimHealthState starting;

    public SwimHealthContext(Fsm<SwimHealthState, SwimHealthEvents> fsm,
                             MessageRouter router,
                             TopologyConfig topologyConfig,
                             Serializer serializer,
                             Deserializer deserializer,
                             HealthSignalSink signalSink,
                             Supplier<Epoch> epochSupplier,
                             BooleanSupplier isLeaderSupplier,
                             PeerObservationStore observationStore,
                             SwimConfig swimConfig) {
        this(fsm,
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
             () -> true);
    }

    public SwimHealthContext(Fsm<SwimHealthState, SwimHealthEvents> fsm,
                             MessageRouter router,
                             TopologyConfig topologyConfig,
                             Serializer serializer,
                             Deserializer deserializer,
                             HealthSignalSink signalSink,
                             Supplier<Epoch> epochSupplier,
                             BooleanSupplier isLeaderSupplier,
                             PeerObservationStore observationStore,
                             SwimConfig swimConfig,
                             LongSupplier clock) {
        this(fsm,
             router,
             topologyConfig,
             serializer,
             deserializer,
             signalSink,
             epochSupplier,
             isLeaderSupplier,
             observationStore,
             swimConfig,
             clock,
             () -> true);
    }

    public SwimHealthContext(Fsm<SwimHealthState, SwimHealthEvents> fsm,
                             MessageRouter router,
                             TopologyConfig topologyConfig,
                             Serializer serializer,
                             Deserializer deserializer,
                             HealthSignalSink signalSink,
                             Supplier<Epoch> epochSupplier,
                             BooleanSupplier isLeaderSupplier,
                             PeerObservationStore observationStore,
                             SwimConfig swimConfig,
                             LongSupplier clock,
                             BooleanSupplier isBootingSupplier) {
        this(fsm,
             router,
             topologyConfig,
             serializer,
             deserializer,
             signalSink,
             epochSupplier,
             isLeaderSupplier,
             observationStore,
             swimConfig,
             clock,
             isBootingSupplier,
             _ -> {});
    }

    public SwimHealthContext(Fsm<SwimHealthState, SwimHealthEvents> fsm,
                             MessageRouter router,
                             TopologyConfig topologyConfig,
                             Serializer serializer,
                             Deserializer deserializer,
                             HealthSignalSink signalSink,
                             Supplier<Epoch> epochSupplier,
                             BooleanSupplier isLeaderSupplier,
                             PeerObservationStore observationStore,
                             SwimConfig swimConfig,
                             LongSupplier clock,
                             BooleanSupplier isBootingSupplier,
                             java.util.function.Consumer<NodeId> faultyLeaderEvictor) {
        this.fsm = fsm;
        this.router = router;
        this.topologyConfig = topologyConfig;
        this.serializer = serializer;
        this.deserializer = deserializer;
        this.signalSink = signalSink;
        this.epochSupplier = epochSupplier;
        this.isLeaderSupplier = isLeaderSupplier;
        this.observationStore = observationStore;
        this.swimConfig = swimConfig;
        this.clock = clock;
        this.isBootingSupplier = isBootingSupplier;
        this.faultyLeaderEvictor = faultyLeaderEvictor;
        this.stopped = new SwimHealthState.Stopped(this);
        this.starting = new SwimHealthState.Starting(this);
    }

    public long nowMs() {
        return clock.getAsLong();
    }

    public Fsm<SwimHealthState, SwimHealthEvents> fsm() {
        return fsm;
    }

    @Contract public void dispatch(SwimHealthEvents event) {
        fsm.dispatch(event);
    }

    public SwimHealthState stopped() {
        return stopped;
    }

    public SwimHealthState starting() {
        return starting;
    }

    public MessageRouter router() {
        return router;
    }

    public TopologyConfig topologyConfig() {
        return topologyConfig;
    }

    public Serializer serializer() {
        return serializer;
    }

    public Deserializer deserializer() {
        return deserializer;
    }

    public BooleanSupplier isLeaderSupplier() {
        return isLeaderSupplier;
    }

    /// Phase-aware cold-boot suppression gate. See [`#isBootingSupplier`] field doc.
    public BooleanSupplier isBootingSupplier() {
        return isBootingSupplier;
    }

    public SwimConfig swimConfig() {
        return swimConfig;
    }

    @Contract public void reportHint(NodeId nodeId, HealthHint hint) {
        emitLeaderHint(nodeId, hint);
        bufferHealthObservation(nodeId, hint);
    }

    @Contract public void emitLeaderHint(NodeId nodeId, HealthHint hint) {
        signalSink.emit(new HealthSignal.SwimHint(nodeId, hint, epochSupplier.get()));
    }

    @Contract public void bufferHealthObservation(NodeId nodeId, HealthHint hint) {
        var epoch = epochSupplier.get();
        observationStore.pushHealth(new PeerHealthObservation(nodeId,
                                                              toWire(hint),
                                                              epoch.rabiaTerm(),
                                                              epoch.localCounter(),
                                                              nowMs()));
    }

    private static HealthHintWire toWire(HealthHint hint) {
        return switch (hint){
            case HEALTHY -> HealthHintWire.HEALTHY;
            case SUSPECTED -> HealthHintWire.SUSPECTED;
            case FAULTY -> HealthHintWire.FAULTY;
        };
    }

    /// RC1-9 audit Step 3: the leader-only `routeDisconnect(peer)` and the
    /// follower-for-dead-leader `routeDisconnect(peer)` are both gone. QUIC eviction
    /// now flows via `MembershipDecision.NodeRemoved` after the leader's
    /// `MembershipFsm` writes `DECOMMISSIONED` and `TopologyObserver` publishes
    /// the membership delta. The leader-side aggregation path
    /// (`emitLeaderHint` + `bufferHealthObservation`) is preserved.
    @Contract public void routeFaulty(NodeId peer, Option<NodeId> currentLeader) {
        emitLeaderHint(peer, HealthHint.FAULTY);
        bufferHealthObservation(peer, HealthHint.FAULTY);
        // See `faultyLeaderEvictor` field doc for the catch-22 this breaks. Narrow trigger:
        // ONLY when (a) cluster phase is NORMAL or RECOVERING (not COLD_BOOT — boot-time
        // transient FAULTY events on the still-being-established leader would otherwise
        // prematurely evict it) AND (b) the FAULTY peer IS the current cluster leader.
        // Other peers, and COLD_BOOT-phase observations, continue through the
        // post-consensus eviction path (audit Step 3).
        if (!isBootingSupplier.getAsBoolean() && currentLeader.map(peer::equals).or(false)) {
            faultyLeaderEvictor.accept(peer);
        }
    }

    public int incrementAndGetFaulty(long nowMillis) {
        var suspectTimeoutMs = swimConfig.suspectTimeout().millis();
        var start = faultyWindowStart.get();
        if (nowMillis - start > suspectTimeoutMs && faultyWindowStart.compareAndSet(start, nowMillis)) {faultyCountInWindow.set(0);}
        return faultyCountInWindow.incrementAndGet();
    }

    @Contract public void resetFaultyWindow(long nowMillis) {
        faultyCountInWindow.set(0);
        faultyWindowStart.set(nowMillis);
    }

    public Option<InetSocketAddress> resolveSwimAddress(NodeId nodeId, int swimPortOffset) {
        return Option.from(topologyConfig.coreNodes().stream()
                                                   .filter(node -> node.id().equals(nodeId))
                                                   .map(node -> toSwimAddress(node, swimPortOffset))
                                                   .findFirst());
    }

    public static InetSocketAddress toSwimAddress(NodeInfo node, int swimPortOffset) {
        return InetSocketAddress.createUnresolved(node.address().host(),
                                                  node.address().port() + swimPortOffset);
    }

    public Option<NodeInfo> findSelfNode() {
        return Option.from(topologyConfig.coreNodes().stream()
                                                   .filter(node -> node.id().equals(topologyConfig.self()))
                                                   .findFirst());
    }
}
