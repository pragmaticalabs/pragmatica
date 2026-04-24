// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics.fsm;

import org.pragmatica.aether.metrics.ClusterSyncCollector;
import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.HealthSignal;
import org.pragmatica.aether.slice.generation.HealthSignalSink;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPing;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.SnapshotPayload;
import org.pragmatica.cluster.metrics.PeerConnectivityObservation;
import org.pragmatica.cluster.metrics.PeerHealthObservation;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.statemachine.Fsm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/// Shared context for the cluster-sync scheduler FSM. Holds every long-lived artifact that is
/// intentionally NOT on a state record:
///
/// - Collaborators and config (network, collector, suppliers, threshold, interval, signal sink).
/// - The `topology` snapshot — read by `bufferCap` in every state (Dormant / Pinging / Stopped),
///   so it cannot live on the `Pinging` record alone.
/// - `quorumSequence` — intentionally kept here per the current adapter boundary: even though the
///   FSM pattern would otherwise subsume it, `ClusterFsmRouter.wire` expects the caller to
///   supply an external `AtomicLong` for stale-notification dedup; this field IS that
///   deduplication anchor.
/// - `observedEpoch` — per-peer pong-advanced epoch, queried via the public `observedEpochs()`
///   accessor in every state (not owned by `Pinging`).
/// - `healthBuffer` / `connectivityBuffer` — follower-facing observation buffers. Followers push
///   regardless of whether this scheduler is pinging, so the buffers outlive individual state
///   entries.
/// - The ping timer is now owned by the [`Pinging`] record (eagerly scheduled in
///   [`#schedulePingTimer`] called from `Pinging.fresh` / `Pinging.with`). Pinging.onExit /
///   Pinging.onCasLost cancel the timer; Stopped.onEntry only clears observation buffers.
///
/// Thread safety: the atomic fields (`topology`, `quorumSequence`) and concurrent maps
/// (`observedEpoch`) are thread-safe on their own. Buffer mutation uses explicit lock objects. The
/// FSM reference is `final` and safe for publication once the initial-state factory returns.
public final class ClusterSyncContext {

    private static final int PER_PEER_BURST = 4;
    private static final int MIN_BUFFER_CAP = 8;

    private final Fsm<ClusterSyncState, ClusterFsmEvent> fsm;

    // Collaborators & config
    private final NodeId self;
    private final ClusterNetwork network;
    private final ClusterSyncCollector collector;
    private final TimeSpan interval;
    private final Supplier<Long> rabiaTermSupplier;
    private final Supplier<Option<ClusterGenerationSnapshot>> snapshotSupplier;
    private final Function<ClusterGenerationSnapshot, byte[]> snapshotEncoder;
    private final HealthSignalSink signalSink;
    private final int pingTimeoutThreshold;
    private final Supplier<Epoch> epochSupplier;

    // Long-lived state (all-states visible — NOT on Pinging record).
    private final AtomicReference<List<NodeId>> topology = new AtomicReference<>(List.of());
    private final AtomicLong quorumSequence = new AtomicLong();
    private final Map<NodeId, Epoch> observedEpoch = new ConcurrentHashMap<>();

    // Follower observation buffers.
    private final Object healthBufferLock = new Object();
    private final Deque<PeerHealthObservation> healthBuffer = new ArrayDeque<>();
    private final Object connectivityBufferLock = new Object();
    private final Deque<PeerConnectivityObservation> connectivityBuffer = new ArrayDeque<>();

    // Per-FSM singletons for the data-free states.
    private final ClusterSyncState dormant;
    private final ClusterSyncState stopped;

    public ClusterSyncContext(Fsm<ClusterSyncState, ClusterFsmEvent> fsm,
                              NodeId self,
                              ClusterNetwork network,
                              ClusterSyncCollector collector,
                              TimeSpan interval,
                              Supplier<Long> rabiaTermSupplier,
                              Supplier<Option<ClusterGenerationSnapshot>> snapshotSupplier,
                              Function<ClusterGenerationSnapshot, byte[]> snapshotEncoder,
                              HealthSignalSink signalSink,
                              int pingTimeoutThreshold,
                              Supplier<Epoch> epochSupplier) {
        this.fsm = fsm;
        this.self = self;
        this.network = network;
        this.collector = collector;
        this.interval = interval;
        this.rabiaTermSupplier = rabiaTermSupplier;
        this.snapshotSupplier = snapshotSupplier;
        this.snapshotEncoder = snapshotEncoder;
        this.signalSink = signalSink;
        this.pingTimeoutThreshold = pingTimeoutThreshold;
        this.epochSupplier = epochSupplier;
        this.dormant = new ClusterSyncState.Dormant(this);
        this.stopped = new ClusterSyncState.Stopped(this);
    }

    // --- FSM / state access ---

    public Fsm<ClusterSyncState, ClusterFsmEvent> fsm() { return fsm; }

    @Contract public void dispatch(ClusterFsmEvent event) { fsm.dispatch(event); }

    public ClusterSyncState dormant() { return dormant; }

    public ClusterSyncState stopped() { return stopped; }

    // --- Configuration accessors ---

    public NodeId self() { return self; }

