// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics;

import org.pragmatica.aether.metrics.fsm.ClusterSyncContext;
import org.pragmatica.aether.metrics.fsm.ClusterSyncEvents;
import org.pragmatica.aether.metrics.fsm.ClusterSyncState;
import org.pragmatica.aether.metrics.observation.PeerObservationStore;
import org.pragmatica.aether.slice.delegation.DelegatedComponent;
import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.HealthSignalSink;
import org.pragmatica.cluster.metrics.PeerConnectivityObservation;
import org.pragmatica.cluster.metrics.PeerHealthObservation;
import org.pragmatica.cluster.metrics.PeerObservationBuffer;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.consensus.topology.MembershipDecision.NodeDecommissioned;
import org.pragmatica.consensus.topology.MembershipDecision.NodeJoined;
import org.pragmatica.consensus.topology.MembershipDecision.NodeRemoved;
import org.pragmatica.consensus.topology.QuorumStateNotification;
import org.pragmatica.lang.Contract;
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


public interface ClusterSyncScheduler extends DelegatedComponent, PeerObservationBuffer {
    int DEFAULT_PING_TIMEOUT_THRESHOLD = 3;

    @MessageReceiver@Contract void onMembershipDecision(MembershipDecision decision);
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
                                    HealthSignalSink.noop(),
                                    DEFAULT_PING_TIMEOUT_THRESHOLD,
                                    () -> Epoch.ZERO,
                                    PeerObservationStore.peerObservationStore());
    }

    static ClusterSyncScheduler clusterSyncScheduler(NodeId self,
                                                     ClusterNetwork network,
                                                     ClusterSyncCollector clusterSyncCollector,
                                                     TimeSpan interval,
                                                     Supplier<Long> rabiaTermSupplier) {
        return clusterSyncScheduler(self,
                                    network,
                                    clusterSyncCollector,
                                    interval,
                                    rabiaTermSupplier,
                                    HealthSignalSink.noop(),
                                    DEFAULT_PING_TIMEOUT_THRESHOLD,
                                    () -> Epoch.ZERO,
                                    PeerObservationStore.peerObservationStore());
    }

    static ClusterSyncScheduler clusterSyncScheduler(NodeId self,
                                                     ClusterNetwork network,
                                                     ClusterSyncCollector clusterSyncCollector,
                                                     TimeSpan interval,
                                                     Supplier<Long> rabiaTermSupplier,
                                                     HealthSignalSink signalSink,
                                                     int pingTimeoutThreshold,
                                                     Supplier<Epoch> epochSupplier) {
        return clusterSyncScheduler(self,
                                    network,
                                    clusterSyncCollector,
                                    interval,
                                    rabiaTermSupplier,
                                    signalSink,
                                    pingTimeoutThreshold,
                                    epochSupplier,
                                    PeerObservationStore.peerObservationStore());
    }

    static ClusterSyncScheduler clusterSyncScheduler(NodeId self,
                                                     ClusterNetwork network,
                                                     ClusterSyncCollector clusterSyncCollector,
                                                     TimeSpan interval,
                                                     Supplier<Long> rabiaTermSupplier,
                                                     HealthSignalSink signalSink,
                                                     int pingTimeoutThreshold,
                                                     Supplier<Epoch> epochSupplier,
                                                     PeerObservationStore observationStore) {
        var ctxHolder = new AtomicReference<ClusterSyncContext>();
        Function<Fsm<ClusterSyncState, ClusterFsmEvent>, ClusterSyncState> initialStateFactory = fsm -> buildContextAndDormant(fsm,
                                                                                                                               ctxHolder,
                                                                                                                               self,
                                                                                                                               network,
                                                                                                                               clusterSyncCollector,
                                                                                                                               interval,
                                                                                                                               rabiaTermSupplier,
                                                                                                                               signalSink,
                                                                                                                               pingTimeoutThreshold,
                                                                                                                               epochSupplier,
                                                                                                                               observationStore);
        var _fsm = Fsm.fsm("cluster-sync", self.id(), initialStateFactory);
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
                                                           HealthSignalSink signalSink,
                                                           int pingTimeoutThreshold,
                                                           Supplier<Epoch> epochSupplier,
                                                           PeerObservationStore observationStore) {
        var ctx = new ClusterSyncContext(fsm,
                                         self,
                                         network,
                                         collector,
                                         interval,
                                         rabiaTermSupplier,
                                         signalSink,
                                         pingTimeoutThreshold,
                                         epochSupplier,
                                         observationStore);
        ctxHolder.set(ctx);
        return ctx.dormant();
    }
}

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

    @Override@Contract public void onMembershipDecision(MembershipDecision decision) {
        switch (decision){
            case NodeJoined(_, List<NodeId> newTopology) -> context.setTopology(newTopology);
            case NodeRemoved(NodeId removed, List<NodeId> newTopology) -> handleNodeRemoved(removed, newTopology);
            case NodeDecommissioned(NodeId removed, List<NodeId> newTopology) -> handleNodeRemoved(removed, newTopology);
        }
    }

    private void handleNodeRemoved(NodeId removed, List<NodeId> newTopology) {
        context.setTopology(newTopology);
        context.forgetPeer(removed);
        context.dispatch(new ClusterFsmEvent.NodeGone(removed, newTopology));
    }

    @Override@Contract public void onQuorumStateChange(QuorumStateNotification notification) {
        if (!notification.advanceSequence(context.quorumSequence())) {return;}
        switch (notification.state()){
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
