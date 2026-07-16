// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.controller;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.controller.fsm.ControlLoopContext;
import org.pragmatica.aether.controller.fsm.ControlLoopEvents.Activate;
import org.pragmatica.aether.controller.fsm.ControlLoopEvents.Deactivate;
import org.pragmatica.aether.controller.fsm.ControlLoopState;
import org.pragmatica.aether.controller.fsm.ScalingDecisionRecord;
import org.pragmatica.aether.metrics.ClusterSyncCollector;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.worker.metrics.CommunityMetricsSnapshot;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.consensus.topology.MembershipDecision.NodeDecommissioned;
import org.pragmatica.consensus.topology.MembershipDecision.NodeJoined;
import org.pragmatica.consensus.topology.MembershipDecision.NodeRemoved;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.statemachine.Fsm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@Contract
public interface ControlLoop {
    Promise<Unit> activate();
    Promise<Unit> deactivate();
    boolean isActive();

    @MessageReceiver
    void onMembershipDecision(MembershipDecision decision);

    @MessageReceiver
    void onSliceTargetPut(ValuePut<SliceTargetKey, SliceTargetValue> valuePut);

    @MessageReceiver
    void onSliceTargetRemove(ValueRemove<SliceTargetKey, SliceTargetValue> valueRemove);

    @MessageReceiver
    void onNodeArtifactPut(ValuePut<NodeArtifactKey, NodeArtifactValue> valuePut);

    @MessageReceiver
    void onNodeArtifactRemove(ValueRemove<NodeArtifactKey, NodeArtifactValue> valueRemove);

    @MessageReceiver
    void onQuorumStateChange(ClusterStateNotification notification);

    void registerBlueprint(Artifact artifact,
                           int instances,
                           int minInstances,
                           Option<Integer> maxInstances,
                           Option<Double> scaleUpThreshold,
                           Option<Double> scaleDownThreshold);

    void unregisterBlueprint(Artifact artifact);
    ControllerConfig configuration();
    void updateConfiguration(ControllerConfig config);
    void stop();
    void onCommunityMetricsSnapshot(CommunityMetricsSnapshot snapshot);
    Map<String, CommunityMetricsSnapshot> communitySnapshots();
    /// Bounded snapshot of the latest per-artifact scaling decision (#425). Leader-only observability
    /// surface; empty on nodes whose control loop has not evaluated.
    Map<Artifact, ScalingDecisionRecord> scalingDecisions();
    /// Cluster-average CPU usage surfaced alongside [scalingDecisions] as honest node-capacity
    /// context (never acted on by the autoscaler).
    double clusterCpuContext();

    static ControlLoop controlLoop(NodeId self,
                                   ClusterController controller,
                                   ClusterSyncCollector metricsCollector,
                                   Option<InvocationMetricsCollector> invocationMetricsCollector,
                                   ClusterNode<KVCommand<AetherKey>> cluster,
                                   TimeSpan interval,
                                   ControllerConfig config,
                                   Consumer<ScalingEvent> eventPublisher) {
        var ctxHolder = new AtomicReference<ControlLoopContext>();
        Function<Fsm<ControlLoopState, ClusterFsmEvent>, ControlLoopState> initialStateFactory = fsm -> buildContext(ctxHolder,
                                                                                                                     fsm,
                                                                                                                     self,
                                                                                                                     controller,
                                                                                                                     metricsCollector,
                                                                                                                     invocationMetricsCollector,
                                                                                                                     cluster,
                                                                                                                     interval,
                                                                                                                     config,
                                                                                                                     eventPublisher);
        var fsm = Fsm.fsm("control-loop", self.id(), initialStateFactory);

        return new ControlLoopAdapter(ctxHolder.get(), fsm);
    }

    private static ControlLoopState buildContext(AtomicReference<ControlLoopContext> ctxHolder,
                                                 Fsm<ControlLoopState, ClusterFsmEvent> fsm,
                                                 NodeId self,
                                                 ClusterController controller,
                                                 ClusterSyncCollector metricsCollector,
                                                 Option<InvocationMetricsCollector> invocationMetricsCollector,
                                                 ClusterNode<KVCommand<AetherKey>> cluster,
                                                 TimeSpan interval,
                                                 ControllerConfig config,
                                                 Consumer<ScalingEvent> eventPublisher) {
        var ctx = new ControlLoopContext(fsm,
                                         self,
                                         controller,
                                         metricsCollector,
                                         invocationMetricsCollector,
                                         cluster,
                                         interval,
                                         config,
                                         eventPublisher);

        ctxHolder.set(ctx);

        return ctx.dormant();
    }

    record ControlLoopAdapter(ControlLoopContext ctx, Fsm<ControlLoopState, ClusterFsmEvent> fsm) implements ControlLoop {
        private static final Logger log = LoggerFactory.getLogger(ControlLoop.class);

