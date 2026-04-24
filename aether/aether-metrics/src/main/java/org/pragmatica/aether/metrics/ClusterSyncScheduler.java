// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics;

import org.pragmatica.aether.metrics.fsm.ClusterSyncContext;
import org.pragmatica.aether.metrics.fsm.ClusterSyncEvents;
import org.pragmatica.aether.metrics.fsm.ClusterSyncState;
import org.pragmatica.aether.slice.delegation.DelegatedComponent;
import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.HealthSignalSink;
import org.pragmatica.cluster.metrics.PeerConnectivityObservation;
import org.pragmatica.cluster.metrics.PeerHealthObservation;
import org.pragmatica.cluster.metrics.PeerObservationBuffer;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.topology.QuorumStateNotification;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeAdded;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeRemoved;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.statemachine.Fsm;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;


/// Tier 1 cluster-sync scheduler. Runs on the leader node.
///
/// When this node is the leader, periodically sends `ClusterSyncPing` to all nodes.
/// Each node responds with `ClusterSyncPong` containing their metrics.
///
/// Lifecycle is owned by an internal FSM ([`ClusterSyncState`] + [`ClusterSyncContext`]) —
/// `Dormant` (no pings) → `Pinging` (periodic ticks) → `Stopped` (terminal). External calls are
/// translated into dispatches on the FSM; the state records hold per-peer `lastSentEpoch` and
/// `missedPings` as immutable maps (see [`ClusterSyncState.Pinging`] Javadoc).
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

    static ClusterSyncScheduler clusterSyncScheduler(NodeId self,
                                                     ClusterNetwork network,
                                                     ClusterSyncCollector clusterSyncCollector,
                                                     TimeSpan interval) {
        return clusterSyncScheduler(self,
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
        return clusterSyncScheduler(self,
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
        var ctxHolder = new AtomicReference<ClusterSyncContext>();
        var fsmName = "cluster-sync-scheduler-" + self.id();
        Function<Fsm<ClusterSyncState, ClusterFsmEvent>, ClusterSyncState> initialStateFactory =
                fsm -> buildContextAndDormant(fsm,
                                              ctxHolder,
                                              self,
                                              network,
                                              clusterSyncCollector,
                                              interval,
                                              rabiaTermSupplier,
                                              snapshotSupplier,
                                              snapshotEncoder,
                                              signalSink,
                                              pingTimeoutThreshold,
                                              epochSupplier);
        // Fsm constructor publishes itself into ctxHolder via initialStateFactory —
        // we only need the context here; the FSM reference lives on ctx.fsm().
        var _fsm = Fsm.fsm(fsmName, initialStateFactory);
        return new ClusterSyncSchedulerAdapter(ctxHolder.get());
    }

    static ClusterSyncScheduler clusterSyncScheduler(NodeId self,
                                                     ClusterNetwork network,
                                                     ClusterSyncCollector clusterSyncCollector) {
        return clusterSyncScheduler(self,
                                    network,
                                    clusterSyncCollector,
                                    TimeSpan.timeSpan(1).seconds());
    }

    private static ClusterSyncState buildContextAndDormant(Fsm<ClusterSyncState, ClusterFsmEvent> fsm,
                                                           AtomicReference<ClusterSyncContext> ctxHolder,
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
        var ctx = new ClusterSyncContext(fsm,
                                         self,
                                         network,
                                         collector,
                                         interval,
                                         rabiaTermSupplier,
                                         snapshotSupplier,
                                         snapshotEncoder,
                                         signalSink,
                                         pingTimeoutThreshold,
                                         epochSupplier);
        ctxHolder.set(ctx);
        return ctx.dormant();
    }
}

/// Thin adapter: translates the public [`ClusterSyncScheduler`] surface into FSM dispatches and
/// context calls. All lifecycle state (Dormant / Pinging / Stopped), per-peer `lastSentEpoch` /
/// `missedPings` accounting, and the scheduled ping task live inside the FSM/context.
final class ClusterSyncSchedulerAdapter implements ClusterSyncScheduler {

    private final ClusterSyncContext context;

    ClusterSyncSchedulerAdapter(ClusterSyncContext context) {
        this.context = context;
    }

    @Override public Promise<Unit> activate() {
        context.dispatch(new ClusterFsmEvent.QuorumEstablished());
        return Promise.unitPromise();
    }

    @Override public Promise<Unit> deactivate() {
        context.dispatch(new ClusterFsmEvent.QuorumDisappeared());
        return Promise.unitPromise();
    }

    @Override public TaskGroup taskGroup() {
        return TaskGroup.METRICS;
    }

    @Override public boolean isActive() {
        return context.fsm().current() instanceof ClusterSyncState.Pinging;
    }

    @Override@Contract public void onTopologyChange(TopologyChangeNotification topologyChange) {
        switch (topologyChange) {
            case NodeAdded(_, List<NodeId> newTopology) -> context.setTopology(newTopology);
            case NodeRemoved(NodeId removed, List<NodeId> newTopology) -> handleNodeRemoved(removed, newTopology);
            default -> {}
        }
    }

    private void handleNodeRemoved(NodeId removed, List<NodeId> newTopology) {
        context.setTopology(newTopology);
        context.forgetPeer(removed);
        context.dispatch(new ClusterFsmEvent.NodeGone(removed, newTopology));
    }

    @Override@Contract public void onQuorumStateChange(QuorumStateNotification notification) {
        if (!notification.advanceSequence(context.quorumSequence())) { return; }
        switch (notification.state()) {
            case ESTABLISHED -> context.dispatch(new ClusterFsmEvent.QuorumEstablished());
            case DISAPPEARED -> context.dispatch(new ClusterFsmEvent.QuorumDisappeared());
        }
    }

    @Override@Contract public void stop() {
        context.dispatch(new ClusterFsmEvent.Shutdown());
    }

    @Override@Contract public void recordObservedEpoch(NodeId nodeId, Epoch epoch) {
        context.recordObservedEpoch(nodeId, epoch);
    }

    @Override public Map<NodeId, Epoch> observedEpochs() {
        return context.observedEpochs();
    }

    @Override@Contract public void onPongReceived(NodeId nodeId) {
        context.dispatch(new ClusterSyncEvents.PongReceived(nodeId));
    }

    @Override@Contract public void sendPingsNow() {
        context.dispatch(new ClusterSyncEvents.PingTick(context.epochSupplier().get()));
    }

    @Override@Contract public void pushHealth(PeerHealthObservation observation) {
        context.pushHealth(observation);
    }

    @Override@Contract public void pushConnectivity(PeerConnectivityObservation observation) {
        context.pushConnectivity(observation);
    }

    @Override public List<PeerHealthObservation> drainHealth() {
        return context.drainHealth();
    }

    @Override public List<PeerConnectivityObservation> drainConnectivity() {
        return context.drainConnectivity();
    }
}
