// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics;

import org.pragmatica.aether.slice.delegation.DelegatedComponent;
import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.HealthSignal;
import org.pragmatica.aether.slice.generation.HealthSignalSink;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPing;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.SnapshotPayload;
import org.pragmatica.cluster.metrics.PeerConnectivityObservation;
import org.pragmatica.cluster.metrics.PeerHealthObservation;
import org.pragmatica.cluster.metrics.PeerObservationBuffer;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeAdded;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeRemoved;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.consensus.topology.QuorumStateNotification;
import org.pragmatica.lang.concurrent.CancellableTask;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmState;
import org.pragmatica.statemachine.TransitionRequest;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Tier 1 cluster-sync scheduler. Runs on the leader node.
///
/// When this node is the leader, periodically sends `ClusterSyncPing` to all nodes.
/// Each node responds with `ClusterSyncPong` containing their metrics.
///
/// Commit 3 extension: the ping now carries the leader's current Rabia term,
/// the cluster-generation epoch, and — on epoch advance — the full
/// `ClusterGenerationSnapshot` serialized as a `SnapshotPayload`. The
/// scheduler tracks the last-sent epoch per target node to decide between
/// full-snapshot and heartbeat-only ping bodies (see spec §7.5).
public interface ClusterSyncScheduler extends DelegatedComponent, PeerObservationBuffer {
    int DEFAULT_PING_TIMEOUT_THRESHOLD = 3;

    @MessageReceiver@Contract void onTopologyChange(TopologyChangeNotification topologyChange);
    @MessageReceiver@Contract void onQuorumStateChange(QuorumStateNotification notification);
    @Contract void stop();
    @Contract void recordObservedEpoch(NodeId nodeId, Epoch epoch);
    Map<NodeId, Epoch> observedEpochs();
    @Contract void onPongReceived(NodeId nodeId);
    @Contract void sendPingsNow();
    @Contract@Override void pushHealth(PeerHealthObservation observation);
    @Contract@Override void pushConnectivity(PeerConnectivityObservation observation);
    @Override List<PeerHealthObservation> drainHealth();
    @Override List<PeerConnectivityObservation> drainConnectivity();

    /// Lifecycle states tracked by internal Fsm alongside the existing `active` AtomicBoolean.
    sealed interface SchedulerState extends FsmState<SchedulerState, LifecycleEvent>
            permits SchedulerState.Inactive, SchedulerState.Pinging {
        record Inactive() implements SchedulerState {
            public static final Inactive INSTANCE = new Inactive();
            @Override public void handle(LifecycleEvent event, TransitionRequest<SchedulerState, LifecycleEvent> tx) {
                switch (event) {
                    case LifecycleEvent.Activate _ -> tx.transitionTo(Pinging.INSTANCE);
                    default -> tx.ignore();
                }
            }
        }
        record Pinging() implements SchedulerState {
            public static final Pinging INSTANCE = new Pinging();
            @Override public void handle(LifecycleEvent event, TransitionRequest<SchedulerState, LifecycleEvent> tx) {
                switch (event) {
                    case LifecycleEvent.Deactivate _ -> tx.transitionTo(Inactive.INSTANCE);
                    default -> tx.ignore();
                }
            }
        }
    }

    sealed interface LifecycleEvent {
        record Activate() implements LifecycleEvent {}
        record Deactivate() implements LifecycleEvent {}
    }

    static ClusterSyncScheduler clusterSyncScheduler(NodeId self,
                                                     ClusterNetwork network,
                                                     ClusterSyncCollector clusterSyncCollector,
                                                     TimeSpan interval) {
        return new ClusterSyncSchedulerImpl(self,
                                            network,
                                            clusterSyncCollector,
                                            interval,
                                            () -> 0L,
                                            Option::none,
                                            _ -> new byte[0],
                                            HealthSignalSink.noop(),
                                            DEFAULT_PING_TIMEOUT_THRESHOLD,
                                            () -> Epoch.ZERO);
    }

