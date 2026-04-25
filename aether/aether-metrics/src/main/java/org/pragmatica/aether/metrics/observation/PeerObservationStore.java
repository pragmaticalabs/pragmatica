// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics.observation;

import org.pragmatica.cluster.metrics.PeerConnectivityObservation;
import org.pragmatica.cluster.metrics.PeerHealthObservation;
import org.pragmatica.cluster.metrics.PeerObservationBuffer;
import org.pragmatica.lang.Contract;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

import org.pragmatica.consensus.NodeId;


/// Node-level singleton owner of buffered peer observations.
///
/// Replaces the per-FSM buffers that used to live on `ClusterSyncContext` (follower-only,
/// drained from `buildPong`) and the leader-only `signalSink.emit` path. Both the ping path
/// (`ClusterSyncCollectorImpl.onClusterSyncPing` → `buildPong`) and the pong path (in the
/// leader era) now write to / read from this single store.
///
/// Why node-level: when a follower is promoted to leader it no longer receives pings, so the
/// follower-era buffer never drains. By centralising the buffer on the node, the freshly-
/// elected leader can subscribe (Q3) and observe its own SWIM hints without depending on a
/// peer's ping arriving first.
///
/// Thread safety: per-channel locks on the deques; subscriber lists are
/// `CopyOnWriteArrayList`. Subscriber callbacks fire synchronously on the writer thread —
/// callbacks MUST NOT block. Per-FSM single-writer invariants on the KV-store are unaffected
/// (only the leader writes; the buffer is shared infrastructure).
///
/// Cap policy: the optional [`IntSupplier`] is consulted on every push so the bound shrinks /
/// grows with topology changes. Default is [`#DEFAULT_CAP`] when no supplier is wired.
public final class PeerObservationStore implements PeerObservationBuffer {

    private static final int DEFAULT_CAP = 64;

    private final Object healthLock = new Object();
    private final Deque<PeerHealthObservation> healthBuffer = new ArrayDeque<>();
    private final Object connectivityLock = new Object();
    private final Deque<PeerConnectivityObservation> connectivityBuffer = new ArrayDeque<>();

    private final CopyOnWriteArrayList<Consumer<PeerHealthObservation>> healthSubscribers = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<PeerConnectivityObservation>> connectivitySubscribers = new CopyOnWriteArrayList<>();

    /// Per-peer running count of consecutive ping misses. Owned at the node level — survives
    /// leader thrash so HealthReconciler does not lose miss telemetry on every demote/promote
    /// flip. Counters are pruned by HealthReconciler against live core members
    /// (see `retainPingMisses`) and explicitly cleared on SWIM HEALTHY transitions
    /// (see `clearPingMisses`).
    private final ConcurrentHashMap<NodeId, Integer> pingMisses = new ConcurrentHashMap<>();

    private volatile IntSupplier capSupplier = () -> DEFAULT_CAP;

    private PeerObservationStore() {}

    public static PeerObservationStore peerObservationStore() {
        return new PeerObservationStore();
    }

    /// Wire a topology-aware cap supplier. Called once at boot from [`ClusterSyncContext`] so
    /// the store inherits the same `bufferCap()` semantics that the previous in-context buffer
    /// used (per-peer burst * peers, with a floor). Idempotent — last write wins.
    @Contract public void setCapSupplier(IntSupplier supplier) {
        capSupplier = supplier;
    }

    @Override @Contract public void pushHealth(PeerHealthObservation observation) {
        synchronized (healthLock) {
            if (healthBuffer.size() >= capSupplier.getAsInt()) {
                healthBuffer.pollFirst();
            }
            healthBuffer.offerLast(observation);
        }
        notifyHealth(observation);
    }

    @Override @Contract public void pushConnectivity(PeerConnectivityObservation observation) {
        synchronized (connectivityLock) {
            if (connectivityBuffer.size() >= capSupplier.getAsInt()) {
                connectivityBuffer.pollFirst();
            }
            connectivityBuffer.offerLast(observation);
        }
        notifyConnectivity(observation);
    }