    public Supplier<Option<ClusterGenerationSnapshot>> snapshotSupplier() { return snapshotSupplier; }

    public Supplier<Epoch> epochSupplier() { return epochSupplier; }

    public int pingTimeoutThreshold() { return pingTimeoutThreshold; }

    public AtomicLong quorumSequence() { return quorumSequence; }

    // --- Topology ---

    public List<NodeId> topology() { return topology.get(); }

    @Contract public void setTopology(List<NodeId> newTopology) { topology.set(newTopology); }

    // --- Observed-epoch accessor (public surface) ---

    @Contract public void recordObservedEpoch(NodeId nodeId, Epoch epoch) {
        observedEpoch.merge(nodeId, epoch, ClusterSyncContext::pickLater);
    }

    private static Epoch pickLater(Epoch prev, Epoch next) {
        return next.isStrictlyAfter(prev) ? next : prev;
    }

    public Map<NodeId, Epoch> observedEpochs() { return Map.copyOf(observedEpoch); }

    @Contract public void forgetPeer(NodeId peer) { observedEpoch.remove(peer); }

    // --- Ping scheduling lifecycle ---

    /// Schedule the periodic ping tick. Called eagerly from `Pinging.fresh` / `Pinging.with`; the
    /// returned future is owned by the `Pinging` record (cancelled in `onExit` / `onCasLost`).
    public ScheduledFuture<?> schedulePingTimer(Runnable tick) {
        return SharedScheduler.scheduleAtFixedRate(tick, interval);
    }

    /// Drop both follower observation buffers. Called from `Stopped.onEntry` so the terminal
    /// state does not retain dangling buffered observations.
    @Contract public void clearObservationBuffers() {
        clearBuffers();
    }

    // --- Send one ping to one peer — pure I/O, no state mutation. Returns the epoch the peer
    //     should see as its new `lastSentEpoch`. Called from within the `Pinging` state handler
    //     during a PingTick. ---

    public Epoch sendOnePing(NodeId peer,
                             Epoch currentEpoch,
                             Option<Epoch> lastSentToPeer,
                             Option<ClusterGenerationSnapshot> maybeSnapshot,
                             long rabiaTerm) {
        var payload = buildPayloadForTarget(currentEpoch, lastSentToPeer, maybeSnapshot);
        var ping = new ClusterSyncPing(self,
                                       collector.allMetrics(),
                                       rabiaTerm,
                                       currentEpoch.rabiaTerm(),
                                       currentEpoch.localCounter(),
                                       payload);
        network.send(peer, ping);
        return currentEpoch;
    }

    private Option<SnapshotPayload> buildPayloadForTarget(Epoch currentEpoch,
                                                          Option<Epoch> lastSentToPeer,
                                                          Option<ClusterGenerationSnapshot> maybeSnapshot) {
        var alreadyUpToDate = lastSentToPeer.filter(last -> !currentEpoch.isStrictlyAfter(last)).isPresent();
        if (alreadyUpToDate) {
            return Option.none();
        }
        return maybeSnapshot.map(snapshotEncoder::apply).map(SnapshotPayload::snapshotPayload);
    }

    public long currentRabiaTerm() { return rabiaTermSupplier.get(); }

    public Option<ClusterGenerationSnapshot> currentSnapshot() { return snapshotSupplier.get(); }

    @Contract public void emitPingTimeoutIfExceeded(NodeId peer, int missed) {
        if (missed < pingTimeoutThreshold) { return; }
        signalSink.emit(new HealthSignal.PingTimeout(peer, missed, epochSupplier.get()));
    }

    // --- Buffers ---

    public int bufferCap() {
        var peers = Math.max(topology.get().size() - 1, 0);
        return Math.max(peers * PER_PEER_BURST, MIN_BUFFER_CAP);
    }

    @Contract public void pushHealth(PeerHealthObservation observation) {
        synchronized (healthBufferLock) {
            if (healthBuffer.size() >= bufferCap()) {
                healthBuffer.pollFirst();
            }
            healthBuffer.offerLast(observation);
        }
    }

    @Contract public void pushConnectivity(PeerConnectivityObservation observation) {
        synchronized (connectivityBufferLock) {
            if (connectivityBuffer.size() >= bufferCap()) {
                connectivityBuffer.pollFirst();
            }
            connectivityBuffer.offerLast(observation);
        }
    }

    public List<PeerHealthObservation> drainHealth() {
        synchronized (healthBufferLock) {
            if (healthBuffer.isEmpty()) { return List.of(); }
            var drained = new ArrayList<>(healthBuffer);
            healthBuffer.clear();
            return List.copyOf(drained);
        }
    }

    public List<PeerConnectivityObservation> drainConnectivity() {
        synchronized (connectivityBufferLock) {
            if (connectivityBuffer.isEmpty()) { return List.of(); }
            var drained = new ArrayList<>(connectivityBuffer);
            connectivityBuffer.clear();
            return List.copyOf(drained);
        }
    }

    private void clearBuffers() {
        synchronized (healthBufferLock) { healthBuffer.clear(); }
        synchronized (connectivityBufferLock) { connectivityBuffer.clear(); }
    }
}
