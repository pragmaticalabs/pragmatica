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
import org.pragmatica.cluster.metrics.MetricsMessage.MetricsPing;
import org.pragmatica.cluster.metrics.MetricsMessage.SnapshotPayload;
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


/// Scheduler for metrics collection that runs on the leader node.
///
/// When this node is the leader, periodically sends `MetricsPing` to all nodes.
/// Each node responds with `MetricsPong` containing their metrics.
///
/// Commit 3 extension: the ping now carries the leader's current Rabia term,
/// the cluster-generation epoch, and — on epoch advance — the full
/// `ClusterGenerationSnapshot` serialized as a `SnapshotPayload`. The
/// scheduler tracks the last-sent epoch per target node to decide between
/// full-snapshot and heartbeat-only ping bodies (see spec §7.5).
public interface MetricsScheduler extends DelegatedComponent {
    int DEFAULT_PING_TIMEOUT_THRESHOLD = 3;

    @MessageReceiver@Contract void onTopologyChange(TopologyChangeNotification topologyChange);
    @MessageReceiver@Contract void onQuorumStateChange(QuorumStateNotification notification);
    @Contract void stop();
    @Contract void recordObservedEpoch(NodeId nodeId, Epoch epoch);
    Map<NodeId, Epoch> observedEpochs();
    @Contract void onPongReceived(NodeId nodeId);

    static MetricsScheduler metricsScheduler(NodeId self,
                                             ClusterNetwork network,
                                             MetricsCollector metricsCollector,
                                             TimeSpan interval) {
        return new MetricsSchedulerImpl(self,
                                        network,
                                        metricsCollector,
                                        interval,
                                        () -> 0L,
                                        Option::none,
                                        _ -> new byte[0],
                                        HealthSignalSink.noop(),
                                        DEFAULT_PING_TIMEOUT_THRESHOLD,
                                        () -> Epoch.ZERO);
    }

    static MetricsScheduler metricsScheduler(NodeId self,
                                             ClusterNetwork network,
                                             MetricsCollector metricsCollector,
                                             TimeSpan interval,
                                             Supplier<Long> rabiaTermSupplier,
                                             Supplier<Option<ClusterGenerationSnapshot>> snapshotSupplier,
                                             Function<ClusterGenerationSnapshot, byte[]> snapshotEncoder) {
        return new MetricsSchedulerImpl(self,
                                        network,
                                        metricsCollector,
                                        interval,
                                        rabiaTermSupplier,
                                        snapshotSupplier,
                                        snapshotEncoder,
                                        HealthSignalSink.noop(),
                                        DEFAULT_PING_TIMEOUT_THRESHOLD,
                                        () -> Epoch.ZERO);
    }

    static MetricsScheduler metricsScheduler(NodeId self,
                                             ClusterNetwork network,
                                             MetricsCollector metricsCollector,
                                             TimeSpan interval,
                                             Supplier<Long> rabiaTermSupplier,
                                             Supplier<Option<ClusterGenerationSnapshot>> snapshotSupplier,
                                             Function<ClusterGenerationSnapshot, byte[]> snapshotEncoder,
                                             HealthSignalSink signalSink,
                                             int pingTimeoutThreshold,
                                             Supplier<Epoch> epochSupplier) {
        return new MetricsSchedulerImpl(self,
                                        network,
                                        metricsCollector,
                                        interval,
                                        rabiaTermSupplier,
                                        snapshotSupplier,
                                        snapshotEncoder,
                                        signalSink,
                                        pingTimeoutThreshold,
                                        epochSupplier);
    }

    static MetricsScheduler metricsScheduler(NodeId self, ClusterNetwork network, MetricsCollector metricsCollector) {
        return metricsScheduler(self,
                                network,
                                metricsCollector,
                                TimeSpan.timeSpan(1).seconds());
    }
}

class MetricsSchedulerImpl implements MetricsScheduler {
    private static final Logger log = LoggerFactory.getLogger(MetricsSchedulerImpl.class);

    private final NodeId self;
    private final ClusterNetwork network;
    private final MetricsCollector metricsCollector;
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

    private final Map<NodeId, Epoch> lastSentEpoch = new ConcurrentHashMap<>();

    private final Map<NodeId, Epoch> observedEpoch = new ConcurrentHashMap<>();

    private final Map<NodeId, AtomicInteger> missedPings = new ConcurrentHashMap<>();

    MetricsSchedulerImpl(NodeId self,
                         ClusterNetwork network,
                         MetricsCollector metricsCollector,
                         TimeSpan interval,
                         Supplier<Long> rabiaTermSupplier,
                         Supplier<Option<ClusterGenerationSnapshot>> snapshotSupplier,
                         Function<ClusterGenerationSnapshot, byte[]> snapshotEncoder,
                         HealthSignalSink signalSink,
                         int pingTimeoutThreshold,
                         Supplier<Epoch> epochSupplier) {
        this.self = self;
        this.network = network;
        this.metricsCollector = metricsCollector;
        this.interval = interval;
        this.rabiaTermSupplier = rabiaTermSupplier;
        this.snapshotSupplier = snapshotSupplier;
        this.snapshotEncoder = snapshotEncoder;
        this.signalSink = signalSink;
        this.pingTimeoutThreshold = pingTimeoutThreshold;
        this.epochSupplier = epochSupplier;
    }

    @Override public Promise<Unit> activate() {
        log.debug("Node {} activating metrics scheduler", self);
        active.set(true);
        startPinging();
        return Promise.unitPromise();
    }

    @Override public Promise<Unit> deactivate() {
        log.info("Node {} deactivating metrics scheduler", self);
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
            log.info("Quorum disappeared, stopping metrics scheduler");
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
            log.trace("Sent MetricsPing to {} nodes at epoch {}", currentTopology.size() - 1, currentEpoch);
        } catch (Exception e) {
            log.warn("Failed to send metrics ping: {}", e.getMessage());
        }
    }

    @Contract void sendPingsNow() {
        sendPingsToAllNodes();
    }

    private void sendOnePing(NodeId nodeId,
                             long rabiaTerm,
                             Epoch currentEpoch,
                             Option<ClusterGenerationSnapshot> maybeSnapshot) {
        var payload = buildPayloadForTarget(nodeId, currentEpoch, maybeSnapshot);
        var ping = new MetricsPing(self,
                                   metricsCollector.allMetrics(),
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
