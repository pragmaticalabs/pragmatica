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
import org.pragmatica.cluster.metrics.AggregatedReachabilitySnapshot;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPing;
import org.pragmatica.cluster.metrics.PeerConnectivityObservation;
import org.pragmatica.cluster.metrics.PeerHealthObservation;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
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

    private final AtomicReference<List<NodeId>> topology = new AtomicReference<>(List.of());

    private final AtomicLong quorumSequence = new AtomicLong();

    private final Map<NodeId, Epoch> observedEpoch = new ConcurrentHashMap<>();

    private final PeerObservationStore observationStore;
    private final Supplier<Option<AggregatedReachabilitySnapshot>> reachabilitySnapshotSupplier;

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
             Option::none,
             PeriodicObservationConfig.defaultConfig());
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
                              Supplier<Option<AggregatedReachabilitySnapshot>> reachabilitySnapshotSupplier) {
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
             reachabilitySnapshotSupplier,
             PeriodicObservationConfig.defaultConfig());
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
                              Supplier<Option<AggregatedReachabilitySnapshot>> reachabilitySnapshotSupplier,
                              PeriodicObservationConfig periodicConfig) {
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
        this.reachabilitySnapshotSupplier = reachabilitySnapshotSupplier == null
                                            ? Option::none
                                            : reachabilitySnapshotSupplier;
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

    @Contract public void dispatch(ClusterFsmEvent event) {
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

    @Contract public void setTopology(List<NodeId> newTopology) {
        topology.set(newTopology);
    }

    @Contract public void recordObservedEpoch(NodeId nodeId, Epoch epoch) {
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

    @Contract public void forgetPeer(NodeId peer) {
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
    @Contract public void emitPeriodicConnectivityNow() {
        var currentTopology = topology.get();
        if (currentTopology == null || currentTopology.isEmpty()) {return;}
        var topologySet = Set.copyOf(currentTopology);
        var connected = new HashSet<>(network.connectedPeers());
        collector.emitPeriodicConnectivity(topologySet, connected, self, System.currentTimeMillis());
    }

    @Contract public void clearObservationBuffers() {
        observationStore.clear();
    }

    public PeerObservationStore observationStore() {
        return observationStore;
    }

    public Epoch sendOnePing(NodeId peer, Epoch currentEpoch, long rabiaTerm) {
        var ping = new ClusterSyncPing(self,
                                       collector.allMetrics(),
                                       rabiaTerm,
                                       currentEpoch.rabiaTerm(),
                                       currentEpoch.localCounter(),
                                       reachabilitySnapshotSupplier.get(),
                                       currentEvictionHints());
        log.debug("ClusterSync: sending PING to {} (rabiaTerm={}, epoch={}:{})",
                  peer,
                  rabiaTerm,
                  currentEpoch.rabiaTerm(),
                  currentEpoch.localCounter());
        network.send(peer, ping);
        return currentEpoch;
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

    @Contract public void emitPingTimeoutIfExceeded(NodeId peer, int missed) {
        if (missed <pingTimeoutThreshold) {return;}
        signalSink.emit(new HealthSignal.PingTimeout(peer, missed, epochSupplier.get()));
        // RC1 (S01 fix): app-level liveness detection. QUIC's MAX_IDLE_TIMEOUT is
        // intentionally disabled (cluster connections are persistent per QUIC RFC 9000
        // §10.1), so the QUIC layer has no autonomous dead-peer detection — UDP sends
        // are fire-and-forget. The cluster-sync ping/pong cycle IS an app-level liveness
        // signal: `pingTimeoutThreshold` consecutive missed pongs (default 3 = ~3s) means
        // the peer is unresponsive. Without this local disconnect, `ReachabilityAggregator.
        // foldSelfObservations` would keep voting REACHABLE for the unresponsive peer
        // (its QUIC `PeerState` stays CONNECTED), diluting follower UNREACHABLE evidence
        // and forcing the FSM to wait on SWIM's 10s suspectTimeout floor for transport-
        // gated decommission. Local disconnect here strips the false REACHABLE vote and
        // lets the aggregator converge within the ping-timeout window. Disconnect is
        // idempotent (peer.evict() guards against double-eviction). See spec §16 S01/S02
        // and the QuicClusterClient.java:139 / QuicClusterServer.java:136 idle-timeout note.
        network.disconnect(new NetworkServiceMessage.DisconnectNode(peer));
        // Track this eviction so the next `ClusterSyncPing` broadcasts it as a
        // SUGGESTION to followers. Followers verify against their own `lastReceivedNanos`
        // before acting — owner's eviction is informational, not authoritative.
        evictionHints.put(peer, System.nanoTime());
    }

    public int bufferCap() {
        var peers = Math.max(topology.get().size() - 1,
                             0);
        var floor = Math.max(periodicConfig.capFloor().getAsInt(), MIN_BUFFER_CAP);
        return Math.max(peers * PER_PEER_BURST, floor);
    }

    @Contract public void pushHealth(PeerHealthObservation observation) {
        observationStore.pushHealth(observation);
    }

    @Contract public void pushConnectivity(PeerConnectivityObservation observation) {
        observationStore.pushConnectivity(observation);
    }

    public List<PeerHealthObservation> drainHealth() {
        return observationStore.drainHealth();
    }

    public List<PeerConnectivityObservation> drainConnectivity() {
        return observationStore.drainConnectivity();
    }
}
