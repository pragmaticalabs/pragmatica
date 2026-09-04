// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.config.CommunitySizing;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentContext;
import org.pragmatica.aether.deployment.cluster.fsm.CommunityLivenessView;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.ActivationDirectivePutReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.ActivationDirectiveRemoveReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.Activate;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.AppBlueprintPutReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.AppBlueprintRemoveReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.Deactivate;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.NodeArtifactPutReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.NodeArtifactRemoveReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.SchemaVersionPutReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.GovernorAnnouncementPutReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.SliceTargetPutReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.SliceTargetRemoveReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.MembershipDecisionReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.WorkerJoinReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.WorkerLeaveReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.SelfShutdownReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.VersionRoutingPutReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.VersionRoutingRemoveReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentState;
import org.pragmatica.aether.deployment.membership.fsm.WorkerJoinDecision;
import org.pragmatica.aether.deployment.membership.fsm.WorkerLeaveDecision;
import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.VersionRoutingKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
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
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.Message;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.Fsm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


public interface ClusterDeploymentManager {
    Promise<Unit> activate();
    Promise<Unit> deactivate();
    boolean isActive();

    /// #590 — inject the leader's OBSERVED community-liveness view, replacing the community's own
    /// frozen self-report as the input to the per-community FSM. Narrow on purpose: the caller needs
    /// this one seam, not the whole deployment context. Wired in `AetherNode` once the cluster-sync
    /// collector exists; unwired deployments keep the pre-#590 behaviour.
    @Contract
    void setCommunityLiveness(CommunityLivenessView view);

    /// #731 round 3 — inject the leader's local SWIM-derived alive-member view, read by
    /// `sweepDeadRestoredWorkers` alongside the committed announcement roster. Narrow on purpose,
    /// same rationale as `setCommunityLiveness`. Wired in `AetherNode` from the node's `MembershipFsm`;
    /// unwired deployments keep the pre-round-3 behaviour (empty set, no effect on the sweep).
    @Contract
    void setLocalAliveMembersSupplier(Supplier<Set<NodeId>> supplier);

    @Contract
    @MessageReceiver
    void onAppBlueprintPut(ValuePut<AppBlueprintKey, AppBlueprintValue> valuePut);

    @Contract
    @MessageReceiver
    void onSliceTargetPut(ValuePut<SliceTargetKey, SliceTargetValue> valuePut);

    @Contract
    @MessageReceiver
    void onVersionRoutingPut(ValuePut<VersionRoutingKey, VersionRoutingValue> valuePut);

    @Contract
    @MessageReceiver
    void onAppBlueprintRemove(ValueRemove<AppBlueprintKey, AppBlueprintValue> valueRemove);

    @Contract
    @MessageReceiver
    void onSliceTargetRemove(ValueRemove<SliceTargetKey, SliceTargetValue> valueRemove);

    @Contract
    @MessageReceiver
    void onVersionRoutingRemove(ValueRemove<VersionRoutingKey, VersionRoutingValue> valueRemove);

    @Contract
    @MessageReceiver
    void onMembershipDecision(MembershipDecision decision);

    /// The non-core join channel (#728). Separate receiver from [`#onMembershipDecision`] because
    /// worker joins deliberately never travel on the core `MembershipDecision` stream.
    @Contract
    @MessageReceiver
    void onWorkerJoin(WorkerJoinDecision decision);

    /// The non-core leave channel (#731), symmetric to [`#onWorkerJoin`] — a departed worker's
    /// REMOVED edge never travels on the core `MembershipDecision` stream either.
    @Contract
    @MessageReceiver
    void onWorkerLeave(WorkerLeaveDecision decision);

    @Contract
    @MessageReceiver
    void onSelfShutdown(TransportObservation.SelfShutdown selfShutdown);

    @Contract
    @MessageReceiver
    void onActivationDirectivePut(ValuePut<ActivationDirectiveKey, ActivationDirectiveValue> valuePut);

    @Contract
    @MessageReceiver
    void onActivationDirectiveRemove(ValueRemove<ActivationDirectiveKey, ActivationDirectiveValue> valueRemove);

    @Contract
    @MessageReceiver
    void onNodeArtifactPut(ValuePut<NodeArtifactKey, NodeArtifactValue> valuePut);

    @Contract
    @MessageReceiver
    void onNodeArtifactRemove(ValueRemove<NodeArtifactKey, NodeArtifactValue> valueRemove);

    @Contract
    @MessageReceiver
    void onSchemaVersionPut(ValuePut<SchemaVersionKey, SchemaVersionValue> valuePut);