    static ClusterSyncScheduler clusterSyncScheduler(NodeId self,
                                                     ClusterNetwork network,
                                                     ClusterSyncCollector clusterSyncCollector,
                                                     TimeSpan interval,
                                                     Supplier<Long> rabiaTermSupplier,
                                                     Supplier<Option<ClusterGenerationSnapshot>> snapshotSupplier,
                                                     Function<ClusterGenerationSnapshot, byte[]> snapshotEncoder) {
        return new ClusterSyncSchedulerImpl(self,
                                            network,
                                            clusterSyncCollector,
                                            interval,
                                            rabiaTermSupplier,
                                            snapshotSupplier,
                                            snapshotEncoder,
                                            HealthSignalSink.noop(),
                                            DEFAULT_PING_TIMEOUT_THRESHOLD,
                                            () -> Epoch.ZERO);
    }

    static ClusterSyncScheduler clusterSyncScheduler(NodeId self,
                                                     ClusterNetwork network,
                                                     ClusterSyncCollector clusterSyncCollector,
                                                     TimeSpan interval,
                                                     Supplier<Long> rabiaTermSupplier,
                                                     Supplier<Option<ClusterGenerationSnapshot>> snapshotSupplier,
                                                     Function<ClusterGenerationSnapshot, byte[]> snapshotEncoder,
                                                     HealthSignalSink signalSink,
                                                     int pingTimeoutThreshold,
                                                     Supplier<Epoch> epochSupplier) {
        return new ClusterSyncSchedulerImpl(self,
                                            network,
                                            clusterSyncCollector,
                                            interval,
                                            rabiaTermSupplier,
                                            snapshotSupplier,
                                            snapshotEncoder,
                                            signalSink,
                                            pingTimeoutThreshold,
                                            epochSupplier);
    }

    static ClusterSyncScheduler clusterSyncScheduler(NodeId self,
                                                     ClusterNetwork network,
                                                     ClusterSyncCollector clusterSyncCollector) {
        return clusterSyncScheduler(self,
                                    network,
                                    clusterSyncCollector,
                                    TimeSpan.timeSpan(1).seconds());
    }
}

class ClusterSyncSchedulerImpl implements ClusterSyncScheduler {
    private static final Logger log = LoggerFactory.getLogger(ClusterSyncSchedulerImpl.class);

    private static final int PER_PEER_BURST = 4;

    private static final int MIN_BUFFER_CAP = 8;

    private final NodeId self;
    private final ClusterNetwork network;
    private final ClusterSyncCollector clusterSyncCollector;
    private final TimeSpan interval;
    private final Supplier<Long> rabiaTermSupplier;
    private final Supplier<Option<ClusterGenerationSnapshot>> snapshotSupplier;
    private final Function<ClusterGenerationSnapshot, byte[]> snapshotEncoder;
    private final HealthSignalSink signalSink;
    private final int pingTimeoutThreshold;
    private final Supplier<Epoch> epochSupplier;

    private final CancellableTask pingTask = CancellableTask.cancellableTask();

    private final AtomicReference<List<NodeId>> topology = new AtomicReference<>(List.of());

    private final AtomicLong quorumSequence = new AtomicLong();

    private final AtomicBoolean active = new AtomicBoolean(false);

    private final Fsm<SchedulerState, LifecycleEvent> lifecycle =
        Fsm.fsm("cluster-sync-scheduler", SchedulerState.Inactive.INSTANCE);

    private final Map<NodeId, Epoch> lastSentEpoch = new ConcurrentHashMap<>();

    private final Map<NodeId, Epoch> observedEpoch = new ConcurrentHashMap<>();

    private final Map<NodeId, AtomicInteger> missedPings = new ConcurrentHashMap<>();

    private final Object healthBufferLock = new Object();

    private final Deque<PeerHealthObservation> healthBuffer = new ArrayDeque<>();

