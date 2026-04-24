// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.health.fsm;

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

/// Shared context for the SWIM health-detector FSM. Holds:
/// - Immutable configuration (router, topology, serializer/deserializer, signal sink, epoch
///   supplier, leader-gate supplier, observation buffer, SWIM config).
/// - Per-FSM "singleton" state instances (`stopped`, `starting`) so CAS comparisons against them
///   are stable.
/// - Long-lived accounting that survives `Running ↔ LocalDisconnect` cycles: the global rolling-
///   window faulty counter and the window-start timestamp. Explicitly NOT on the state record
///   because they must outlive individual state entries (see plan: "long-lived accounting on
///   Context").
/// - The bound `Fsm` reference for self-dispatch.
///
/// Thread safety: atomic fields are thread-safe on their own. Callers MUST NOT invoke `dispatch`
/// from the initial-state factory.
public final class SwimHealthContext {

    private final Fsm<SwimHealthState, SwimHealthEvents> fsm;
    private final MessageRouter router;
    private final TopologyConfig topologyConfig;
    private final Serializer serializer;
    private final Deserializer deserializer;
    private final HealthSignalSink signalSink;
    private final Supplier<Epoch> epochSupplier;
    private final BooleanSupplier isLeaderSupplier;
    private final PeerObservationBuffer observationBuffer;
    private final SwimConfig swimConfig;
    private final LongSupplier clock;

    // Global rolling-window faulty counter + window-start timestamp. Kept on Context (not on
    // the state record) so that counts survive Running → LocalDisconnect → Running cycles.
    // Matches the pre-FSM semantic of a single shared faulty-within-window count across all
    // peers — any majority-FAULTY gust within `suspectTimeout` triggers local disconnect.
    private final AtomicInteger faultyCountInWindow = new AtomicInteger();
    private final AtomicLong faultyWindowStart = new AtomicLong();

    // Per-FSM singletons.
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
                             PeerObservationBuffer observationBuffer,
                             SwimConfig swimConfig) {
        this(fsm, router, topologyConfig, serializer, deserializer, signalSink, epochSupplier,
             isLeaderSupplier, observationBuffer, swimConfig, System::currentTimeMillis);
    }

    /// Full constructor with injectable clock — for tests that need deterministic time.
    public SwimHealthContext(Fsm<SwimHealthState, SwimHealthEvents> fsm,
                             MessageRouter router,
                             TopologyConfig topologyConfig,
                             Serializer serializer,
                             Deserializer deserializer,
                             HealthSignalSink signalSink,
                             Supplier<Epoch> epochSupplier,
                             BooleanSupplier isLeaderSupplier,
                             PeerObservationBuffer observationBuffer,
                             SwimConfig swimConfig,
                             LongSupplier clock) {
        this.fsm = fsm;
        this.router = router;
        this.topologyConfig = topologyConfig;
        this.serializer = serializer;
        this.deserializer = deserializer;
        this.signalSink = signalSink;
        this.epochSupplier = epochSupplier;
        this.isLeaderSupplier = isLeaderSupplier;
        this.observationBuffer = observationBuffer;
        this.swimConfig = swimConfig;
        this.clock = clock;
        this.stopped = new SwimHealthState.Stopped(this);
        this.starting = new SwimHealthState.Starting(this);
    }

    /// Current time in milliseconds. Reads from the injected clock so tests can make FSM
    /// transitions deterministic. Equivalent to `System.currentTimeMillis()` in production.
    public long nowMs() {
        return clock.getAsLong();
    }

    // --- FSM / state access ---

    public Fsm<SwimHealthState, SwimHealthEvents> fsm() { return fsm; }

    public void dispatch(SwimHealthEvents event) { fsm.dispatch(event); }

    public SwimHealthState stopped() { return stopped; }

    public SwimHealthState starting() { return starting; }

    // --- Configuration accessors ---

    public MessageRouter router() { return router; }

    public TopologyConfig topologyConfig() { return topologyConfig; }

    public Serializer serializer() { return serializer; }

    public Deserializer deserializer() { return deserializer; }

    public BooleanSupplier isLeaderSupplier() { return isLeaderSupplier; }

    public SwimConfig swimConfig() { return swimConfig; }

    // --- Routing helpers ---

    public void routeDisconnect(NodeId nodeId) {
        router.routeAsync(() -> new NetworkServiceMessage.DisconnectNode(nodeId));
    }

    // --- Health reporting ---

    /// Route a health hint according to leader/follower role policy: leaders emit through the
    /// signal sink (fed into HealthReconciler); followers buffer upstream for the next pong
    /// (single-writer rule). Both paths use the current epoch from [`epochSupplier`].
    public void reportHint(NodeId nodeId, HealthHint hint) {
        if (isLeaderSupplier.getAsBoolean()) {
            emitLeaderHint(nodeId, hint);
            return;
        }
        bufferHealthObservation(nodeId, hint);
    }

    public void emitLeaderHint(NodeId nodeId, HealthHint hint) {
        signalSink.emit(new HealthSignal.SwimHint(nodeId, hint, epochSupplier.get()));
    }

    public void bufferHealthObservation(NodeId nodeId, HealthHint hint) {
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

    // --- Shared routing policy (works without a live protocol) ---

    /// Shared FAULTY-routing policy used by both `onMemberFaulty` (FSM Running or legacy Stopped)
    /// and `onMemberLeft`. Leader: emits health hint locally and routes DisconnectNode. Follower:
    /// buffers the observation upstream for the leader's HealthReconciler and, when the faulty
    /// peer IS the current leader (buffer-upstream cannot work), also routes DisconnectNode
    /// locally so LeaderManager detects NodeRemoved and proposes a new leader.
    public void routeFaulty(NodeId peer, Option<NodeId> currentLeader) {
        if (isLeaderSupplier.getAsBoolean()) {
            routeDisconnect(peer);
            emitLeaderHint(peer, HealthHint.FAULTY);
            return;
        }
        if (currentLeader.filter(peer::equals).isPresent()) {
            routeDisconnect(peer);
        }
        bufferHealthObservation(peer, HealthHint.FAULTY);
    }

    // --- Faulty-window accounting ---

    /// Increment the global rolling-window faulty counter. If the window elapsed
    /// ([`SwimConfig#suspectTimeout`]) has passed, reset the counter and restart the window
    /// before counting. The counter is shared across all peers to match the pre-FSM semantic.
    public int incrementAndGetFaulty(long nowMillis) {
        var suspectTimeoutMs = swimConfig.suspectTimeout().millis();
        var start = faultyWindowStart.get();
        if (nowMillis - start > suspectTimeoutMs
            && faultyWindowStart.compareAndSet(start, nowMillis)) {
            faultyCountInWindow.set(0);
        }
        return faultyCountInWindow.incrementAndGet();
    }

    /// Reset the rolling window — invoked when the detector observes recovery
    /// (onNodeConnected / onMemberJoined). Restarts the window start timestamp as well.
    public void resetFaultyWindow(long nowMillis) {
        faultyCountInWindow.set(0);
        faultyWindowStart.set(nowMillis);
    }

    // --- Topology helpers ---

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
