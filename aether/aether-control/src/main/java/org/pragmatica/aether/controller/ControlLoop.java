// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.controller;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.controller.fsm.ControlLoopContext;
import org.pragmatica.aether.controller.fsm.ControlLoopEvents.Activate;
import org.pragmatica.aether.controller.fsm.ControlLoopEvents.Deactivate;
import org.pragmatica.aether.controller.fsm.ControlLoopState;
import org.pragmatica.aether.metrics.ClusterSyncCollector;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.slice.delegation.DelegatedComponent;
import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.worker.metrics.CommunityMetricsSnapshot;
import org.pragmatica.aether.worker.metrics.CommunityScalingRequest;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;


/// Control loop that runs the [`ClusterController`] periodically on the leader node.
///
/// Responsibilities:
///
///   - Run only on leader node (driven by `DelegatedComponent.activate/deactivate` from
///     `TaskGroupActivator`, or directly by `LeaderChange` / `QuorumDisappeared` events).
///   - Periodically evaluate controller with current metrics.
///   - Apply scaling decisions by updating blueprints in KV-Store.
///
/// The lifecycle is implemented as an explicit FSM whose states live in
/// [`org.pragmatica.aether.controller.fsm`]. See [`ControlLoopState`] for the transition diagram.
@Contract
public interface ControlLoop extends DelegatedComponent {
    @MessageReceiver void onTopologyChange(TopologyChangeNotification topologyChange);
    @MessageReceiver void onSliceTargetPut(ValuePut<SliceTargetKey, SliceTargetValue> valuePut);
    @MessageReceiver void onSliceTargetRemove(ValueRemove<SliceTargetKey, SliceTargetValue> valueRemove);
    @MessageReceiver void onNodeArtifactPut(ValuePut<NodeArtifactKey, NodeArtifactValue> valuePut);
    @MessageReceiver void onNodeArtifactRemove(ValueRemove<NodeArtifactKey, NodeArtifactValue> valueRemove);
    @MessageReceiver void onQuorumStateChange(QuorumStateNotification notification);
    void registerBlueprint(Artifact artifact, int instances, int minInstances);
    void unregisterBlueprint(Artifact artifact);
    ControllerConfig configuration();
    void updateConfiguration(ControllerConfig config);
    void stop();
    void onCommunityScalingRequest(CommunityScalingRequest request);
    void onCommunityMetricsSnapshot(CommunityMetricsSnapshot snapshot);
    Map<String, CommunityMetricsSnapshot> communitySnapshots();

    static ControlLoop controlLoop(NodeId self,
                                   ClusterController controller,
                                   ClusterSyncCollector metricsCollector,
                                   Option<InvocationMetricsCollector> invocationMetricsCollector,
                                   ClusterNode<KVCommand<AetherKey>> cluster,
                                   KVStore<AetherKey, AetherValue> kvStore,
                                   TimeSpan interval,
                                   ControllerConfig config,
                                   Consumer<ScalingEvent> eventPublisher) {
        var ctxHolder = new AtomicReference<ControlLoopContext>();
        Function<Fsm<ControlLoopState, ClusterFsmEvent>, ControlLoopState> initialStateFactory =
            fsm -> buildContext(ctxHolder, fsm, self, controller, metricsCollector,
                                invocationMetricsCollector, cluster, kvStore, interval,
                                config, eventPublisher);
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
                                                 KVStore<AetherKey, AetherValue> kvStore,
                                                 TimeSpan interval,
                                                 ControllerConfig config,
                                                 Consumer<ScalingEvent> eventPublisher) {
        var ctx = new ControlLoopContext(fsm, self, controller, metricsCollector, invocationMetricsCollector,
                                         cluster, kvStore, interval, config, eventPublisher);
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
        public TaskGroup taskGroup() {
            return TaskGroup.SCALING;
        }

        @Override
        public boolean isActive() {
            var current = fsm.current();
            return !(current instanceof ControlLoopState.Dormant || current instanceof ControlLoopState.Stopped);
        }

        // NodeDown falls through to the default branch; it's handled via onQuorumStateChange
        // when quorum disappears.
        @Override
        public void onTopologyChange(TopologyChangeNotification topologyChange) {
            switch (topologyChange) {
                case NodeAdded(_, var newTopology) -> ctx.setTopology(newTopology);
                case NodeRemoved(_, var newTopology) -> ctx.setTopology(newTopology);
                default -> {}
            }
        }

        @Override
        public void onQuorumStateChange(QuorumStateNotification notification) {
            if (!notification.advanceSequence(ctx.quorumSequence())) {
                log.debug("Ignoring stale QuorumStateNotification: {}", notification);
                return;
            }
            switch (notification.state()) {
                case ESTABLISHED -> fsm.dispatch(new ClusterFsmEvent.QuorumEstablished());
                case DISAPPEARED -> fsm.dispatch(new ClusterFsmEvent.QuorumDisappeared());
            }
        }

        @Override
        public void onSliceTargetPut(ValuePut<SliceTargetKey, SliceTargetValue> valuePut) {
            var key = valuePut.cause().key();
            var value = valuePut.cause().value();
            registerBlueprint(key.artifactBase().withVersion(value.currentVersion()),
                              value.targetInstances(),
                              value.effectiveMinInstances());
        }

        @Override
        public void onSliceTargetRemove(ValueRemove<SliceTargetKey, SliceTargetValue> valueRemove) {
            ctx.removeBlueprintMatching(valueRemove.cause().key());
        }

        @Override
        public void onNodeArtifactPut(ValuePut<NodeArtifactKey, NodeArtifactValue> valuePut) {
            var key = valuePut.cause().key();
            var value = valuePut.cause().value();
            ctx.recordSliceState(new SliceNodeKey(key.artifact(), key.nodeId()), value.state());
        }

        @Override
        public void onNodeArtifactRemove(ValueRemove<NodeArtifactKey, NodeArtifactValue> valueRemove) {
            var key = valueRemove.cause().key();
            ctx.clearSliceState(new SliceNodeKey(key.artifact(), key.nodeId()));
        }

        @Override
        public void registerBlueprint(Artifact artifact, int instances, int minInstances) {
            ctx.putBlueprint(artifact, instances, minInstances);
            log.info("Registered blueprint: {} with {} instances (min: {})", artifact, instances, minInstances);
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
        public void onCommunityScalingRequest(CommunityScalingRequest request) {
            if (!isActive()) {
                log.debug("Ignoring community scaling request: not active");
                return;
            }
            ctx.handleCommunityScalingRequest(request);
        }

        @Override
        public void onCommunityMetricsSnapshot(CommunityMetricsSnapshot snapshot) {
            ctx.storeCommunitySnapshot(snapshot);
        }

        @Override
        public Map<String, CommunityMetricsSnapshot> communitySnapshots() {
            return ctx.communitySnapshots();
        }
    }
}
