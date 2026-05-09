// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentContext;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.ActivationDirectivePutReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.ActivationDirectiveRemoveReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.Activate;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.AppBlueprintPutReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.AppBlueprintRemoveReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.Deactivate;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.NodeArtifactPutReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.NodeArtifactRemoveReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.NodeLifecyclePutReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.SchemaVersionPutReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.SliceTargetPutReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.SliceTargetRemoveReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.MembershipDecisionReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.SelfShutdownReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.VersionRoutingPutReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.VersionRoutingRemoveReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentState;
import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.delegation.DelegatedComponent;
import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.HealthSignalSink;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.VersionRoutingKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.VersionRoutingValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.consensus.topology.TransportObservation;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.Message;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.Fsm;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


public interface ClusterDeploymentManager extends DelegatedComponent {
    @Contract@MessageReceiver void onAppBlueprintPut(ValuePut<AppBlueprintKey, AppBlueprintValue> valuePut);
    @Contract@MessageReceiver void onSliceTargetPut(ValuePut<SliceTargetKey, SliceTargetValue> valuePut);
    @Contract@MessageReceiver void onVersionRoutingPut(ValuePut<VersionRoutingKey, VersionRoutingValue> valuePut);
    @Contract@MessageReceiver void onAppBlueprintRemove(ValueRemove<AppBlueprintKey, AppBlueprintValue> valueRemove);
    @Contract@MessageReceiver void onSliceTargetRemove(ValueRemove<SliceTargetKey, SliceTargetValue> valueRemove);
    @Contract@MessageReceiver void onVersionRoutingRemove(ValueRemove<VersionRoutingKey, VersionRoutingValue> valueRemove);
    @Contract@MessageReceiver void onMembershipDecision(MembershipDecision decision);
    // Self-shutdown cleanup hook: kept on TransportObservation stream because self-shutdown is not a cluster decision.
    @Contract@MessageReceiver void onSelfShutdown(TransportObservation.SelfShutdown selfShutdown);
    @Contract@MessageReceiver void onNodeLifecyclePut(ValuePut<NodeLifecycleKey, NodeLifecycleValue> valuePut);
    @Contract@MessageReceiver void onActivationDirectivePut(ValuePut<ActivationDirectiveKey, ActivationDirectiveValue> valuePut);
    @Contract@MessageReceiver void onActivationDirectiveRemove(ValueRemove<ActivationDirectiveKey, ActivationDirectiveValue> valueRemove);
    @Contract@MessageReceiver void onNodeArtifactPut(ValuePut<NodeArtifactKey, NodeArtifactValue> valuePut);
    @Contract@MessageReceiver void onNodeArtifactRemove(ValueRemove<NodeArtifactKey, NodeArtifactValue> valueRemove);
    @Contract@MessageReceiver void onSchemaVersionPut(ValuePut<SchemaVersionKey, SchemaVersionValue> valuePut);

    record ReconciliationAdjustment(Artifact artifact, int currentInstances, int desiredInstances) implements Message.Local {
        public static ReconciliationAdjustment reconciliationAdjustment(Artifact artifact,
                                                                        int currentInstances,
                                                                        int desiredInstances) {
            return new ReconciliationAdjustment(artifact, currentInstances, desiredInstances);
        }
    }

    enum DeploymentAtomicity {
        BEST_EFFORT,
        ALL_OR_NOTHING;
        public static DeploymentAtomicity parse(String value) {
            if (value == null || value.isBlank()) {return ALL_OR_NOTHING;}
            return switch (value.trim().toLowerCase()
                                     .replace("-", "_")){
                case "best_effort" -> BEST_EFFORT;
                default -> ALL_OR_NOTHING;
            };
        }
    }

    record Blueprint(Artifact artifact,
                     int instances,
                     int minInstances,
                     Option<BlueprintId> owner,
                     boolean schemaRequired) {
        public static Blueprint blueprint(Artifact artifact,
                                          int instances,
                                          int minInstances,
                                          Option<BlueprintId> owner) {
            return new Blueprint(artifact, instances, minInstances, owner, true);
        }

        public static Blueprint blueprint(Artifact artifact,
                                          int instances,
                                          int minInstances,
                                          Option<BlueprintId> owner,
                                          boolean schemaRequired) {
            return new Blueprint(artifact, instances, minInstances, owner, schemaRequired);
        }

        public static Blueprint blueprint(Artifact artifact, int instances, int minInstances) {
            return new Blueprint(artifact, instances, minInstances, Option.empty(), true);
        }

        public static Blueprint blueprint(Artifact artifact, int instances) {
            return new Blueprint(artifact, instances, 1, Option.empty(), true);
        }
    }

    TimeSpan DEFAULT_RECONCILE_INTERVAL = timeSpan(30).seconds();

