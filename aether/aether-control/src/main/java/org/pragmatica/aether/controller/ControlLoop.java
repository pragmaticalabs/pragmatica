// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.controller;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

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
import org.pragmatica.aether.slice.kvstore.AetherKey.AbTestKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceRequirementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AbTestValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceRequirementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.worker.metrics.CommunityMetricsSnapshot;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
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
import org.pragmatica.utility.react.ReactExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@Contract
public interface ControlLoop {
    Promise<Unit> activate();
    Promise<Unit> deactivate();
    boolean isActive();

    @MessageReceiver
    void onMembershipDecision(MembershipDecision decision);

    org.pragmatica.lang.Unit onWorkerDeparture(org.pragmatica.consensus.NodeId node);

    @MessageReceiver
    void onSliceTargetPut(ValuePut<SliceTargetKey, SliceTargetValue> valuePut);

    @MessageReceiver
    void onSliceTargetRemove(ValueRemove<SliceTargetKey, SliceTargetValue> valueRemove);

    /// rc6 §3.1: a slice capacity requirement was put; the leader's [RequirementCombiner] re-combines the slice's target.
    void onSliceRequirementPut(ValuePut<SliceRequirementKey, SliceRequirementValue> valuePut);
    /// rc6 §3.1: a slice capacity requirement was removed; the leader's [RequirementCombiner] re-combines the slice's target.
    void onSliceRequirementRemove(ValueRemove<SliceRequirementKey, SliceRequirementValue> valueRemove);
    /// An A/B test was put: a canary starting or ending changes whether its slice is combined (v2713 item 7).
    void onAbTestPut(ValuePut<AbTestKey, AbTestValue> valuePut);

    @MessageReceiver
    void onNodeArtifactPut(ValuePut<NodeArtifactKey, NodeArtifactValue> valuePut);

    @MessageReceiver
    void onNodeArtifactRemove(ValueRemove<NodeArtifactKey, NodeArtifactValue> valueRemove);

    @MessageReceiver
    void onQuorumStateChange(ClusterStateNotification notification);

    /// Registers the slice from the whole `SliceTargetValue` that was observed for it, rather than
    /// from a hand-picked selection of its components.
    ///
    /// This parameter used to be seven exploded fields, and that shape was the root of #698, #936
    /// and #937: the autoscaler had to rebuild a nine-component durable value out of the subset it
    /// had been handed, so every component outside the subset was replaced by a factory default on
    /// every scaling Put — the owner erased, the placement reset, and the floor ratcheted. Passing
    /// the value keeps the autoscaler's decision to a single number and leaves every other
    /// component untouched by construction.
    ///
    /// `artifact` is the slice's base coordinate at `target.currentVersion()`; the two must agree,
    /// because the registration is keyed by it and the value's version is written back out.
    void registerBlueprint(Artifact artifact, SliceTargetValue target);
    void unregisterBlueprint(Artifact artifact);
    ControllerConfig configuration();
    void updateConfiguration(ControllerConfig config);
    void stop();

    default Unit setMetricsProducerEligibility(Predicate<NodeId> isEligible) {
        return Unit.unit();
    }

