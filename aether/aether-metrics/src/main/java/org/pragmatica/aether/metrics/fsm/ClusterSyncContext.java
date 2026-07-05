// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics.fsm;

import org.pragmatica.aether.metrics.ClusterSyncCollector;
import org.pragmatica.aether.metrics.PeriodicObservationConfig;
import org.pragmatica.aether.metrics.observation.PeerObservationStore;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.HealthSignal;
import org.pragmatica.aether.slice.generation.HealthSignalSink;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPing;
import org.pragmatica.cluster.metrics.PeerConnectivityObservation;
import org.pragmatica.cluster.metrics.PeerHealthObservation;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.statemachine.Fsm;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public final class ClusterSyncContext {
    private static final Logger log = LoggerFactory.getLogger(ClusterSyncContext.class);
    private static final int PER_PEER_BURST = 4;
    private static final int MIN_BUFFER_CAP = 8;

    private final Fsm<ClusterSyncState, ClusterFsmEvent> fsm;
    private final NodeId self;
    private final ClusterNetwork network;
    private final ClusterSyncCollector collector;
    private final TimeSpan interval;
    private final Supplier<Long> rabiaTermSupplier;
    private final HealthSignalSink signalSink;
    private final int pingTimeoutThreshold;
    private final Supplier<Epoch> epochSupplier;
    /// #231 regression fix — ping DISPATCH is leader-only. Sourced from the SAME leader signal as
    /// the other leader-gated AetherNode wiring (`clusterNode.leaderManager()::isLeader`). When this
    /// returns false the Pinging tick is a no-op (no dispatch, no missedPings, no eviction emission);
    /// the node still RESPONDS to inbound pings. Defaults to always-leader for callers that don't
    /// supply it (single-pinger tests / standalone use), preserving prior behavior.
    private final BooleanSupplier isLeader;
    /// Membership v2 (B5a/B5b) — leader-local supplier of the GLOBAL set of nodes to command DRAIN.
    /// `broadcastPing` snapshots it via `drainTargets.get()` and stamps the set into the single
    /// uniform `ClusterSyncPing.drainNodes` BROADCAST to every QUIC-connected peer; each receiver
    /// self-checks membership. Sourced from `DrainCommandRegistry::drainTargets` in production.
    /// Defaults to `() -> Set.of()` (no drain ever) so existing construction / tests keep working
    /// with no behavior change. Mutable holder so `AetherNode` can inject the real supplier AFTER
    /// scheduler construction (`ClusterSyncScheduler.setDrainTargets`) once the
    /// `DrainCommandRegistry` exists, without a constructor-signature change to the wiring path.
    private final AtomicReference<Supplier<Set<NodeId>>> drainTargets = new AtomicReference<>(Set::of);

    /// Provisioning-stickiness fix — leader-local supplier of the GLOBAL set of node ids the leader
    /// currently tracks as IN-FLIGHT provisioning (dispatched-but-not-yet-joined replacements).
    /// `broadcastPing` snapshots it via `dispatchedNodesSupplier.get().get()` and stamps the set into
    /// the single uniform `ClusterSyncPing.dispatchedNodes` BROADCAST to every QUIC-connected peer.
    /// Followers retain it stickily (term-fenced) so a NEW leader seeds its in-flight set from the
    /// retained set instead of starting empty (preventing re-dispatch / over-provisioning). Sourced
    /// from `LeaderReconciler::inFlightProvisioningKeys` in production. Defaults to `() -> Set.of()`
    /// (no in-flight ever) so existing construction / tests keep working with no behavior change.
    /// Mutable holder so `AetherNode` can inject the real supplier AFTER scheduler construction
    /// (`ClusterSyncScheduler.setDispatchedNodesSupplier`) once the `LeaderReconciler` exists.
    private final AtomicReference<Supplier<Set<NodeId>>> dispatchedNodesSupplier = new AtomicReference<>(Set::of);

    private final AtomicReference<List<NodeId>> topology = new AtomicReference<>(List.of());
    private final AtomicLong quorumSequence = new AtomicLong();
    private final Map<NodeId, Epoch> observedEpoch = new ConcurrentHashMap<>();
    private final PeerObservationStore observationStore;
    /// RC1 (S01 fix) — peers this node has locally evicted via ping-timeout, with the
    /// nanos timestamp at which the eviction was recorded. Snapshotted into each outbound
    /// `ClusterSyncPing.evictionHints` as a suggestion to followers. Entries older than
    /// `EVICTION_HINT_TTL` are pruned on read. ConcurrentHashMap so the periodic
    /// ping tick (read) and `emitPingTimeoutIfExceeded` (write) don't race.
    private final Map<NodeId, Long> evictionHints = new ConcurrentHashMap<>();

    private static final TimeSpan EVICTION_HINT_TTL = TimeSpan.timeSpan(15).seconds();

    private final PeriodicObservationConfig periodicConfig;
    private final ClusterSyncState dormant;
    private final ClusterSyncState stopped;

    public ClusterSyncContext(Fsm<ClusterSyncState, ClusterFsmEvent> fsm,
                              NodeId self,
                              ClusterNetwork network,
                              ClusterSyncCollector collector,
                              TimeSpan interval,
                              Supplier<Long> rabiaTermSupplier,
                              HealthSignalSink signalSink,
                              int pingTimeoutThreshold,
                              Supplier<Epoch> epochSupplier,
                              PeerObservationStore observationStore) {
        this(fsm,
             self,
             network,
             collector,
             interval,
             rabiaTermSupplier,
             signalSink,
             pingTimeoutThreshold,
             epochSupplier,
             observationStore,
             PeriodicObservationConfig.defaultConfig(),
             () -> true);
    }

    public ClusterSyncContext(Fsm<ClusterSyncState, ClusterFsmEvent> fsm,
                              NodeId self,
                              ClusterNetwork network,
                              ClusterSyncCollector collector,
                              TimeSpan interval,
                              Supplier<Long> rabiaTermSupplier,
                              HealthSignalSink signalSink,
                              int pingTimeoutThreshold,
                              Supplier<Epoch> epochSupplier,
                              PeerObservationStore observationStore,
                              PeriodicObservationConfig periodicConfig) {
        this(fsm,
             self,
             network,
             collector,
             interval,
             rabiaTermSupplier,
             signalSink,
             pingTimeoutThreshold,
             epochSupplier,
             observationStore,
             periodicConfig,
             () -> true);
    }

    public ClusterSyncContext(Fsm<ClusterSyncState, ClusterFsmEvent> fsm,
                              NodeId self,
                              ClusterNetwork network,
                              ClusterSyncCollector collector,
                              TimeSpan interval,
                              Supplier<Long> rabiaTermSupplier,
                              HealthSignalSink signalSink,
                              int pingTimeoutThreshold,
                              Supplier<Epoch> epochSupplier,
                              PeerObservationStore observationStore,
                              PeriodicObservationConfig periodicConfig,
                              BooleanSupplier isLeader) {
        this(fsm,
             self,
             network,
             collector,
             interval,
             rabiaTermSupplier,
             signalSink,
             pingTimeoutThreshold,
             epochSupplier,
             observationStore,
             periodicConfig,
             isLeader,
             Set::of);
    }

    public ClusterSyncContext(Fsm<ClusterSyncState, ClusterFsmEvent> fsm,
                              NodeId self,
                              ClusterNetwork network,
                              ClusterSyncCollector collector,
                              TimeSpan interval,
                              Supplier<Long> rabiaTermSupplier,
                              HealthSignalSink signalSink,
                              int pingTimeoutThreshold,
                              Supplier<Epoch> epochSupplier,
                              PeerObservationStore observationStore,
                              PeriodicObservationConfig periodicConfig,
                              BooleanSupplier isLeader,
                              Supplier<Set<NodeId>> drainTargets) {
        this.fsm = fsm;
        this.self = self;
        this.network = network;
        this.collector = collector;
        this.interval = interval;
        this.rabiaTermSupplier = rabiaTermSupplier;
        this.signalSink = signalSink;
        this.pingTimeoutThreshold = pingTimeoutThreshold;
        this.epochSupplier = epochSupplier;
        this.observationStore = observationStore;
        this.isLeader = isLeader == null
                        ? () -> true
                        : isLeader;
        this.drainTargets.set(drainTargets == null
                              ? Set::of
                              : drainTargets);
        this.periodicConfig = periodicConfig == null
                              ? PeriodicObservationConfig.defaultConfig()
                              : periodicConfig;
        this.observationStore.setCapSupplier(this::bufferCap);
        this.dormant = new ClusterSyncState.Dormant(this);
        this.stopped = new ClusterSyncState.Stopped(this);
    }

    public Fsm<ClusterSyncState, ClusterFsmEvent> fsm() {
        return fsm;
    }

    @Contract
    public void dispatch(ClusterFsmEvent event) {
        fsm.dispatch(event);
    }

    public ClusterSyncState dormant() {
        return dormant;
    }

    public ClusterSyncState stopped() {
        return stopped;
    }

    public NodeId self() {
        return self;
    }

    /// #231 regression fix — true only when this node is the current cluster leader. Gates the
    /// Pinging tick so ping DISPATCH (and the missedPings / ping-timeout / eviction-hint cycle) is
    /// leader-only. The pong-response path is independent and stays unconditional.
    public boolean isLeader() {
        return isLeader.getAsBoolean();
    }

    public Supplier<Epoch> epochSupplier() {
        return epochSupplier;
    }

    public int pingTimeoutThreshold() {
        return pingTimeoutThreshold;
    }

    public AtomicLong quorumSequence() {
        return quorumSequence;
    }

    public List<NodeId> topology() {
        return topology.get();
    }

    /// Live set of peers this node currently has a transport connection to. Same source the
    /// periodic connectivity emission reads (`emitPeriodicConnectivityNow`). Used by the ping
    /// tick as a fallback ping-target set when the membership-seeded `topology()` is still empty
    /// (Spike-1 finding: Pinging-but-unseeded → silently dormant ping/pong).
    public Set<NodeId> connectedPeers() {
        return network.connectedPeers();
    }

    @Contract
    public void setTopology(List<NodeId> newTopology) {
        topology.set(newTopology);
    }

    @Contract
    public void recordObservedEpoch(NodeId nodeId, Epoch epoch) {
        observedEpoch.merge(nodeId, epoch, ClusterSyncContext::pickLater);
    }

    private static Epoch pickLater(Epoch prev, Epoch next) {
        return next.isStrictlyAfter(prev)
               ? next
               : prev;
    }

    public Map<NodeId, Epoch> observedEpochs() {
        return Map.copyOf(observedEpoch);
    }

    @Contract
    public void forgetPeer(NodeId peer) {
        observedEpoch.remove(peer);
    }

    public ScheduledFuture<?> schedulePingTimer(Runnable tick) {
        return SharedScheduler.scheduleAtFixedRate(tick, interval);
    }

    /// Schedule the periodic `PeerConnectivityObservation` emission task. Returns
    /// the future for cancellation on `Pinging.onExit`. The task fires at
    /// `periodicConfig.period()` cadence and invokes
    /// `emitPeriodicConnectivityNow()` on the context.
    public ScheduledFuture<?> schedulePeriodicEmission() {
        return SharedScheduler.scheduleAtFixedRate(this::emitPeriodicConnectivityNow,
                                                   periodicConfig.period(),
                                                   periodicConfig.period());
    }

    public PeriodicObservationConfig periodicConfig() {
        return periodicConfig;
    }

    /// Drive one periodic emission tick synchronously. Invoked by the periodic
    /// scheduler task and by `ClusterSyncScheduler.emitPeriodicConnectivityNow()`
    /// for deterministic testing. NOT leader-gated — every node emits its own
    /// view; leader gating happens at FSM consumption (Step 3).
    @Contract
    public void emitPeriodicConnectivityNow() {
        var currentTopology = topology.get();

        if (currentTopology == null || currentTopology.isEmpty()) {
            return;
        }

        var topologySet = Set.copyOf(currentTopology);
        var connected = new HashSet<>(network.connectedPeers());

        collector.emitPeriodicConnectivity(topologySet, connected, self, System.currentTimeMillis());
    }

    @Contract
    public void clearObservationBuffers() {
        observationStore.clear();
    }

    public PeerObservationStore observationStore() {
        return observationStore;
    }

    /// Build and BROADCAST one uniform `ClusterSyncPing` to every QUIC-connected peer. The single
    /// ping carries the leader's metrics, fencing terms, the global `evictionHints` snapshot, the
    /// global `drainNodes` snapshot (`drainTargets.get()`), and the leader's authoritative
    /// `readinessView` (so followers cache it and serve per-node readiness without a leader
    /// round-trip). `network.broadcast` skips self, so no per-peer dispatch or self-exclusion is
    /// needed here.
    @Contract
    public void broadcastPing(Epoch currentEpoch, long rabiaTerm) {
        var ping = new ClusterSyncPing(self,
                                       collector.allMetrics(),
                                       rabiaTerm,
                                       currentEpoch.rabiaTerm(),
                                       currentEpoch.localCounter(),
                                       currentEvictionHints(),
                                       drainTargets.get().get(),
                                       collector.authoritativeReadinessView(),
                                       dispatchedNodesSupplier.get().get());

        log.debug("ClusterSync: broadcasting PING (rabiaTerm={}, epoch={}:{})",
                  rabiaTerm,
                  currentEpoch.rabiaTerm(),
                  currentEpoch.localCounter());
        var _ = network.broadcast(ping);
    }

    /// Membership v2 (B5b) — inject the leader-local DRAIN target supplier after construction.
    /// `AetherNode` calls this (via `ClusterSyncScheduler.setDrainTargets`) once the
    /// `DrainCommandRegistry` exists, wiring `DrainCommandRegistry::drainTargets`. `null`
    /// resets to the empty-set default.
    @Contract
    public void setDrainTargets(Supplier<Set<NodeId>> supplier) {
        drainTargets.set(supplier == null
                         ? Set::of
                         : supplier);
    }

    /// Provisioning-stickiness fix — inject the leader-local IN-FLIGHT provisioning supplier after
    /// construction. `AetherNode` calls this (via `ClusterSyncScheduler.setDispatchedNodesSupplier`)
    /// once the `LeaderReconciler` exists, wiring `LeaderReconciler::inFlightProvisioningKeys`.
    /// `null` resets to the empty-set default.
    @Contract
    public void setDispatchedNodesSupplier(Supplier<Set<NodeId>> supplier) {
        dispatchedNodesSupplier.set(supplier == null
                                    ? Set::of
                                    : supplier);
    }

    /// RC1 (S01 fix) — snapshot of peers this node has locally evicted via
    /// `emitPingTimeoutIfExceeded` recently. Included in each outbound `ClusterSyncPing`
    /// as a SUGGESTION to followers ("I think these peers are dead — verify and act").
    /// Followers reconcile against their own `lastReceivedNanos` before evicting locally;
    /// owner is not authoritative. Entries age out after `evictionHintTtlNanos`.
    private Set<NodeId> currentEvictionHints() {
        var now = System.nanoTime();
        var ttl = EVICTION_HINT_TTL.nanos();

        evictionHints.entrySet().removeIf(e -> (now - e.getValue()) > ttl);

        return Set.copyOf(evictionHints.keySet());
    }

    public long currentRabiaTerm() {
        return rabiaTermSupplier.get();
    }

    @Contract
    public void emitPingTimeoutIfExceeded(NodeId peer, int missed) {
        if (missed < pingTimeoutThreshold) {
            return;
        }

        signalSink.emit(new HealthSignal.PingTimeout(peer, missed, epochSupplier.get()));
        // RC1 (S01 fix, owner-side SWIM symmetry) + option 1: do NOT feed an unreachable hint for a
        // peer SWIM still observes as HEALTHY. Pong delivery is leader-coupled, so during a
        // leader-loss / re-election window a LIVE SWIM-HEALTHY peer can transiently miss
        // `pingTimeoutThreshold` pongs. SWIM already trusts that peer, so feeding a conflicting
        // unreachable hint would be self-defeating. The `PingTimeout` signal above is still emitted —
        // it is advisory liveness telemetry the SWIM-gated FSM arbitrates independently.
        if (collector.peerLocallyAlive(peer)) {
            log.debug("ClusterSync: skipping ping-timeout unreachable hint for {} — SWIM says ALIVE (missed={})",
                      peer,
                      missed);

            return;
        }
        // Option 1 (S01) — feed the missed-pong observation into SWIM as a transport-unreachable
        // HINT instead of manufacturing a destructive `network.disconnect`. SWIM already drives the
        // full SUSPECT → 3s-floored-FAULTY → DepartedObserved → synchronous-DEAD pipeline (the same
        // path the QUIC `onPeerLeft` listener feeds), and the hint is naturally refuted when pongs
        // resume — so a transient flap no longer false-evicts a healthy peer. The reporter is wired
        // in `AetherNode` to `CoreSwimHealthDetector.recordTransportHint(PeerUnreachable)`.
        collector.reportUnreachable(peer);
    }

    public int bufferCap() {
        var peers = Math.max(topology.get().size() - 1,
                             0);
        var floor = Math.max(periodicConfig.capFloor().getAsInt(),
                             MIN_BUFFER_CAP);

        return Math.max(peers * PER_PEER_BURST, floor);
    }

    @Contract
    public void pushHealth(PeerHealthObservation observation) {
        observationStore.pushHealth(observation);
    }

    @Contract
    public void pushConnectivity(PeerConnectivityObservation observation) {
        observationStore.pushConnectivity(observation);
    }

    public List<PeerHealthObservation> drainHealth() {
        return observationStore.drainHealth();
    }

    public List<PeerConnectivityObservation> drainConnectivity() {
        return observationStore.drainConnectivity();
    }
}