    static ClusterDeploymentManager clusterDeploymentManager(NodeId self,
                                                             ClusterNode<KVCommand<AetherKey>> cluster,
                                                             KVStore<AetherKey, AetherValue> kvStore,
                                                             MessageRouter router,
                                                             List<NodeId> initialTopology,
                                                             TopologyManager topologyManager,
                                                             DeploymentAtomicity atomicity,
                                                             int coreMax,
                                                             SchemaOrchestratorService schemaOrchestrator) {
        return clusterDeploymentManager(self,
                                        cluster,
                                        kvStore,
                                        router,
                                        initialTopology,
                                        topologyManager,
                                        atomicity,
                                        coreMax,
                                        DEFAULT_RECONCILE_INTERVAL,
                                        schemaOrchestrator,
                                        HealthSignalSink.noop(),
                                        Option::none);
    }

    static ClusterDeploymentManager clusterDeploymentManager(NodeId self,
                                                             ClusterNode<KVCommand<AetherKey>> cluster,
                                                             KVStore<AetherKey, AetherValue> kvStore,
                                                             MessageRouter router,
                                                             List<NodeId> initialTopology,
                                                             TopologyManager topologyManager,
                                                             DeploymentAtomicity atomicity,
                                                             int coreMax,
                                                             TimeSpan reconcileInterval,
                                                             SchemaOrchestratorService schemaOrchestrator) {
        return clusterDeploymentManager(self,
                                        cluster,
                                        kvStore,
                                        router,
                                        initialTopology,
                                        topologyManager,
                                        atomicity,
                                        coreMax,
                                        reconcileInterval,
                                        schemaOrchestrator,
                                        HealthSignalSink.noop(),
                                        Option::none);
    }

    static ClusterDeploymentManager clusterDeploymentManager(NodeId self,
                                                             ClusterNode<KVCommand<AetherKey>> cluster,
                                                             KVStore<AetherKey, AetherValue> kvStore,
                                                             MessageRouter router,
                                                             List<NodeId> initialTopology,
                                                             TopologyManager topologyManager,
                                                             DeploymentAtomicity atomicity,
                                                             int coreMax,
                                                             TimeSpan reconcileInterval,
                                                             SchemaOrchestratorService schemaOrchestrator,
                                                             HealthSignalSink healthSignalSink) {
        return clusterDeploymentManager(self,
                                        cluster,
                                        kvStore,
                                        router,
                                        initialTopology,
                                        topologyManager,
                                        atomicity,
                                        coreMax,
                                        reconcileInterval,
                                        schemaOrchestrator,
                                        healthSignalSink,
                                        Option::none);
    }

    static ClusterDeploymentManager clusterDeploymentManager(NodeId self,
                                                             ClusterNode<KVCommand<AetherKey>> cluster,
                                                             KVStore<AetherKey, AetherValue> kvStore,
                                                             MessageRouter router,
                                                             List<NodeId> initialTopology,
                                                             TopologyManager topologyManager,
                                                             DeploymentAtomicity atomicity,
                                                             int coreMax,
                                                             TimeSpan reconcileInterval,
                                                             SchemaOrchestratorService schemaOrchestrator,
                                                             HealthSignalSink healthSignalSink,
                                                             Supplier<Option<ClusterGenerationSnapshot>> snapshotSupplier) {
        var ctx = buildContext(self,
                               cluster,
                               kvStore,
                               router,
                               Set.copyOf(initialTopology),
                               topologyManager,
                               atomicity,
                               coreMax,
                               reconcileInterval,
                               schemaOrchestrator,
                               healthSignalSink,
                               snapshotSupplier);
        return new ClusterDeploymentManagerAdapter(ctx);
    }

    private static ClusterDeploymentContext buildContext(NodeId self,
                                                         ClusterNode<KVCommand<AetherKey>> cluster,
                                                         KVStore<AetherKey, AetherValue> kvStore,
                                                         MessageRouter router,
                                                         Set<NodeId> seedNodes,
                                                         TopologyManager topologyManager,
                                                         DeploymentAtomicity atomicity,
                                                         int coreMax,
                                                         TimeSpan reconcileInterval,
                                                         SchemaOrchestratorService schemaOrchestrator,
                                                         HealthSignalSink healthSignalSink,
                                                         Supplier<Option<ClusterGenerationSnapshot>> snapshotSupplier) {
        var ctxHolder = new AtomicReference<ClusterDeploymentContext>();
        Function<Fsm<ClusterDeploymentState, ClusterFsmEvent>, ClusterDeploymentState> initialStateFactory = fsm -> buildContextAndDormant(fsm,
                                                                                                                                           ctxHolder,
                                                                                                                                           self,
                                                                                                                                           cluster,
                                                                                                                                           kvStore,
                                                                                                                                           router,
                                                                                                                                           seedNodes,
                                                                                                                                           topologyManager,
                                                                                                                                           atomicity,
                                                                                                                                           coreMax,
                                                                                                                                           reconcileInterval,
                                                                                                                                           schemaOrchestrator,
                                                                                                                                           healthSignalSink,
                                                                                                                                           snapshotSupplier);
        var _fsm = Fsm.fsm("cluster-deployment", self.id(), initialStateFactory);
        return ctxHolder.get();
    }