    private final Object connectivityBufferLock = new Object();

    private final Deque<PeerConnectivityObservation> connectivityBuffer = new ArrayDeque<>();

    ClusterSyncSchedulerImpl(NodeId self,
                             ClusterNetwork network,
                             ClusterSyncCollector clusterSyncCollector,
                             TimeSpan interval,
                             Supplier<Long> rabiaTermSupplier,
                             Supplier<Option<ClusterGenerationSnapshot>> snapshotSupplier,
                             Function<ClusterGenerationSnapshot, byte[]> snapshotEncoder,
                             HealthSignalSink signalSink,
                             int pingTimeoutThreshold,
                             Supplier<Epoch> epochSupplier) {
        this.self = self;
        this.network = network;
        this.clusterSyncCollector = clusterSyncCollector;
        this.interval = interval;
        this.rabiaTermSupplier = rabiaTermSupplier;
        this.snapshotSupplier = snapshotSupplier;
        this.snapshotEncoder = snapshotEncoder;
        this.signalSink = signalSink;
        this.pingTimeoutThreshold = pingTimeoutThreshold;
        this.epochSupplier = epochSupplier;
    }

    @Override public Promise<Unit> activate() {
        log.debug("Node {} activating cluster-sync scheduler", self);
        lifecycle.dispatch(new LifecycleEvent.Activate());
        active.set(true);
        startPinging();
        return Promise.unitPromise();
    }

    @Override public Promise<Unit> deactivate() {
        log.info("Node {} deactivating cluster-sync scheduler", self);
        lifecycle.dispatch(new LifecycleEvent.Deactivate());
        active.set(false);
        stopPinging();
        return Promise.unitPromise();
    }

    @Override public TaskGroup taskGroup() {
        return TaskGroup.METRICS;
    }

    @Override public boolean isActive() {
        return active.get();
    }

    @Override@Contract public void onTopologyChange(TopologyChangeNotification topologyChange) {
        switch (topologyChange){
            case NodeAdded(_, List<NodeId> newTopology) -> topology.set(newTopology);
            case NodeRemoved(NodeId removed, List<NodeId> newTopology) -> {
                topology.set(newTopology);
                lastSentEpoch.remove(removed);
                observedEpoch.remove(removed);
                missedPings.remove(removed);
            }
            default -> {}
        }
    }

    @Override@Contract public void onQuorumStateChange(QuorumStateNotification notification) {
        if (!notification.advanceSequence(quorumSequence)) {
            log.debug("Ignoring stale QuorumStateNotification: {}", notification);
            return;
        }
        if (notification.state() == QuorumStateNotification.State.DISAPPEARED) {
            log.info("Quorum disappeared, stopping cluster-sync scheduler");
            stopPinging();
        }
    }

    @Override@Contract public void stop() {
        stopPinging();
    }

    @Override@Contract public void recordObservedEpoch(NodeId nodeId, Epoch epoch) {
        observedEpoch.merge(nodeId, epoch, (prev, next) -> next.isStrictlyAfter(prev)
                                                          ? next
                                                          : prev);
    }

    @Override public Map<NodeId, Epoch> observedEpochs() {
        return Map.copyOf(observedEpoch);
    }

    @Override@Contract public void onPongReceived(NodeId nodeId) {
        var counter = missedPings.get(nodeId);
        if (counter != null) {counter.set(0);}
    }

    private void startPinging() {
        pingTask.set(SharedScheduler.scheduleAtFixedRate(this::sendPingsToAllNodes, interval));
    }

    private void stopPinging() {
        pingTask.cancel();
        synchronized (healthBufferLock) {
            healthBuffer.clear();
        }
        synchronized (connectivityBufferLock) {
            connectivityBuffer.clear();
        }
    }