    /// D1-react (KVStore dispatcher design §3.5.1 row 39): the executor the slice cooldown request of
    /// [#onNodeArtifactPut] runs on. A node installs its gated executor once, at assembly; until then (and in a host
    /// that installs none) the request runs inline, as before D1-react. Default no-op so stubs stay compilable.
    default Unit installReactExecutor(ReactExecutor executor) {
        return Unit.unit();
    }

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
                                   KVStore<AetherKey, AetherValue> kvStore,
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
                                                                                                                     kvStore,
                                                                                                                     interval,
                                                                                                                     config,
                                                                                                                     eventPublisher);
        var fsm = Fsm.fsm("control-loop", self.id(), initialStateFactory);
        var ctx = ctxHolder.get();
        var combiner = RequirementCombiner.requirementCombiner(kvStore,
                                                               cluster::apply,
                                                               ctx::nowMs,
                                                               () -> RequirementCombiner.UNBOUNDED_CAPACITY);

        return new ControlLoopAdapter(ctx, fsm, combiner);
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
        var ctx = new ControlLoopContext(fsm,
                                         self,
                                         controller,
                                         metricsCollector,
                                         invocationMetricsCollector,
                                         cluster,
                                         kvStore,
                                         interval,
                                         config,
                                         eventPublisher);

        ctxHolder.set(ctx);

        return ctx.dormant();
    }

    record ControlLoopAdapter(ControlLoopContext ctx,
                              Fsm<ControlLoopState, ClusterFsmEvent> fsm,
                              RequirementCombiner combiner) implements ControlLoop {
        private static final Logger log = LoggerFactory.getLogger(ControlLoop.class);

        @Override
        public Promise<Unit> activate() {
            log.info("Node {} activating control loop", ctx.self());
            fsm.dispatch(new Activate());
            combiner.activate();

            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> deactivate() {
            log.info("Node {} deactivating control loop", ctx.self());
            fsm.dispatch(new Deactivate());
            combiner.deactivate();

            return Promise.unitPromise();
        }

        @Override
        public boolean isActive() {
            var current = fsm.current();

            return ! (current instanceof ControlLoopState.Dormant || current instanceof ControlLoopState.Stopped);
        }

        @Override
        public org.pragmatica.lang.Unit onWorkerDeparture(org.pragmatica.consensus.NodeId node) {
            ctx.removeNodeMetrics(node);

            return org.pragmatica.lang.Unit.unit();
        }

        @Override
        public void onMembershipDecision(MembershipDecision decision) {
            switch (decision) {
                case NodeJoined(_, var newTopology, _, _) -> {
                    ctx.setTopology(newTopology);
                    combiner.onTopologyChange();
                }
                case NodeRemoved(var departed, var newTopology, _, _) -> {
                    ctx.onNodeDeparted(departed, newTopology);
                    combiner.onTopologyChange();
                }
                case NodeDecommissioned(var departed, var newTopology, _, _) -> {
                    ctx.onNodeDeparted(departed, newTopology);
                    combiner.onTopologyChange();
                }
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
                              value);
            combiner.onSliceTargetPut(key);
        }

        @Override
        public void onSliceTargetRemove(ValueRemove<SliceTargetKey, SliceTargetValue> valueRemove) {
            ctx.removeBlueprintMatching(valueRemove.cause().key());
            combiner.onSliceTargetRemove(valueRemove.cause().key());
        }

        @Override
        public void onSliceRequirementPut(ValuePut<SliceRequirementKey, SliceRequirementValue> valuePut) {
            combiner.onRequirementPut(valuePut.cause().key());
        }

        @Override
        public void onSliceRequirementRemove(ValueRemove<SliceRequirementKey, SliceRequirementValue> valueRemove) {
            combiner.onRequirementRemove(valueRemove.cause().key());
        }

        @Override
        public void onAbTestPut(ValuePut<AbTestKey, AbTestValue> valuePut) {
            combiner.onAbTestPut(valuePut.cause().value().artifactBase());
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
        public void registerBlueprint(Artifact artifact, SliceTargetValue target) {
            ctx.putBlueprint(artifact, target);
            log.info("Registered blueprint: {} with {} instances (min: {}, max: {}, placement: {})",
                     artifact,
                     target.targetInstances(),
                     target.effectiveMinInstances(),
                     target.maxInstances(),
                     target.effectivePlacement());
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
        public Unit setMetricsProducerEligibility(Predicate<NodeId> isEligible) {
            return ctx.setMetricsProducerEligibility(isEligible);
        }

        @Override
        public Unit installReactExecutor(ReactExecutor executor) {
            return ctx.installReactExecutor(executor);
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