    private static ClusterDeploymentState buildContextAndDormant(Fsm<ClusterDeploymentState, ClusterFsmEvent> fsm,
                                                                 AtomicReference<ClusterDeploymentContext> ctxHolder,
                                                                 NodeId self,
                                                                 ClusterNode<KVCommand<AetherKey>> cluster,
                                                                 KVStore<AetherKey, AetherValue> kvStore,
                                                                 MessageRouter router,
                                                                 Set<NodeId> seedNodes,
                                                                 TopologyManager topologyManager,
                                                                 DeploymentAtomicity atomicity,
                                                                 int coreMax,
                                                                 TimeSpan reconcileInterval,
                                                                 SchemaOrchestratorService schemaOrchestrator,
                                                                 HealthSignalSink healthSignalSink,
                                                                 Supplier<Option<ClusterGenerationSnapshot>> snapshotSupplier) {
        var ctx = new ClusterDeploymentContext(fsm,
                                               self,
                                               cluster,
                                               kvStore,
                                               router,
                                               topologyManager,
                                               schemaOrchestrator,
                                               healthSignalSink,
                                               snapshotSupplier,
                                               seedNodes,
                                               atomicity,
                                               coreMax,
                                               reconcileInterval);
        ctxHolder.set(ctx);
        return ctx.dormant();
    }

    final class ClusterDeploymentManagerAdapter implements ClusterDeploymentManager {
        private static final Logger log = LoggerFactory.getLogger(ClusterDeploymentManagerAdapter.class);

        private final ClusterDeploymentContext ctx;

        ClusterDeploymentManagerAdapter(ClusterDeploymentContext ctx) {
            this.ctx = ctx;
        }

        public ClusterDeploymentContext context() {
            return ctx;
        }

        @Override public Promise<Unit> activate() {
            log.info("Activating cluster deployment manager on node {}", ctx.self());
            ctx.dispatch(new Activate());
            return Promise.unitPromise();
        }

        @Override public Promise<Unit> deactivate() {
            log.info("Deactivating cluster deployment manager on node {}", ctx.self());
            ctx.dispatch(new Deactivate());
            return Promise.unitPromise();
        }

        @Override public TaskGroup taskGroup() {
            return TaskGroup.DEPLOYMENT;
        }

        @Override public boolean isActive() {
            return ctx.isActive();
        }

        @Contract@Override public void onAppBlueprintPut(ValuePut<AppBlueprintKey, AppBlueprintValue> valuePut) {
            ctx.dispatch(new AppBlueprintPutReceived(valuePut));
        }

        @Contract@Override public void onSliceTargetPut(ValuePut<SliceTargetKey, SliceTargetValue> valuePut) {
            ctx.dispatch(new SliceTargetPutReceived(valuePut));
        }

        @Contract@Override public void onVersionRoutingPut(ValuePut<VersionRoutingKey, VersionRoutingValue> valuePut) {
            ctx.dispatch(new VersionRoutingPutReceived(valuePut));
        }

        @Contract@Override public void onAppBlueprintRemove(ValueRemove<AppBlueprintKey, AppBlueprintValue> valueRemove) {
            ctx.dispatch(new AppBlueprintRemoveReceived(valueRemove));
        }

        @Contract@Override public void onSliceTargetRemove(ValueRemove<SliceTargetKey, SliceTargetValue> valueRemove) {
            ctx.dispatch(new SliceTargetRemoveReceived(valueRemove));
        }

        @Contract@Override public void onVersionRoutingRemove(ValueRemove<VersionRoutingKey, VersionRoutingValue> valueRemove) {
            ctx.dispatch(new VersionRoutingRemoveReceived(valueRemove));
        }

        @Contract@Override public void onNodeLifecyclePut(ValuePut<NodeLifecycleKey, NodeLifecycleValue> valuePut) {
            ctx.dispatch(new NodeLifecyclePutReceived(valuePut));
        }

        @Contract@Override public void onActivationDirectivePut(ValuePut<ActivationDirectiveKey, ActivationDirectiveValue> valuePut) {
            ctx.dispatch(new ActivationDirectivePutReceived(valuePut));
        }

        @Contract@Override public void onActivationDirectiveRemove(ValueRemove<ActivationDirectiveKey, ActivationDirectiveValue> valueRemove) {
            ctx.dispatch(new ActivationDirectiveRemoveReceived(valueRemove));
        }

        @Contract@Override public void onNodeArtifactPut(ValuePut<NodeArtifactKey, NodeArtifactValue> valuePut) {
            ctx.dispatch(new NodeArtifactPutReceived(valuePut));
        }

        @Contract@Override public void onNodeArtifactRemove(ValueRemove<NodeArtifactKey, NodeArtifactValue> valueRemove) {
            ctx.dispatch(new NodeArtifactRemoveReceived(valueRemove));
        }

        @Contract@Override public void onSchemaVersionPut(ValuePut<SchemaVersionKey, SchemaVersionValue> valuePut) {
            ctx.dispatch(new SchemaVersionPutReceived(valuePut));
        }

        @Contract@Override public void onMembershipDecision(MembershipDecision decision) {
            ctx.dispatch(new MembershipDecisionReceived(decision));
        }

        @Contract@Override public void onSelfShutdown(TransportObservation.SelfShutdown selfShutdown) {
            ctx.dispatch(new SelfShutdownReceived(selfShutdown));
        }
    }
}