        @Override
        public Promise<Unit> activate() {
            log.info("Node {} activating control loop", ctx.self());
            fsm.dispatch(new Activate());

            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> deactivate() {
            log.info("Node {} deactivating control loop", ctx.self());
            fsm.dispatch(new Deactivate());

            return Promise.unitPromise();
        }

        @Override
        public boolean isActive() {
            var current = fsm.current();

            return ! (current instanceof ControlLoopState.Dormant || current instanceof ControlLoopState.Stopped);
        }

        @Override
        public void onMembershipDecision(MembershipDecision decision) {
            switch (decision) {
                case NodeJoined(_, var newTopology, _, _) -> ctx.setTopology(newTopology);
                case NodeRemoved(var departed, var newTopology, _, _) -> ctx.onNodeDeparted(departed, newTopology);
                case NodeDecommissioned(var departed, var newTopology, _, _) -> ctx.onNodeDeparted(departed, newTopology);
                // RC1 Step 2 lifecycle-projection variants: ControlLoop reacts to terminal
                // topology changes only — JOINING / DRAINING / FAILED_DRAIN /
                // SHUTTING_DOWN do not adjust the committed core set.
                case MembershipDecision.NodeJoining _, MembershipDecision.NodeDraining _, MembershipDecision.NodeFailedDrain _, MembershipDecision.NodeShuttingDown _ -> {}
            }
        }

        @Override
        public void onQuorumStateChange(ClusterStateNotification notification) {
            if (!notification.advanceSequence(ctx.quorumSequence())) {
                log.debug("Ignoring stale ClusterStateNotification: {}", notification);

                return;
            }

            switch (notification.state()) {
                case ACTIVE -> fsm.dispatch(new ClusterFsmEvent.QuorumEstablished());
                case PASSIVE -> fsm.dispatch(new ClusterFsmEvent.QuorumDisappeared());
            }
        }

        @Override
        public void onSliceTargetPut(ValuePut<SliceTargetKey, SliceTargetValue> valuePut) {
            var key = valuePut.cause().key();
            var value = valuePut.cause().value();

            registerBlueprint(key.artifactBase().withVersion(value.currentVersion()),
                              value.targetInstances(),
                              value.effectiveMinInstances(),
                              value.maxInstances(),
                              value.scaleUpThreshold(),
                              value.scaleDownThreshold());
        }

        @Override
        public void onSliceTargetRemove(ValueRemove<SliceTargetKey, SliceTargetValue> valueRemove) {
            ctx.removeBlueprintMatching(valueRemove.cause().key());
        }

        @Override
        public void onNodeArtifactPut(ValuePut<NodeArtifactKey, NodeArtifactValue> valuePut) {
            var key = valuePut.cause().key();
            var value = valuePut.cause().value();

            ctx.recordSliceState(new SliceNodeKey(key.artifact(), key.nodeId()),
                                 value.state());
        }

        @Override
        public void onNodeArtifactRemove(ValueRemove<NodeArtifactKey, NodeArtifactValue> valueRemove) {
            var key = valueRemove.cause().key();

            ctx.clearSliceState(new SliceNodeKey(key.artifact(), key.nodeId()));
        }

        @Override
        public void registerBlueprint(Artifact artifact,
                                      int instances,
                                      int minInstances,
                                      Option<Integer> maxInstances,
                                      Option<Double> scaleUpThreshold,
                                      Option<Double> scaleDownThreshold) {
            ctx.putBlueprint(artifact, instances, minInstances, maxInstances, scaleUpThreshold, scaleDownThreshold);
            log.info("Registered blueprint: {} with {} instances (min: {}, max: {})",
                     artifact,
                     instances,
                     minInstances,
                     maxInstances);
        }

        @Override
        public void unregisterBlueprint(Artifact artifact) {
            ctx.removeBlueprint(artifact);
            log.info("Unregistered blueprint: {}", artifact);
        }

        @Override
        public ControllerConfig configuration() {
            return ctx.config();
        }

        @Override
        public void updateConfiguration(ControllerConfig config) {
            ctx.setConfig(config);
        }

        @Override
        public void stop() {
            fsm.dispatch(new ClusterFsmEvent.Shutdown());
        }

        @Override
        public void onCommunityMetricsSnapshot(CommunityMetricsSnapshot snapshot) {
            ctx.storeCommunitySnapshot(snapshot);
        }

        @Override
        public Map<String, CommunityMetricsSnapshot> communitySnapshots() {
            return ctx.communitySnapshots();
        }

        @Override
        public Map<Artifact, ScalingDecisionRecord> scalingDecisions() {
            return ctx.scalingDecisions();
        }

        @Override
        public double clusterCpuContext() {
            return ctx.clusterCpuContext();
        }
    }
}