    /// #731 round 3: re-runs the dead-worker sweep as soon as a governor's reannouncement commits,
    /// instead of relying solely on the one-shot `deferredTopologyRecheck` timer to catch a worker
    /// that was absent from every roster only transiently.
    @Contract
    @MessageReceiver
    void onGovernorAnnouncementPut(ValuePut<GovernorAnnouncementKey, GovernorAnnouncementValue> valuePut);

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
            if (!Verify.Is.present(value)) {
                return ALL_OR_NOTHING;
            }

            return switch (value.trim()
                                .toLowerCase()
                                .replace("-", "_")) {
                case "best_effort" -> BEST_EFFORT;
                default -> ALL_OR_NOTHING;
            };
        }
    }

    /// #699 — the 2/3/4-arg `blueprint(...)` overloads that used to live here all hardcoded
    /// `schemaRequired = true`, silently, with no call site able to see the default being applied.
    /// That silent default was the exact mechanism behind #555 (three of four production call
    /// sites reverted an owned slice's `schemaRequired` to `true` because they used a short
    /// overload instead of resolving it from the owning blueprint). Only the 5-arg canonical form
    /// remains: every call site now states its intended `schemaRequired` explicitly.
    record Blueprint(Artifact artifact,
                     int instances,
                     int minInstances,
                     Option<BlueprintId> owner,
                     boolean schemaRequired) {
        public static Blueprint blueprint(Artifact artifact,
                                          int instances,
                                          int minInstances,
                                          Option<BlueprintId> owner,
                                          boolean schemaRequired) {
            return new Blueprint(artifact, instances, minInstances, owner, schemaRequired);
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
                                        Set::of,
                                        Set::of);
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
                                        Set::of,
                                        Set::of);
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
                                                             Supplier<Set<NodeId>> coreCountedMembersSupplier,
                                                             Supplier<Set<NodeId>> readyNodesSupplier) {
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
                                        coreCountedMembersSupplier,
                                        readyNodesSupplier,
                                        Set::of);
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
                                                             Supplier<Set<NodeId>> coreCountedMembersSupplier,
                                                             Supplier<Set<NodeId>> readyNodesSupplier,
                                                             Supplier<Set<NodeId>> drainingNodesSupplier) {
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
                                        coreCountedMembersSupplier,
                                        readyNodesSupplier,
                                        drainingNodesSupplier,
                                        node -> Option.none());
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
                                                             Supplier<Set<NodeId>> coreCountedMembersSupplier,
                                                             Supplier<Set<NodeId>> readyNodesSupplier,
                                                             Supplier<Set<NodeId>> drainingNodesSupplier,
                                                             Function<NodeId, Option<String>> memberSourceSupplier) {
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
                                        coreCountedMembersSupplier,
                                        readyNodesSupplier,
                                        drainingNodesSupplier,
                                        memberSourceSupplier,
                                        CommunitySizing.DEFAULT);
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
                                                             Supplier<Set<NodeId>> coreCountedMembersSupplier,
                                                             Supplier<Set<NodeId>> readyNodesSupplier,
                                                             Supplier<Set<NodeId>> drainingNodesSupplier,
                                                             Function<NodeId, Option<String>> memberSourceSupplier,
                                                             CommunitySizing communitySizing) {
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
                               coreCountedMembersSupplier,
                               readyNodesSupplier,
                               drainingNodesSupplier,
                               memberSourceSupplier,
                               communitySizing);

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
                                                         Supplier<Set<NodeId>> coreCountedMembersSupplier,
                                                         Supplier<Set<NodeId>> readyNodesSupplier,
                                                         Supplier<Set<NodeId>> drainingNodesSupplier,
                                                         Function<NodeId, Option<String>> memberSourceSupplier,
                                                         CommunitySizing communitySizing) {
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
                                                                                                                                           coreCountedMembersSupplier,
                                                                                                                                           readyNodesSupplier,
                                                                                                                                           drainingNodesSupplier,
                                                                                                                                           memberSourceSupplier,
                                                                                                                                           communitySizing);
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
                                                                 Supplier<Set<NodeId>> coreCountedMembersSupplier,
                                                                 Supplier<Set<NodeId>> readyNodesSupplier,
                                                                 Supplier<Set<NodeId>> drainingNodesSupplier,
                                                                 Function<NodeId, Option<String>> memberSourceSupplier,
                                                                 CommunitySizing communitySizing) {
        var ctx = new ClusterDeploymentContext(fsm,
                                               self,
                                               cluster,
                                               kvStore,
                                               router,
                                               topologyManager,
                                               schemaOrchestrator,
                                               coreCountedMembersSupplier,
                                               readyNodesSupplier,
                                               drainingNodesSupplier,
                                               seedNodes,
                                               atomicity,
                                               coreMax,
                                               reconcileInterval,
                                               System::currentTimeMillis,
                                               memberSourceSupplier,
                                               communitySizing);

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

        @Override
        @Contract
        public void setCommunityLiveness(CommunityLivenessView view) {
            ctx.setCommunityLiveness(view);
        }

        @Override
        @Contract
        public void setLocalAliveMembersSupplier(Supplier<Set<NodeId>> supplier) {
            ctx.setLocalAliveMembersSupplier(supplier);
        }

        @Override
        public Promise<Unit> activate() {
            log.info("Activating cluster deployment manager on node {}", ctx.self());
            ctx.dispatch(new Activate());

            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> deactivate() {
            log.info("Deactivating cluster deployment manager on node {}", ctx.self());
            ctx.dispatch(new Deactivate());

            return Promise.unitPromise();
        }

        @Override
        public boolean isActive() {
            return ctx.isActive();
        }

        @Contract
        @Override
        public void onAppBlueprintPut(ValuePut<AppBlueprintKey, AppBlueprintValue> valuePut) {
            ctx.dispatch(new AppBlueprintPutReceived(valuePut));
        }

        @Contract
        @Override
        public void onSliceTargetPut(ValuePut<SliceTargetKey, SliceTargetValue> valuePut) {
            ctx.dispatch(new SliceTargetPutReceived(valuePut));
        }

        @Contract
        @Override
        public void onVersionRoutingPut(ValuePut<VersionRoutingKey, VersionRoutingValue> valuePut) {
            ctx.dispatch(new VersionRoutingPutReceived(valuePut));
        }

        @Contract
        @Override
        public void onAppBlueprintRemove(ValueRemove<AppBlueprintKey, AppBlueprintValue> valueRemove) {
            ctx.dispatch(new AppBlueprintRemoveReceived(valueRemove));
        }

        @Contract
        @Override
        public void onSliceTargetRemove(ValueRemove<SliceTargetKey, SliceTargetValue> valueRemove) {
            ctx.dispatch(new SliceTargetRemoveReceived(valueRemove));
        }

        @Contract
        @Override
        public void onVersionRoutingRemove(ValueRemove<VersionRoutingKey, VersionRoutingValue> valueRemove) {
            ctx.dispatch(new VersionRoutingRemoveReceived(valueRemove));
        }

        @Contract
        @Override
        public void onActivationDirectivePut(ValuePut<ActivationDirectiveKey, ActivationDirectiveValue> valuePut) {
            ctx.dispatch(new ActivationDirectivePutReceived(valuePut));
        }

        @Contract
        @Override
        public void onActivationDirectiveRemove(ValueRemove<ActivationDirectiveKey, ActivationDirectiveValue> valueRemove) {
            ctx.dispatch(new ActivationDirectiveRemoveReceived(valueRemove));
        }

        @Contract
        @Override
        public void onNodeArtifactPut(ValuePut<NodeArtifactKey, NodeArtifactValue> valuePut) {
            ctx.dispatch(new NodeArtifactPutReceived(valuePut));
        }

        @Contract
        @Override
        public void onNodeArtifactRemove(ValueRemove<NodeArtifactKey, NodeArtifactValue> valueRemove) {
            ctx.dispatch(new NodeArtifactRemoveReceived(valueRemove));
        }

        @Contract
        @Override
        public void onSchemaVersionPut(ValuePut<SchemaVersionKey, SchemaVersionValue> valuePut) {
            ctx.dispatch(new SchemaVersionPutReceived(valuePut));
        }

        @Contract
        @Override
        public void onGovernorAnnouncementPut(ValuePut<GovernorAnnouncementKey, GovernorAnnouncementValue> valuePut) {
            ctx.dispatch(new GovernorAnnouncementPutReceived(valuePut));
        }

        @Contract
        @Override
        public void onMembershipDecision(MembershipDecision decision) {
            ctx.dispatch(new MembershipDecisionReceived(decision));
        }

        @Contract
        @Override
        public void onWorkerJoin(WorkerJoinDecision decision) {
            ctx.dispatch(new WorkerJoinReceived(decision));
        }

        @Contract
        @Override
        public void onWorkerLeave(WorkerLeaveDecision decision) {
            ctx.dispatch(new WorkerLeaveReceived(decision));
        }

        @Contract
        @Override
        public void onSelfShutdown(TransportObservation.SelfShutdown selfShutdown) {
            ctx.dispatch(new SelfShutdownReceived(selfShutdown));
        }
    }
}