    @Override public List<PeerHealthObservation> drainHealth() {
        synchronized (healthLock) {
            if (healthBuffer.isEmpty()) { return List.of(); }
            var drained = new ArrayList<>(healthBuffer);
            healthBuffer.clear();
            return List.copyOf(drained);
        }
    }

    @Override public List<PeerConnectivityObservation> drainConnectivity() {
        synchronized (connectivityLock) {
            if (connectivityBuffer.isEmpty()) { return List.of(); }
            var drained = new ArrayList<>(connectivityBuffer);
            connectivityBuffer.clear();
            return List.copyOf(drained);
        }
    }

    /// Drop both buffers. Called from `ClusterSyncState.Stopped.onEntry` so the terminal state
    /// does not retain dangling observations. Subscribers are NOT cleared — the store outlives
    /// the FSM. Ping-miss counters are also retained intentionally — they outlive any single
    /// FSM and only reset on `clearAllPingMisses()` (node shutdown / explicit reset) or
    /// targeted `clearPingMisses(NodeId)` (peer recovered).
    @Contract public void clear() {
        synchronized (healthLock) { healthBuffer.clear(); }
        synchronized (connectivityLock) { connectivityBuffer.clear(); }
    }

    /// Increment and return the consecutive ping-miss count for a peer. Called by
    /// HealthReconciler on `PingTimeout` and `QuicDisconnect` signals.
    public int recordPingMiss(NodeId peer) {
        return pingMisses.merge(peer, 1, Integer::sum);
    }

    /// Reset the consecutive ping-miss count for a peer. Called when SWIM reports the peer
    /// HEALTHY again so the suspect/evict thresholds restart from zero.
    @Contract public void clearPingMisses(NodeId peer) {
        pingMisses.remove(peer);
    }

    /// Read-only view of the current consecutive ping-miss count for a peer (zero if absent).
    /// Exposed for tests and diagnostics.
    public int pingMissCount(NodeId peer) {
        return pingMisses.getOrDefault(peer, 0);
    }

    /// Prune ping-miss counters down to the supplied live-core set. Called by HealthReconciler
    /// on every signal so counters for departed members do not linger.
    @Contract public void retainPingMisses(Set<NodeId> liveCore) {
        pingMisses.keySet().retainAll(liveCore);
    }

    /// Drop all ping-miss counters. Reserved for explicit resets; NOT invoked on leader demote
    /// (counter lifetime is per-NODE, not per-leader-tenure).
    @Contract public void clearAllPingMisses() {
        pingMisses.clear();
    }

    /// Subscribe a callback fired synchronously on every fresh health observation arrival.
    /// Used by `HealthReconciler.LeadingSteady.onEntry` (Q3) so the freshly-promoted leader
    /// observes hints without waiting for a pong drain. Subscribers MUST NOT block.
    public Subscription subscribeHealth(Consumer<PeerHealthObservation> callback) {
        healthSubscribers.add(callback);
        return () -> healthSubscribers.remove(callback);
    }

    public Subscription subscribeConnectivity(Consumer<PeerConnectivityObservation> callback) {
        connectivitySubscribers.add(callback);
        return () -> connectivitySubscribers.remove(callback);
    }

    private void notifyHealth(PeerHealthObservation observation) {
        healthSubscribers.forEach(subscriber -> subscriber.accept(observation));
    }

    private void notifyConnectivity(PeerConnectivityObservation observation) {
        connectivitySubscribers.forEach(subscriber -> subscriber.accept(observation));
    }

    /// Token returned by `subscribeHealth` / `subscribeConnectivity`; calling
    /// [`#unsubscribe()`] removes the callback. Idempotent — second call is a no-op.
    public interface Subscription {
        @Contract void unsubscribe();
    }
}