    private void sendPingsToAllNodes() {
        try {
            var currentTopology = topology.get();
            if (currentTopology.isEmpty()) {return;}
            var rabiaTerm = rabiaTermSupplier.get();
            var maybeSnapshot = snapshotSupplier.get();
            var currentEpoch = maybeSnapshot.map(ClusterGenerationSnapshot::epoch).or(Epoch.ZERO);
            currentTopology.stream().filter(nodeId -> !nodeId.equals(self))
                                  .forEach(nodeId -> sendOnePing(nodeId, rabiaTerm, currentEpoch, maybeSnapshot));
            log.trace("Sent ClusterSyncPing to {} nodes at epoch {}", currentTopology.size() - 1, currentEpoch);
        } catch (Exception e) {
            log.warn("Failed to send cluster-sync ping: {}", e.getMessage());
        }
    }

    @Override@Contract public void sendPingsNow() {
        sendPingsToAllNodes();
    }

    @Override@Contract public void pushHealth(PeerHealthObservation observation) {
        if (observation == null) {return;}
        synchronized (healthBufferLock) {
            if (healthBuffer.size() >= bufferCap()) {
                healthBuffer.pollFirst();
                log.trace("peer-health buffer overflow; dropping oldest observation");
            }
            healthBuffer.offerLast(observation);
        }
    }

    @Override@Contract public void pushConnectivity(PeerConnectivityObservation observation) {
        if (observation == null) {return;}
        synchronized (connectivityBufferLock) {
            if (connectivityBuffer.size() >= bufferCap()) {
                connectivityBuffer.pollFirst();
                log.trace("peer-connectivity buffer overflow; dropping oldest observation");
            }
            connectivityBuffer.offerLast(observation);
        }
    }

    @Override public List<PeerHealthObservation> drainHealth() {
        synchronized (healthBufferLock) {
            if (healthBuffer.isEmpty()) {return List.of();}
            var drained = new ArrayList<>(healthBuffer);
            healthBuffer.clear();
            return List.copyOf(drained);
        }
    }

    @Override public List<PeerConnectivityObservation> drainConnectivity() {
        synchronized (connectivityBufferLock) {
            if (connectivityBuffer.isEmpty()) {return List.of();}
            var drained = new ArrayList<>(connectivityBuffer);
            connectivityBuffer.clear();
            return List.copyOf(drained);
        }
    }

    private int bufferCap() {
        var peers = Math.max(topology.get().size() - 1,
                             0);
        return Math.max(peers * PER_PEER_BURST, MIN_BUFFER_CAP);
    }

    private void sendOnePing(NodeId nodeId,
                             long rabiaTerm,
                             Epoch currentEpoch,
                             Option<ClusterGenerationSnapshot> maybeSnapshot) {
        var payload = buildPayloadForTarget(nodeId, currentEpoch, maybeSnapshot);
        var ping = new ClusterSyncPing(self,
                                       clusterSyncCollector.allMetrics(),
                                       rabiaTerm,
                                       currentEpoch.rabiaTerm(),
                                       currentEpoch.localCounter(),
                                       payload);
        network.send(nodeId, ping);
        lastSentEpoch.put(nodeId, currentEpoch);
        maybeEmitPingTimeout(nodeId);
    }

    private void maybeEmitPingTimeout(NodeId nodeId) {
        var missed = missedPings.computeIfAbsent(nodeId, _ -> new AtomicInteger()).incrementAndGet();
        if (missed <pingTimeoutThreshold) {return;}
        signalSink.emit(new HealthSignal.PingTimeout(nodeId, missed, epochSupplier.get()));
    }

    private Option<SnapshotPayload> buildPayloadForTarget(NodeId nodeId,
                                                          Epoch currentEpoch,
                                                          Option<ClusterGenerationSnapshot> maybeSnapshot) {
        var lastSent = lastSentEpoch.get(nodeId);
        if (lastSent != null && !currentEpoch.isStrictlyAfter(lastSent)) {return Option.none();}
        return maybeSnapshot.map(snapshotEncoder::apply).map(SnapshotPayload::snapshotPayload);
    }
}
