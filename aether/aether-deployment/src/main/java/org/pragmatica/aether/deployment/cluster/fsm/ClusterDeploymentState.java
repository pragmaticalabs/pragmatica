// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.config.PlacementPolicy;
import org.pragmatica.aether.deployment.AuditLog;
import org.pragmatica.aether.deployment.cluster.AllocationPool;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.deployment.membership.fsm.WorkerJoinDecision;
import org.pragmatica.aether.deployment.membership.fsm.WorkerLeaveDecision;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.Blueprint;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.ReconciliationAdjustment;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.Activate;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.ActivationDirectivePutReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.ActivationDirectiveRemoveReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.AppBlueprintPutReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.AppBlueprintRemoveReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.Deactivate;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.NodeArtifactPutReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.NodeArtifactRemoveReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.SchemaVersionPutReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.SliceTargetPutReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.SliceTargetRemoveReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.MembershipDecisionReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.SelfShutdownReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.VersionRoutingPutReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.VersionRoutingRemoveReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.WorkerJoinReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.WorkerLeaveReceived;
import org.pragmatica.aether.deployment.schema.SchemaEvent.ActivationBlocked;
import org.pragmatica.aether.metrics.deployment.DeploymentEvent.DeploymentFailed;
import org.pragmatica.aether.metrics.deployment.DeploymentEvent.DeploymentStarted;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.BlueprintParser;
import org.pragmatica.aether.slice.blueprint.DeploymentConfig;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.CommunityKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentOutcomeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaMigrationLockKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamMetadataKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.VersionRoutingKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.WorkerSliceDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaMigrationLockValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceNodeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamMetadataValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.VersionRoutingValue;
import org.pragmatica.aether.slice.kvstore.CommunityState;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.fsm.ClusterFsmEvent.Shutdown;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.consensus.topology.MembershipDecision.NodeDecommissioned;
import org.pragmatica.consensus.topology.MembershipDecision.NodeDraining;
import org.pragmatica.consensus.topology.MembershipDecision.NodeFailedDrain;
import org.pragmatica.consensus.topology.MembershipDecision.NodeJoined;
import org.pragmatica.consensus.topology.MembershipDecision.NodeJoining;
import org.pragmatica.consensus.topology.MembershipDecision.NodeRemoved;
import org.pragmatica.consensus.topology.TransportObservation;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.concurrent.CancellableTask;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.JitterUtil;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.statemachine.FsmState;
import org.pragmatica.statemachine.TransitionRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


public sealed interface ClusterDeploymentState extends FsmState<ClusterDeploymentState, ClusterFsmEvent> permits ClusterDeploymentState.Dormant, ClusterDeploymentState.Active, ClusterDeploymentState.Stopped {
    Logger LOG = LoggerFactory.getLogger(ClusterDeploymentState.class);
    ClusterDeploymentContext ctx();

    /// Schema statuses that hold slice activation (#542). FAILED is a blocking status: the
    /// physical schema sits at a version the slice was not built against until an operator
    /// retries or redeploys.
    ///
    /// #760 review BLOCKING 1 / TEST GAP 5: hoisted from a `private` copy inside [Active] to a
    /// single shared accessor also used by `SchemaRoutes.heldSlices` (`aether-node`), so the
    /// activation gate and the management-API report cannot silently diverge on which statuses
    /// hold a slice.
    Set<SchemaStatus> BLOCKING_SCHEMA_STATUSES = Set.of(SchemaStatus.PENDING,
                                                        SchemaStatus.MIGRATING,
                                                        SchemaStatus.FAILED);

    /// #760 review BLOCKING 1: the SAME predicate the activation gate uses
    /// ([Active#collectIfBlocking]) to decide whether a schema record holds a slice, exposed so
    /// `SchemaRoutes.heldSlices` reads live per-node state instead of re-deriving a parallel
    /// ownership-only check that ignores [SliceState] entirely.
    ///
    /// A slice is held only while it sits in [SliceState#LOADED] — i.e. it has not yet passed the
    /// gate. An [SliceState#ACTIVE] slice already passed it and has no transition path back
    /// through this check ([SliceState#validTransitions]), so re-arming a COMPLETED record to
    /// MIGRATING must not retroactively report a serving slice as held: `sliceState == LOADED` is
    /// load-bearing, not incidental.
    static boolean blocksSliceActivation(SliceState sliceState,
                                         Option<BlueprintId> sliceOwner,
                                         SchemaVersionValue schema,
                                         KVStore<AetherKey, AetherValue> kvStore) {
        return sliceState == SliceState.LOADED
               && BLOCKING_SCHEMA_STATUSES.contains(schema.status())
               && sliceOwner.map(owner -> owner.base()
                                               .equals(schema.owningBlueprint().base()) && resolveSchemaRequired(kvStore,
                                                                                                                 owner))
                            .or(false);
    }

    /// #760 review round 2 item a: `heldSlices` (the management-API view) and the FSM gate
    /// (`blockingSchemaRecords`) previously called schemaRequired resolution through two different
    /// paths — the gate via the in-memory [Active#blueprints] map, the route not at all — so a
    /// slice owned by a `schema_required = false` blueprint could be reported held even though the
    /// gate itself never blocked it. `resolveDeclaredSchemaRequired` is a pure function of the shared
    /// [KVStore], so both call sites now go through this one static method instead of diverging.
    /// Returns [Option#empty()] when nothing could be resolved (missing blueprint entry,
    /// resourcesConfig, or unparsable/incomplete resources.toml) — the caller decides how to log
    /// that and what to default to; see [#resolveSchemaRequired(KVStore, BlueprintId)] and
    /// [Active#resolveSchemaRequired(BlueprintId)].
    static Option<Boolean> resolveDeclaredSchemaRequired(KVStore<AetherKey, AetherValue> kvStore,
                                                         BlueprintId blueprintId) {
        var blueprintKey = AppBlueprintKey.appBlueprintKey(blueprintId);

        return kvStore.get(blueprintKey)
                      .filter(AppBlueprintValue.class::isInstance)
                      .map(AppBlueprintValue.class::cast)
                      .flatMap(value -> value.blueprint()
                                             .resourcesConfig())
                      .flatMap(toml -> BlueprintParser.parse(toml).option())
                      .flatMap(org.pragmatica.aether.slice.blueprint.Blueprint::deploymentConfig)
                      .map(DeploymentConfig::schemaRequired);
    }

    /// Deliberately silent, unlike [Active#resolveSchemaRequired(BlueprintId)]: this is invoked once
    /// per candidate record inside [#blocksSliceActivation] (from both `blockingSchemaRecords`'s
    /// forEach and `heldSlices`'s forEach), so logging here would fire once per slice-node/schema
    /// pair per gate pass or status request instead of once per blueprint change.
    static boolean resolveSchemaRequired(KVStore<AetherKey, AetherValue> kvStore, BlueprintId blueprintId) {
        return resolveDeclaredSchemaRequired(kvStore, blueprintId).or(true);
    }

    record Dormant(ClusterDeploymentContext ctx) implements ClusterDeploymentState {
        @Contract
        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<ClusterDeploymentState, ClusterFsmEvent> tx) {
            switch (event) {
                case Activate _ -> tx.transitionTo(ctx.newActive());
                case Shutdown _ -> tx.transitionTo(ctx.stopped());
                default -> tx.ignore();
            }
        }
    }

    record Stopped(ClusterDeploymentContext ctx) implements ClusterDeploymentState {
        @Contract
        @Override
        public void onEntry() {
            LOG.debug("ClusterDeploymentManager stopped");
        }

        @Contract
        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<ClusterDeploymentState, ClusterFsmEvent> tx) {
            tx.ignore();
        }
    }

    record Active(ClusterDeploymentContext ctx,
                  Map<Artifact, Blueprint> blueprints,
                  Map<SliceNodeKey, SliceState> sliceStates,
                  Map<Artifact, Set<Artifact>> sliceDependencies,
                  Set<ArtifactBase> activeRoutings,
                  Map<String, Integer> retryCounters,
                  Map<BlueprintId, InFlightBlueprint> inFlightBlueprints,
                  Set<BlueprintId> restoringBlueprints,
                  Set<Artifact> permanentlyFailed,
                  Set<NodeId> workerNodes,
                  Map<SliceNodeKey, Long> transitionalStateTimestamps,
                  Map<Artifact, Integer> consecutiveImbalancedTicks,
                  // Per-SliceNodeKey WARN/DEBUG dedup for schema holds (#760 follow-up). In-memory
                  // only, never persisted — resets on leader failover. See #reportSchemaHold's
                  // Javadoc (#760/#724 review round 2 item l) for the accepted consequence.
                  Map<SliceNodeKey, String> reportedSchemaHolds,
                  AtomicInteger allocationIndex,
                  AtomicBoolean deactivated,
                  CancellableTask reconcileTimer) implements ClusterDeploymentState {
        private static final Logger log = LoggerFactory.getLogger(Active.class);
        private static final int MAX_RETRIES = 5;
        private static final long MAX_RETRY_DELAY_SECONDS = 30;
        /// The deterministic, single-community-per-source suffix (worker-membership-spec A10): one
        /// community `<source>-w-0` per source keeps community ids stable across rejoins (no
        /// renumbering). The growth-comparator slice introduces additional `-w-N` slots.
        private static final String WORKER_COMMUNITY_SUFFIX = "-w-0";
        /// The fallback source label for a joining worker whose membership `source` is absent or
        /// blank (worker-membership-spec D2).
        private static final String DEFAULT_SOURCE = "default";

        // --- move-only extraction seams (package-private helpers operating on this Active) ---
        StuckTransitionalRemediator stuckRemediator() {
            return new StuckTransitionalRemediator(this);
        }

        CommunityPlacementPlanner communityPlanner() {
            return new CommunityPlacementPlanner(this);
        }

        SliceAllocationEngine allocationEngine() {
            return new SliceAllocationEngine(this);
        }

        StaleEntryCleaner staleEntryCleaner() {
            return new StaleEntryCleaner(this);
        }

        @Contract
        @Override
        public void onEntry() {
            log.info("Node {} became leader, activating cluster deployment manager with {} known nodes",
                     ctx.self(),
                     activeNodes().size());
            rebuildStateFromKVStore();
            reconcile();
            startReconcileTimer();
            SharedScheduler.schedule(this::deferredTopologyRecheck, timeSpan(2).seconds());
        }

        @Contract
        @Override
        public void onExit() {
            deactivated.set(true);
            cancelReconcileTimer();
            log.trace("Active state deactivated, stale callbacks will be suppressed");
        }

        @Contract
        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<ClusterDeploymentState, ClusterFsmEvent> tx) {
            switch (event) {
                case Deactivate _ -> tx.transitionTo(ctx.dormant());
                case Shutdown _ -> tx.transitionTo(ctx.stopped());
                case AppBlueprintPutReceived(ValuePut<AppBlueprintKey, AppBlueprintValue> valuePut) -> handleAppBlueprintPut(valuePut,
                                                                                                                             tx);
                case SliceTargetPutReceived(ValuePut<SliceTargetKey, SliceTargetValue> valuePut) -> handleSliceTargetPut(valuePut,
                                                                                                                         tx);
                case VersionRoutingPutReceived(ValuePut<VersionRoutingKey, VersionRoutingValue> valuePut) -> handleVersionRoutingPut(valuePut,
                                                                                                                                     tx);
                case AppBlueprintRemoveReceived(ValueRemove<AppBlueprintKey, AppBlueprintValue> valueRemove) -> handleAppBlueprintRemove(valueRemove,
                                                                                                                                         tx);
                case SliceTargetRemoveReceived(ValueRemove<SliceTargetKey, SliceTargetValue> valueRemove) -> handleSliceTargetRemove(valueRemove,
                                                                                                                                     tx);
                case VersionRoutingRemoveReceived(ValueRemove<VersionRoutingKey, VersionRoutingValue> valueRemove) -> handleVersionRoutingRemove(valueRemove,
                                                                                                                                                 tx);
                case MembershipDecisionReceived(MembershipDecision decision) -> handleMembershipDecision(decision, tx);
                case WorkerJoinReceived(WorkerJoinDecision decision) -> handleWorkerJoin(decision, tx);
                case WorkerLeaveReceived(WorkerLeaveDecision decision) -> handleWorkerLeave(decision, tx);
                case SelfShutdownReceived(TransportObservation.SelfShutdown selfShutdown) -> handleSelfShutdown(selfShutdown,
                                                                                                                tx);
                case ActivationDirectivePutReceived(ValuePut<ActivationDirectiveKey, ActivationDirectiveValue> valuePut) -> handleActivationDirectivePut(valuePut,
                                                                                                                                                         tx);
                case ActivationDirectiveRemoveReceived(ValueRemove<ActivationDirectiveKey, ActivationDirectiveValue> valueRemove) -> handleActivationDirectiveRemove(valueRemove,
                                                                                                                                                                     tx);
                case NodeArtifactPutReceived(ValuePut<NodeArtifactKey, NodeArtifactValue> valuePut) -> handleNodeArtifactPut(valuePut,
                                                                                                                             tx);
                case NodeArtifactRemoveReceived(ValueRemove<NodeArtifactKey, NodeArtifactValue> valueRemove) -> handleNodeArtifactRemove(valueRemove,
                                                                                                                                         tx);
                case SchemaVersionPutReceived(ValuePut<SchemaVersionKey, SchemaVersionValue> valuePut) -> handleSchemaVersionPut(valuePut,
                                                                                                                                 tx);
                default -> tx.ignore();
            }
        }

        private void handleAppBlueprintPut(ValuePut<AppBlueprintKey, AppBlueprintValue> valuePut,
                                           TransitionRequest<ClusterDeploymentState, ClusterFsmEvent> tx) {
            tx.handle(() -> handleAppBlueprintChange(valuePut.cause().key(),
                                                     valuePut.cause().value()));
        }

        private void handleSliceTargetPut(ValuePut<SliceTargetKey, SliceTargetValue> valuePut,
                                          TransitionRequest<ClusterDeploymentState, ClusterFsmEvent> tx) {
            tx.handle(() -> handleSliceTargetChange(valuePut.cause().key(),
                                                    valuePut.cause().value()));
        }

        private void handleVersionRoutingPut(ValuePut<VersionRoutingKey, VersionRoutingValue> valuePut,
                                             TransitionRequest<ClusterDeploymentState, ClusterFsmEvent> tx) {
            tx.handle(() -> processVersionRoutingPut(valuePut));
        }

        private void processVersionRoutingPut(ValuePut<VersionRoutingKey, VersionRoutingValue> valuePut) {
            var routingKey = valuePut.cause().key();

            log.info("Rolling update started for {}", routingKey.artifactBase());
            activeRoutings.add(routingKey.artifactBase());
        }

        private void handleAppBlueprintRemove(ValueRemove<AppBlueprintKey, AppBlueprintValue> valueRemove,
                                              TransitionRequest<ClusterDeploymentState, ClusterFsmEvent> tx) {
            tx.handle(() -> handleAppBlueprintRemoval(valueRemove.cause().key()));
        }

        private void handleSliceTargetRemove(ValueRemove<SliceTargetKey, SliceTargetValue> valueRemove,
                                             TransitionRequest<ClusterDeploymentState, ClusterFsmEvent> tx) {
            tx.handle(() -> processSliceTargetRemove(valueRemove));
        }

        private void processSliceTargetRemove(ValueRemove<SliceTargetKey, SliceTargetValue> valueRemove) {
            var key = valueRemove.cause().key();
            var artifactBase = key.artifactBase();

            blueprints.keySet().stream().filter(artifactBase::matches).toList().forEach(this::issueDeallocationCommands);
        }

        private void handleVersionRoutingRemove(ValueRemove<VersionRoutingKey, VersionRoutingValue> valueRemove,
                                                TransitionRequest<ClusterDeploymentState, ClusterFsmEvent> tx) {
            tx.handle(() -> handleRoutingRemoval(valueRemove.cause().key()));
        }

        private void handleMembershipDecision(MembershipDecision decision,
                                              TransitionRequest<ClusterDeploymentState, ClusterFsmEvent> tx) {
            tx.handle(() -> processMembershipDecision(decision));
        }

        /// The non-core join channel (#728). A worker never appears in `MembershipDecision`, so
        /// without this arm `assignNodeRole` was unreachable for the only nodes that actually need
        /// a community: labelled workers reached FSM Member and were never assigned a role, never
        /// minted a community, and never activated.
        ///
        /// Routed straight to [`#assignNodeRole`] rather than through [`#handleNodeAdded`]: the
        /// seed-node guard there is a CORE concern (seeds are SWIM-derived to present by the
        /// membership-v2 view and need no directive), and `reconcile()` is driven by the core
        /// delta, which a worker join deliberately does not perturb.
        private void handleWorkerJoin(WorkerJoinDecision decision,
                                      TransitionRequest<ClusterDeploymentState, ClusterFsmEvent> tx) {
            tx.handle(() -> processWorkerJoin(decision));
        }

        private void processWorkerJoin(WorkerJoinDecision decision) {
            log.info("Received worker join: {} (role={})", decision.nodeId(), decision.role());
            assignNodeRole(decision.nodeId());
        }

        /// The non-core leave channel (#731), symmetric to [`#handleWorkerJoin`]. Routed straight
        /// to the same [`#handleNodeRemoval`] a CORE `NodeRemoved`/`NodeDecommissioned`/self-shutdown
        /// already uses — no new cleanup logic, because a worker's KV footprint
        /// (`SliceNodeKey`/`NodeArtifactKey`/`NodeRoutesKey`) and its `workerNodes` allocation-pool
        /// entry are written and keyed identically to a core node's. `reconcile()` afterward is what
        /// re-places the departed worker's slice instances onto the remaining pool.
        private void handleWorkerLeave(WorkerLeaveDecision decision,
                                       TransitionRequest<ClusterDeploymentState, ClusterFsmEvent> tx) {
            tx.handle(() -> processWorkerLeave(decision));
        }

        private void processWorkerLeave(WorkerLeaveDecision decision) {
            log.info("Received worker leave: {}", decision.nodeId());
            handleNodeRemoval(decision.nodeId()).onSuccess(_ -> reconcile());
        }

        private void handleSelfShutdown(TransportObservation.SelfShutdown selfShutdown,
                                        TransitionRequest<ClusterDeploymentState, ClusterFsmEvent> tx) {
            tx.handle(() -> processSelfShutdown(selfShutdown.nodeId()));
        }

        private void processMembershipDecision(MembershipDecision decision) {
            log.info("Received membership decision: {}", decision);
            switch (decision) {
                case NodeJoined(NodeId addedNode, List<NodeId>_, _, _) -> handleNodeAdded(addedNode);
                case NodeRemoved(NodeId removedNode, List<NodeId>_, _, _) -> handleNodeRemoval(removedNode).onSuccess(_ -> reconcile());
                case NodeDecommissioned(NodeId removedNode, List<NodeId>_, _, _) -> {
                    cleanupAfterLifecycleDepartedAtomic(removedNode);
                    handleNodeRemoval(removedNode).onSuccess(_ -> reconcile());
                }
                case NodeJoining _ -> {}
                case NodeDraining(NodeId drainingNode, _, _, _) -> startDrainEviction(drainingNode);
                case NodeFailedDrain(NodeId failedDrainNode, _, _, _) -> log.warn("Node {} drain failed — operator intervention may be required",
                                                                                  failedDrainNode);
                case MembershipDecision.NodeShuttingDown _ -> {}
            }
        }

        private void handleNodeAdded(NodeId addedNode) {
            // Seed nodes are SWIM-derived to present by the membership-v2 view; only
            // non-seed nodes need an explicit role assignment via ActivationDirective.
            if (!ctx.seedNodes().contains(addedNode)) {
                assignNodeRole(addedNode);
            }

            reconcile();
            if (allocatableNodes().isEmpty()) {
                log.info("No allocatable nodes after NodeJoined (snapshot not yet ready); scheduling retry in 2s");
                SharedScheduler.schedule(this::reconcileIfActive, timeSpan(2).seconds());
            }
        }

        private void processSelfShutdown(NodeId downNode) {
            log.warn("Self {} is shutting down, triggering immediate reconciliation", downNode);
            handleNodeRemoval(downNode).onSuccess(_ -> reconcile());
        }

        private void handleActivationDirectivePut(ValuePut<ActivationDirectiveKey, ActivationDirectiveValue> valuePut,
                                                  TransitionRequest<ClusterDeploymentState, ClusterFsmEvent> tx) {
            tx.handle(() -> processActivationDirectivePutEvent(valuePut));
        }

        private void processActivationDirectivePutEvent(ValuePut<ActivationDirectiveKey, ActivationDirectiveValue> valuePut) {
            var nodeId = valuePut.cause().key().nodeId();
            var role = valuePut.cause().value().role();

            processActivationDirectivePut(nodeId, role);
        }

        private void handleActivationDirectiveRemove(ValueRemove<ActivationDirectiveKey, ActivationDirectiveValue> valueRemove,
                                                     TransitionRequest<ClusterDeploymentState, ClusterFsmEvent> tx) {
            tx.handle(() -> processActivationDirectiveRemove(valueRemove));
        }

        private void processActivationDirectiveRemove(ValueRemove<ActivationDirectiveKey, ActivationDirectiveValue> valueRemove) {
            var nodeId = valueRemove.cause().key().nodeId();

            if (workerNodes.remove(nodeId)) {
                log.info("Worker node {} deregistered, total workers: {}", nodeId, workerNodes.size());
                reconcile();
            }
        }

        private void handleNodeArtifactPut(ValuePut<NodeArtifactKey, NodeArtifactValue> valuePut,
                                           TransitionRequest<ClusterDeploymentState, ClusterFsmEvent> tx) {
            tx.handle(() -> processNodeArtifactPut(valuePut));
        }

        private void processNodeArtifactPut(ValuePut<NodeArtifactKey, NodeArtifactValue> valuePut) {
            var key = valuePut.cause().key();
            var value = valuePut.cause().value();

            trackSliceState(SliceNodeKey.sliceNodeKey(key.artifact(), key.nodeId()),
                            new SliceNodeValue(value.state(),
                                               value.failureReason(),
                                               value.fatal(),
                                               value.transitionedAt()));
        }

        private void handleNodeArtifactRemove(ValueRemove<NodeArtifactKey, NodeArtifactValue> valueRemove,
                                              TransitionRequest<ClusterDeploymentState, ClusterFsmEvent> tx) {
            tx.handle(() -> processNodeArtifactRemove(valueRemove));
        }

        private void processNodeArtifactRemove(ValueRemove<NodeArtifactKey, NodeArtifactValue> valueRemove) {
            var key = valueRemove.cause().key();

            handleSliceNodeRemoval(SliceNodeKey.sliceNodeKey(key.artifact(), key.nodeId()));
        }

        private void handleSchemaVersionPut(ValuePut<SchemaVersionKey, SchemaVersionValue> valuePut,
                                            TransitionRequest<ClusterDeploymentState, ClusterFsmEvent> tx) {
            tx.handle(() -> processSchemaVersionPut(valuePut));
        }

        private void processSchemaVersionPut(ValuePut<SchemaVersionKey, SchemaVersionValue> valuePut) {
            var value = valuePut.cause().value();
            var datasource = value.datasourceName();

            switch (value.status()) {
                case PENDING -> handleSchemaPending(datasource);
                case COMPLETED -> handleSchemaCompleted(datasource);
                case FAILED -> handleSchemaFailed(value);
                case MIGRATING -> log.debug("Schema migration in progress for datasource: {}", datasource);
            }
        }

        @Contract
        public void startReconcileTimer() {
            reconcileTimer.set(SharedScheduler.scheduleAtFixedRate(this::reconcileIfActive, ctx.reconcileInterval()));
        }

        private void reconcileIfActive() {
            if (!deactivated.get()) {
                reconcile();
            }
        }

        private void cancelReconcileTimer() {
            reconcileTimer.cancel();
        }

        @Contract
        public void rebuildStateFromKVStore() {
            log.info("Rebuilding cluster deployment state from KVStore");
            ctx.kvStore().forEach(AetherKey.class, AetherValue.class, this::processKVEntry);
            log.info("Restored {} blueprints and {} worker nodes from KVStore", blueprints.size(), workerNodes.size());
            rebuildSliceStateFromKVStoreEntries();
            triggerLoadedSliceActivation();
            cleanupStaleNodeRoutes();
            cleanupStaleSliceEntries();
            cleanupStaleNodeArtifactEntries();
            cleanupOrphanedSliceEntries();
            resumeDrainEvictions();
            recoverStalledSchemaMigrations();
        }

        private void deferredTopologyRecheck() {
            if (deactivated.get()) {
                return;
            }

            cleanupStaleNodeRoutes();
            cleanupStaleSliceEntries();
            cleanupStaleNodeArtifactEntries();
            reconcile();
        }

        private void resumeDrainEvictions() {
            var draining = drainingNodes();

            if (draining.isEmpty()) {
                return;
            }

            log.info("Resuming drain evictions for {} nodes", draining.size());
            draining.forEach(this::evictNextSliceFromNode);
        }

        /// Rebuild-time schema recovery. Two distinct ways a migration stalls, both of which strand
        /// every slice of the owning blueprint in LOADED indefinitely — LOADED carries no timeout, and
        /// only FAILED is reported above DEBUG, so the outage is indistinguishable from a healthy
        /// cluster at every operator-visible signal.
        ///
        /// - MIGRATING with an expired lock: died mid-flight. Reset to PENDING; that Put re-fires
        ///   `processSchemaVersionPut`.
        /// - PENDING: never started. `processSchemaVersionPut` is the ONLY caller of
        ///   `handleSchemaPending`, so a PENDING Put that lands before this FSM reaches Active is lost,
        ///   and no later Put is ever issued for a record that is already PENDING — nothing retried it.
        ///   Re-dispatch directly rather than rewriting the same value: `migrateIfNeeded` re-reads the
        ///   record, no-ops unless it is still PENDING, and guards concurrent entry with
        ///   `inFlightMigrations` plus a TTL'd lock, so a redundant call costs nothing.
        private void recoverStalledSchemaMigrations() {
            var stalledDatasources = new ArrayList<String>();
            var pendingDatasources = new ArrayList<String>();

            ctx.kvStore()
               .forEach(SchemaVersionKey.class,
                        SchemaVersionValue.class,
                        (_, value) -> collectSchemaRecovery(value, stalledDatasources, pendingDatasources));
            if (!stalledDatasources.isEmpty()) {
                log.info("Found {} stalled schema migrations, resetting to PENDING", stalledDatasources.size());
                stalledDatasources.forEach(this::resetStalledMigration);
            }

            if (!pendingDatasources.isEmpty()) {
                log.info("Found {} schema migrations still PENDING at rebuild, re-dispatching to the orchestrator",
                         pendingDatasources.size());
                pendingDatasources.forEach(this::handleSchemaPending);
            }
        }

        private void collectSchemaRecovery(SchemaVersionValue value,
                                           List<String> stalledDatasources,
                                           List<String> pendingDatasources) {
            collectStalledMigration(value, stalledDatasources);
            if (value.status() == SchemaStatus.PENDING) {
                pendingDatasources.add(value.datasourceName());
            }
        }

        private void collectStalledMigration(SchemaVersionValue value, List<String> stalledDatasources) {
            if (value.status() != SchemaStatus.MIGRATING) {
                return;
            }

            var lockKey = SchemaMigrationLockKey.schemaMigrationLockKey(value.datasourceName());
            var lockExpired = ctx.kvStore()
                                 .get(lockKey)
                                 .filter(SchemaMigrationLockValue.class::isInstance)
                                 .map(SchemaMigrationLockValue.class::cast)
                                 .map(SchemaMigrationLockValue::isExpired)
                                 .or(true);

            if (lockExpired) {
                stalledDatasources.add(value.datasourceName());
            }
        }

        private void resetStalledMigration(String datasourceName) {
            log.info("Resetting stalled schema migration for '{}' to PENDING", datasourceName);
            var versionKey = SchemaVersionKey.schemaVersionKey(datasourceName);

            ctx.kvStore()
               .get(versionKey)
               .filter(SchemaVersionValue.class::isInstance)
               .map(SchemaVersionValue.class::cast)
               .onPresent(value -> submitStalledMigrationReset(datasourceName, versionKey, value));
        }

        private void submitStalledMigrationReset(String datasourceName,
                                                 SchemaVersionKey versionKey,
                                                 SchemaVersionValue value) {
            var updated = SchemaVersionValue.schemaVersionValue(datasourceName,
                                                                value.currentVersion(),
                                                                value.lastMigration(),
                                                                SchemaStatus.PENDING,
                                                                value.artifactCoords(),
                                                                value.owningBlueprint(),
                                                                value.attemptCount());
            var lockKey = SchemaMigrationLockKey.schemaMigrationLockKey(datasourceName);
            var commands = List.<KVCommand<AetherKey>> of(new KVCommand.Put<>(versionKey, updated),
                                                          new KVCommand.Remove<>(lockKey));

            ctx.cluster()
               .apply(commands)
               .onFailure(cause -> log.error("Failed to reset stalled migration for '{}': {}",
                                             datasourceName,
                                             cause.message()));
        }

        private void triggerLoadedSliceActivation() {
            var loadedSlices = sliceStates.entrySet()
                                          .stream()
                                          .filter(e -> e.getValue() == SliceState.LOADED)
                                          .map(Map.Entry::getKey)
                                          .toList();

            if (!loadedSlices.isEmpty()) {
                log.info("Found {} slices in LOADED state, checking dependencies for activation", loadedSlices.size());
                loadedSlices.forEach(this::tryActivateIfDependenciesReady);
            }
        }

        private void processKVEntry(AetherKey key, AetherValue value) {
            switch (key) {
                case AppBlueprintKey _ when value instanceof AppBlueprintValue appBlueprintValue -> restoreAppBlueprint(appBlueprintValue);
                case SliceTargetKey sliceTargetKey when value instanceof SliceTargetValue sliceTargetValue -> restoreSliceTarget(sliceTargetKey,
                                                                                                                                 sliceTargetValue);
                case SliceNodeKey _ -> {}
                case VersionRoutingKey routingKey -> activeRoutings.add(routingKey.artifactBase());
                case ActivationDirectiveKey activationKey when value instanceof ActivationDirectiveValue activationValue -> restoreWorkerNode(activationKey,
                                                                                                                                              activationValue);
                default -> {}
            }
        }

        private void restoreWorkerNode(ActivationDirectiveKey key, ActivationDirectiveValue value) {
            if (ActivationDirectiveValue.WORKER.equals(value.role())) {
                workerNodes.add(key.nodeId());
                log.trace("Restored worker node: {}", key.nodeId());
            }
        }

        private void restoreAppBlueprint(AppBlueprintValue appBlueprintValue) {
            var expanded = appBlueprintValue.blueprint();
            var registerOnly = appBlueprintValue.registerOnly();

            log.trace("Restored app blueprint: {} with {} slices (registerOnly={})",
                      expanded.id().asString(),
                      expanded.loadOrder().size(),
                      registerOnly);
            buildDependencyMap(expanded);
            // Loop-invariant: same as handleAppBlueprintChange, resolve once rather than per slice.
            var schemaRequired = resolveSchemaRequired(expanded.id());

            for (var slice : expanded.loadOrder()) {
                var artifact = slice.artifact();

                if (shouldSuppressActivation(registerOnly)) {
                    log.trace("Restore: register-only blueprint {} — skipping in-memory blueprints.put for slice {} (existing SliceTargetValue present)",
                              expanded.id().asString(),
                              artifact);
                    continue;
                }

                blueprints.put(artifact,
                               Blueprint.blueprint(artifact,
                                                   slice.instances(),
                                                   slice.minAvailable(),
                                                   Option.some(expanded.id()),
                                                   schemaRequired));
            }
        }

        private void restoreSliceTarget(SliceTargetKey sliceTargetKey, SliceTargetValue sliceTargetValue) {
            var artifact = sliceTargetKey.artifactBase().withVersion(sliceTargetValue.currentVersion());
            var instances = sliceTargetValue.targetInstances();
            var minInstances = sliceTargetValue.effectiveMinInstances();
            var owner = sliceTargetValue.owningBlueprint();
            // Unowned slices keep the historical default (true, preserving prior behavior); owned
            // slices resolve schemaRequired via their blueprint, same as handleAppBlueprintChange.
            boolean schemaRequired = owner.map(this::resolveSchemaRequired).or(true);

            blueprints.put(artifact, Blueprint.blueprint(artifact, instances, minInstances, owner, schemaRequired));
            log.trace("Restored slice target: {} with {} instances (min: {})", artifact, instances, minInstances);
        }

        private void rebuildSliceStateFromKVStoreEntries() {
            ctx.kvStore()
               .forEach(NodeArtifactKey.class, NodeArtifactValue.class, this::restoreSliceStateFromNodeArtifact);
            log.info("Restored {} slice states from KV-Store", sliceStates.size());
        }

        private void restoreSliceStateFromNodeArtifact(NodeArtifactKey key, NodeArtifactValue value) {
            var sliceKey = SliceNodeKey.sliceNodeKey(key.artifact(), key.nodeId());

            sliceStates.put(sliceKey, value.state());
            updateTransitionalTimestamp(sliceKey, value.state(), value.transitionedAt());
        }

        private void handleSchemaPending(String datasource) {
            log.info("Schema migration pending for datasource: {}", datasource);
            ctx.schemaOrchestrator()
               .migrateIfNeeded(datasource)
               .onFailure(cause -> log.error("Schema migration failed for {}: {}",
                                             datasource,
                                             cause.message()));
        }

        private void handleSchemaCompleted(String datasource) {
            log.info("Schema migration completed for datasource: {}", datasource);
            sliceStates.entrySet()
                       .stream()
                       .filter(entry -> entry.getValue() == SliceState.LOADED)
                       .map(Map.Entry::getKey)
                       .toList()
                       .forEach(this::tryActivateIfDependenciesReady);
        }

        /// #542: a FAILED record holds every slice of its owning blueprint (`areSchemasReady`), so
        /// the hold must be reported rather than inferred from a deploy that silently never
        /// finishes. The orchestrator's own `MigrationFailed` names the failure but always carries
        /// an empty `blockedSlices` — it has no deployment state to consult. The leader does, so it
        /// emits the consequence here: which slices are held, and by whose migration.
        private void handleSchemaFailed(SchemaVersionValue value) {
            var owner = value.owningBlueprint();
            var blockedSlices = slicesOwnedBy(owner);

            log.error("Schema migration FAILED for datasource '{}' (owner '{}') — holding activation of {} slice(s) {};"
                     + " clear with POST /api/schema/{}/retry or redeploy the blueprint",
                      value.datasourceName(),
                      owner.asString(),
                      blockedSlices.size(),
                      blockedSlices,
                      value.datasourceName());
            AuditLog.schemaActivationBlocked(value.datasourceName(), owner.asString(), blockedSlices);
            ctx.router()
               .route(ActivationBlocked.activationBlocked(value.datasourceName(),
                                                          owner,
                                                          blockedSlices,
                                                          value.artifactCoords(),
                                                          value.attemptCount()));
        }

        private List<String> slicesOwnedBy(BlueprintId owner) {
            return blueprints.entrySet()
                             .stream()
                             .filter(entry -> isOwnedBy(owner,
                                                        entry.getValue()))
                             .map(entry -> entry.getKey()
                                                .asString())
                             .sorted()
                             .toList();
        }

        private static boolean isOwnedBy(BlueprintId owner, Blueprint blueprint) {
            return blueprint.schemaRequired() && blueprint.owner()
                                                          .map(BlueprintId::base)
                                                          .filter(owner.base()::equals)
                                                          .isPresent();
        }

        private void handleSliceNodeRemoval(SliceNodeKey sliceNodeKey) {
            sliceStates.remove(sliceNodeKey);
            transitionalStateTimestamps.remove(sliceNodeKey);
            reportedSchemaHolds.remove(sliceNodeKey);
            if (permanentlyFailed.contains(sliceNodeKey.artifact())) {
                return;
            }

            SharedScheduler.schedule(this::reconcile, timeSpan(1).seconds());
        }

        private void handleAppBlueprintRemoval(AppBlueprintKey key) {
            var removedBlueprintId = key.blueprintId();
            var rollingUpdateArtifacts = blueprints.entrySet()
                                                   .stream()
                                                   .filter(e -> e.getValue()
                                                                 .owner()
                                                                 .equals(Option.some(removedBlueprintId)))
                                                   .map(Map.Entry::getKey)
                                                   .filter(a -> activeRoutings.contains(a.base()))
                                                   .toList();

            if (!rollingUpdateArtifacts.isEmpty()) {
                log.warn("Cannot delete blueprint '{}' — artifacts {} have active rolling updates",
                         removedBlueprintId.artifact().asString(),
                         rollingUpdateArtifacts);

                return;
            }

            log.info("App blueprint '{}' removed",
                     removedBlueprintId.artifact().asString());
            var artifactsToRemove = blueprints.entrySet()
                                              .stream()
                                              .filter(e -> e.getValue()
                                                            .owner()
                                                            .equals(Option.some(removedBlueprintId)))
                                              .map(Map.Entry::getKey)
                                              .toList();
            var consensusCommands = new ArrayList<KVCommand<AetherKey>>();

            for (var artifact : artifactsToRemove) {
                blueprints.remove(artifact);
                issueDeallocationCommands(artifact);
                consensusCommands.add(new KVCommand.Remove<>(SliceTargetKey.sliceTargetKey(artifact.base())));
            }

            submitBatch(consensusCommands);
            SharedScheduler.schedule(this::reconcile, timeSpan(5).seconds());
        }

        /// The joining node MUST be excluded from its own denominator. `activeNodes()` derives
        /// from `MembershipFsm.coreCountedMembers()`, which already includes the joiner by the
        /// time the NodeJoined decision reaches this method (the FSM stamps it Member first;
        /// Wave-4's edge-driven emission makes that ordering deterministic). A self-inclusive
        /// count made every count-restoring replacement see "core count at max" (e.g. a 5-target
        /// cluster healed back to exactly 5 → count 5 ≥ max 5) and demoted it to WORKER —
        /// observer-mode engine, NodeReportedState stuck SYNCING, voter set decaying until
        /// consensus died. The joiner is classified by the cluster's state WITHOUT it.
        ///
        /// Defense-in-depth alternative (deliberately NOT implemented here): honor CTM's
        /// provision-time intended role instead of re-deriving the role from membership counts —
        /// candidate for a later wave.
        private void assignNodeRole(NodeId addedNode) {
            var currentCoreCount = (int) activeNodes().stream().filter(node -> !node.equals(addedNode)).count();

            if (shouldPromoteToCore(currentCoreCount)) {
                log.info("Promoting node {} to core consensus participant (core count: {}/{})",
                         addedNode,
                         currentCoreCount,
                         ctx.coreMax() == 0
                         ? "unlimited"
                         : ctx.coreMax());
                submitActivationDirective(addedNode, ActivationDirectiveValue.core());
            } else {
                log.info("Assigning node {} as worker (core count at max: {})", addedNode, ctx.coreMax());
                assignWorkerRole(addedNode);
            }
        }

        /// Community-aware WORKER role assignment (worker-membership-spec §4.1 / §3.3): resolve the
        /// joining node's source (defaulting to `"default"` when absent/blank, D2), derive the
        /// deterministic single community id `<source>-w-0` (A10-stable), and atomically commit —
        /// in one batch — a FORMING [`CommunityKey`] Put (only when the community does not yet exist;
        /// reuse otherwise, no renumber) together with the community-assigned WORKER
        /// [`ActivationDirectiveKey`] Put. The directive carries an empty governor hint because a
        /// FORMING community has no governor yet (§4.1 step 5).
        private void assignWorkerRole(NodeId addedNode) {
            var source = resolveSource(addedNode);
            var communityId = source + WORKER_COMMUNITY_SUFFIX;
            var commands = new ArrayList<KVCommand<AetherKey>>();

            if (!communityExists(communityId)) {
                log.info("Minting FORMING community '{}' for source '{}' (worker {})", communityId, source, addedNode);
                commands.add(mintCommunityCommand(communityId, source));
            }

            commands.add(workerDirectiveCommand(addedNode, communityId));
            submitActivationCommands(addedNode, commands);
        }

        /// The joining node's membership source label, normalized to the `"default"` fallback when
        /// the descriptor is absent or its source is blank (worker-membership-spec D2).
        private String resolveSource(NodeId addedNode) {
            return ctx.memberSource(addedNode)
                      .filter(source -> !source.isBlank())
                      .or(DEFAULT_SOURCE);
        }

        private boolean communityExists(String communityId) {
            return ctx.kvStore()
                      .get(CommunityKey.communityKey(communityId))
                      .filter(CommunityValue.class::isInstance)
                      .isPresent();
        }

        private KVCommand<AetherKey> mintCommunityCommand(String communityId, String source) {
            return new KVCommand.Put<>(CommunityKey.communityKey(communityId),
                                       CommunityValue.communityValue(source,
                                                                     ActivationDirectiveValue.WORKER,
                                                                     ctx.communitySizing().targetSize()));
        }

        private KVCommand<AetherKey> workerDirectiveCommand(NodeId targetNode, String communityId) {
            return new KVCommand.Put<>(ActivationDirectiveKey.activationDirectiveKey(targetNode),
                                       ActivationDirectiveValue.worker(communityId, ""));
        }

        private void submitActivationDirective(NodeId targetNode, ActivationDirectiveValue directive) {
            var command = new KVCommand.Put<AetherKey, AetherValue>(ActivationDirectiveKey.activationDirectiveKey(targetNode),
                                                                    directive);

            submitActivationCommands(targetNode, List.of(command));
        }

        private void submitActivationCommands(NodeId targetNode, List<KVCommand<AetherKey>> commands) {
            ctx.cluster()
               .apply(commands)
               .onFailure(cause -> log.error("Failed to submit activation directive for {}: {}",
                                             targetNode,
                                             cause.message()));
        }

        private boolean shouldPromoteToCore(int currentCoreCount) {
            var effectiveMax = effectiveCoreMax();

            return effectiveMax == 0 || currentCoreCount < effectiveMax;
        }

        private int effectiveCoreMax() {
            return ctx.kvStore()
                      .get(ClusterConfigKey.CURRENT)
                      .flatMap(v -> v instanceof ClusterConfigValue cfg
                                    ? Option.some(cfg.coreCount())
                                    : Option.<Integer> none())
                      .or(ctx.coreMax());
        }

        /// The CORE membership the CDM allocates/counts over (cluster-topology-overhaul spec,
        /// Wave 2 / W3+W6). The supplier is core-scoped at the wiring seam
        /// (`MembershipFsm.coreCountedMembers()` — descriptor-role-based, worker excluded), so a
        /// worker can never enter the role-assignment denominator, `allocatableNodes()`, or the
        /// `AllocationPool.coreNodes` / CORE_ONLY placement pool. The former transport
        /// `isPassive` filter was structurally always false (transport PASSIVE is never
        /// produced) and is gone — role filtering lives at the descriptor seam.
        public List<NodeId> activeNodes() {
            return List.copyOf(ctx.coreCountedMembersSupplier().get());
        }

        /// M4 not-yet-wired guard (cluster-topology-overhaul Wave 9 item 5). True only once the
        /// `MembershipFsm` core-membership supplier is wired; false during the boot window when it
        /// still yields the identity-distinguished [`MembershipFsm#MEMBERSHIP_NOT_WIRED`] sentinel.
        /// Stale-entry cleanups that diff KV state against `activeNodes()` MUST consult this so a
        /// cleanup racing the wiring does not read an unresolved (sentinel) member set and
        /// mass-classify every KV-known member as departed.
        boolean coreMembershipResolved() {
            return ctx.coreCountedMembersSupplier()
                      .get() != MembershipFsm.MEMBERSHIP_NOT_WIRED;
        }

        public Set<NodeId> drainingNodes() {
            return ctx.drainingNodesSupplier()
                      .get();
        }

        List<NodeId> allocatableNodes() {
            var readyNodes = ctx.readyNodesSupplier().get();

            return activeNodes().stream()
                              .filter(readyNodes::contains)
                              .toList();
        }

        AllocationPool buildAllocationPool() {
            return allocationEngine().buildAllocationPool();
        }

        private void cleanupAfterLifecycleDepartedAtomic(NodeId departedNode) {
            log.info("Snapshot-delta cleanup triggered for departed node {} (lifecycle=DECOMMISSIONED)", departedNode);
            var sliceKeysToRemove = sliceStates.keySet()
                                               .stream()
                                               .filter(key -> key.nodeId()
                                                                 .equals(departedNode))
                                               .toList();

            sliceKeysToRemove.forEach(sliceStates::remove);
            sliceKeysToRemove.forEach(transitionalStateTimestamps::remove);
            var artifactKeysToRemove = findNodeArtifactKeysForNode(departedNode);
            var nodeRouteCommands = cleanupNodeRoutesForNode(departedNode);
            var consensusCommands = new ArrayList<KVCommand<AetherKey>>();

            artifactKeysToRemove.stream()
                                .<KVCommand<AetherKey>> map(KVCommand.Remove::new)
                                .forEach(consensusCommands::add);
            sliceKeysToRemove.stream().<KVCommand<AetherKey>> map(KVCommand.Remove::new).forEach(consensusCommands::add);
            consensusCommands.addAll(nodeRouteCommands);
            workerNodes.remove(departedNode);
            if (consensusCommands.isEmpty()) {
                log.debug("Snapshot-delta cleanup for {} — no dependent KV entries to remove", departedNode);

                return;
            }

            log.info("Snapshot-delta cleanup for {}: removing {} node-artifact(s), {} slice-node(s), {} node-route(s) atomically",
                     departedNode,
                     artifactKeysToRemove.size(),
                     sliceKeysToRemove.size(),
                     nodeRouteCommands.size());
            ctx.cluster()
               .apply(consensusCommands)
               .onFailure(cause -> log.error("Snapshot-delta cleanup for {} failed: {}",
                                             departedNode,
                                             cause.message()));
        }

        private void processActivationDirectivePut(NodeId nodeId, String role) {
            if (ActivationDirectiveValue.WORKER.equals(role)) {
                addWorkerNode(nodeId);
            } else {
                workerNodes.remove(nodeId);
            }
        }

        private void addWorkerNode(NodeId nodeId) {
            if (workerNodes.add(nodeId)) {
                log.info("Worker node {} registered, total workers: {}", nodeId, workerNodes.size());
                reconcile();
            }
        }

        private void startDrainEviction(NodeId drainingNode) {
            log.info("Starting drain eviction for node {}", drainingNode);
            evictNextSliceFromNode(drainingNode);
        }

        private void evictNextSliceFromNode(NodeId drainingNode) {
            if (deactivated.get() || !drainingNodes().contains(drainingNode)) {
                return;
            }

            var slicesOnNode = sliceStates.keySet()
                                          .stream()
                                          .filter(key -> key.nodeId()
                                                            .equals(drainingNode))
                                          .filter(key -> isLiveState(sliceStates.getOrDefault(key, SliceState.FAILED)))
                                          .toList();

            if (slicesOnNode.isEmpty()) {
                completeDrain(drainingNode);

                return;
            }

            var sliceKey = slicesOnNode.getFirst();

            log.info("Drain eviction: deploying replacement for {} from node {}", sliceKey.artifact(), drainingNode);
            deployReplacementForDrain(sliceKey);
        }

        private void deployReplacementForDrain(SliceNodeKey originalKey) {
            var artifact = originalKey.artifact();
            var drainingNode = originalKey.nodeId();
            var targetNodes = allocatableNodes().stream()
                                              .filter(n -> !n.equals(drainingNode))
                                              .collect(Collectors.toSet());
            var allocated = issueAllocationsForNodes(artifact, 1, targetNodes);

            if (allocated == 0) {
                log.warn("Drain eviction: no allocatable node for replacement of {} (will retry)", artifact);
                SharedScheduler.schedule(() -> evictNextSliceFromNode(drainingNode), timeSpan(5).seconds());

                return;
            }

            SharedScheduler.schedule(() -> checkReplacementAndUnload(originalKey), timeSpan(3).seconds());
        }

        private void checkReplacementAndUnload(SliceNodeKey originalKey) {
            if (deactivated.get() || !drainingNodes().contains(originalKey.nodeId())) {
                return;
            }

            var artifact = originalKey.artifact();
            var drainingNode = originalKey.nodeId();
            var hasActiveReplacement = sliceStates.entrySet()
                                                  .stream()
                                                  .filter(e -> e.getKey()
                                                                .artifact()
                                                                .equals(artifact))
                                                  .filter(e -> !e.getKey()
                                                                 .nodeId()
                                                                 .equals(drainingNode))
                                                  .anyMatch(e -> e.getValue() == SliceState.ACTIVE);

            if (hasActiveReplacement) {
                log.info("Drain eviction: replacement ACTIVE for {}, unloading from {}", artifact, drainingNode);
                issueUnloadCommand(originalKey);
                SharedScheduler.schedule(() -> evictNextSliceFromNode(drainingNode), timeSpan(2).seconds());
            } else {
                log.debug("Drain eviction: replacement not yet ACTIVE for {}, rechecking", artifact);
                SharedScheduler.schedule(() -> checkReplacementAndUnload(originalKey), timeSpan(3).seconds());
            }
        }

        /// Terminal step of the drain eviction chain: draining completion is observed through the
        /// FSM transition and its log line, and writes no KV command.
        private void completeDrain(NodeId drainingNode) {
            log.info("Drain complete for node {}", drainingNode);
        }

        private void handleSliceTargetChange(SliceTargetKey key, SliceTargetValue value) {
            var artifactBase = key.artifactBase();
            var newVersion = value.currentVersion();
            var newArtifact = artifactBase.withVersion(newVersion);
            var desiredInstances = value.targetInstances();

            if (!activeRoutings.contains(artifactBase)) {
                var oldVersions = blueprints.keySet()
                                            .stream()
                                            .filter(a -> artifactBase.matches(a) && !a.version()
                                                                                      .equals(newVersion))
                                            .toList();

                for (var oldArtifact : oldVersions) {
                    log.info("Removing old version {} (new version: {})", oldArtifact, newArtifact);
                    blueprints.remove(oldArtifact);
                    issueDeallocationCommands(oldArtifact);
                }
            }

            var minInstances = value.effectiveMinInstances();
            var owner = value.owningBlueprint();
            // Unowned slices keep the historical default (true, preserving prior behavior); owned
            // slices resolve schemaRequired via their blueprint, same as handleAppBlueprintChange.
            // Without this, schemaRequired reverted to true on every scale event for owned slices.
            boolean schemaRequired = owner.map(this::resolveSchemaRequired).or(true);

            log.info("Slice target changed for {}: {} instances (min: {})", newArtifact, desiredInstances, minInstances);
            blueprints.put(newArtifact,
                           Blueprint.blueprint(newArtifact, desiredInstances, minInstances, owner, schemaRequired));
            issueAllocationCommandsWithPlacement(newArtifact, desiredInstances, value.effectivePlacement());
        }

        private void handleAppBlueprintChange(AppBlueprintKey key, AppBlueprintValue value) {
            var expanded = value.blueprint();
            var registerOnly = value.registerOnly();
            var nodes = allocatableNodes();

            log.info("App blueprint '{}' deployed with {} slices across {} allocatable nodes (registerOnly={})",
                     expanded.id().asString(),
                     expanded.loadOrder().size(),
                     nodes.size(),
                     registerOnly);
            var previousExpanded = capturePreviousBlueprint(expanded);

            buildDependencyMap(expanded);
            if (hasConflictingOwnership(expanded)) {
                return;
            }

            var consensusCommands = new ArrayList<KVCommand<AetherKey>>();
            var schemaRequired = resolveSchemaRequired(expanded.id());

            for (var slice : expanded.loadOrder()) {
                var artifact = slice.artifact();

                log.info("Scheduling {} with {} requested instances ({} allocatable nodes)",
                         artifact,
                         slice.instances(),
                         nodes.size());
                permanentlyFailed.remove(artifact);
                if (shouldSuppressActivation(registerOnly)) {
                    log.info("Blueprint {} registered-only — skipping SliceTargetValue Put for slice {} (existing currentVersion preserved)",
                             expanded.id().asString(),
                             artifact);
                    continue;
                }

                if (slice.instances() > slice.minAvailable() && slice.maxInstances().isEmpty()) {
                    log.warn("Autoscalable slice {} deployed without maxInstances — scale-up bounded only by cluster size",
                             artifact);
                }

                blueprints.put(artifact,
                               Blueprint.blueprint(artifact,
                                                   slice.instances(),
                                                   slice.minAvailable(),
                                                   Option.some(expanded.id()),
                                                   schemaRequired));
                consensusCommands.add(new KVCommand.Put<>(SliceTargetKey.sliceTargetKey(artifact.base()),
                                                          SliceTargetValue.sliceTargetValue(artifact.version(),
                                                                                            slice.instances(),
                                                                                            slice.minAvailable(),
                                                                                            Option.some(expanded.id()),
                                                                                            slice.maxInstances(),
                                                                                            slice.scaleUpThreshold(),
                                                                                            slice.scaleDownThreshold())));
            }

            collectStreamMetadataCommands(expanded.id(), consensusCommands);
            submitBatch(consensusCommands);
            trackInFlightBlueprint(expanded, previousExpanded);
        }

        /// Returns `true` exactly when the publishing blueprint was registered via
        /// `/api/blueprints/publish` (`registerOnly=true`). A register-only publish registers the
        /// blueprint but NEVER writes or advances a `SliceTargetValue` — activation is performed
        /// exclusively by `/api/deploy`. Suppression is unconditional: it does not depend on whether
        /// an existing `SliceTargetValue` is present. (The former "first-ever publish bootstraps a
        /// fresh slice" exception was removed because it caused register-only to wrongly activate.)
        private boolean shouldSuppressActivation(boolean registerOnly) {
            return registerOnly;
        }

        private boolean hasConflictingOwnership(ExpandedBlueprint expanded) {
            for (var slice : expanded.loadOrder()) {
                var artifactBase = slice.artifact().base();

                for (var bp : blueprints.values()) {
                    if (!artifactBase.equals(bp.artifact().base())) {
                        continue;
                    }

                    var conflict = bp.owner().filter(o -> !o.base()
                                                            .equals(expanded.id().base()));

                    if (!conflict.isEmpty()) {
                        logConflict(expanded, slice.artifact(), conflict);

                        return true;
                    }
                }
            }

            return false;
        }

        private static void logConflict(ExpandedBlueprint expanded, Artifact artifact, Option<BlueprintId> conflict) {
            log.error("Blueprint '{}' rejected — artifact {} already owned by blueprint '{}'. "
                     + "Deploy shared services as independent blueprints.",
                      expanded.id().asString(),
                      artifact,
                      conflict.map(BlueprintId::asString).or(""));
        }

        // #760 review round 2 item a: the KVStore/TOML resolution itself is hoisted to the
        // interface (ClusterDeploymentState#resolveDeclaredSchemaRequired) so SchemaRoutes.heldSlices
        // can share it with the gate instead of two paths that could diverge. The DEBUG/WARN
        // logging stays here, on Active's own logger, unchanged from before the hoist — it fires
        // once per blueprint-change/restore event, not once per slice/schema pair.
        private boolean resolveSchemaRequired(BlueprintId blueprintId) {
            return ClusterDeploymentState.resolveDeclaredSchemaRequired(ctx.kvStore(),
                                                                        blueprintId)
                                         .onPresent(value -> log.debug("schemaRequired resolved to {} for {} from declared deploymentConfig",
                                                                       value,
                                                                       blueprintId))
                                         .onEmpty(() -> log.warn("schemaRequired unresolved for {}, defaulting to true (missing blueprint entry, resourcesConfig, or unparsable/incomplete resources.toml) — every slice of this blueprint will hold in LOADED until a schema migration record for its datasource reaches COMPLETED",
                                                                 blueprintId))
                                         .or(true);
        }

        @Contract
        private void collectStreamMetadataCommands(BlueprintId blueprintId, List<KVCommand<AetherKey>> commands) {
            log.trace("Stream metadata collection hook for blueprint '{}'", blueprintId.asString());
        }

        @SuppressWarnings("unused")
        private KVCommand<AetherKey> buildStreamMetadataCommand(String streamName, BlueprintId blueprintId) {
            var key = StreamMetadataKey.streamMetadataKey(streamName);
            var value = StreamMetadataValue.streamMetadataValue(streamName,
                                                                4,
                                                                "count",
                                                                "100000",
                                                                "65536",
                                                                "block",
                                                                blueprintId.asString());

            return new KVCommand.Put<>(key, value);
        }

        private Option<ExpandedBlueprint> capturePreviousBlueprint(ExpandedBlueprint expanded) {
            if (ctx.atomicity() != DeploymentAtomicity.ALL_OR_NOTHING || restoringBlueprints.contains(expanded.id())) {
                return Option.empty();
            }

            return Option.option(inFlightBlueprints.get(expanded.id()))
                         .map(InFlightBlueprint::expanded)
                         .orElse(() -> capturePriorActiveBlueprint(expanded));
        }

        private Option<ExpandedBlueprint> capturePriorActiveBlueprint(ExpandedBlueprint expanded) {
            return firstSliceBase(expanded).flatMap(sliceBase -> capturePriorActiveBlueprint(expanded, sliceBase));
        }

        private Option<ExpandedBlueprint> capturePriorActiveBlueprint(ExpandedBlueprint expanded,
                                                                      ArtifactBase sliceBase) {
            return ctx.kvStore()
                      .get(SliceTargetKey.sliceTargetKey(sliceBase))
                      .filter(v -> v instanceof SliceTargetValue)
                      .map(v -> ((SliceTargetValue) v).currentVersion())
                      .filter(priorVersion -> !priorVersion.equals(expanded.id().artifact().version()))
                      .flatMap(priorVersion -> lookupPreviousBlueprint(expanded, priorVersion));
        }

        private Option<ExpandedBlueprint> lookupPreviousBlueprint(ExpandedBlueprint expanded, Version priorVersion) {
            var priorId = BlueprintId.blueprintId(expanded.id().base().withVersion(priorVersion));

            return ctx.kvStore()
                      .get(AppBlueprintKey.appBlueprintKey(priorId))
                      .filter(v -> v instanceof AppBlueprintValue)
                      .map(v -> ((AppBlueprintValue) v).blueprint());
        }

        private Option<ArtifactBase> firstSliceBase(ExpandedBlueprint expanded) {
            return Option.option(expanded.loadOrder().isEmpty()
                                 ? null
                                 : expanded.loadOrder().getFirst()).map(slice -> slice.artifact()
                                                                                      .base());
        }

        // #760/#724 review round 3 GAP: tracking is atomicity-agnostic — trackBlueprintSliceActive's
        // success detection (removing the blueprint from this map once every slice reaches ACTIVE,
        // then recordSucceededOutcome) works identically for BEST_EFFORT. Gating this on
        // ALL_OR_NOTHING left a fully successful BEST_EFFORT deployment with no outcome record at
        // all. The OTHER ALL_OR_NOTHING-specific consumers of this map (capturePreviousBlueprint,
        // the rollbackBlueprintForArtifact call site) are independently gated by their own
        // ctx.atomicity() == ALL_OR_NOTHING check, so widening this guard cannot make them observe a
        // BEST_EFFORT entry.
        private void trackInFlightBlueprint(ExpandedBlueprint expanded, Option<ExpandedBlueprint> previousExpanded) {
            if (!restoringBlueprints.contains(expanded.id())) {
                inFlightBlueprints.put(expanded.id(),
                                       InFlightBlueprint.inFlightBlueprint(expanded.id(), expanded, previousExpanded));
            }
        }

        private void buildDependencyMap(ExpandedBlueprint expanded) {
            for (var slice : expanded.loadOrder()) {
                var artifact = slice.artifact();
                var dependencies = slice.dependencies();

                sliceDependencies.put(artifact, dependencies);
                log.trace("buildDependencyMap: Slice {} has {} dependencies: {}",
                          artifact,
                          dependencies.size(),
                          dependencies);
            }
        }

        private void handleRoutingRemoval(VersionRoutingKey routingKey) {
            var artifactBase = routingKey.artifactBase();

            activeRoutings.remove(artifactBase);
            log.info("Rolling update completed for {}, cleaning up old versions", artifactBase);
            var targetKey = SliceTargetKey.sliceTargetKey(artifactBase);

            ctx.kvStore()
               .get(targetKey)
               .filter(v -> v instanceof SliceTargetValue)
               .map(v -> (SliceTargetValue) v)
               .onPresent(targetValue -> removeNonTargetVersions(artifactBase,
                                                                 targetValue.currentVersion()));
        }

        private void removeNonTargetVersions(ArtifactBase artifactBase, Version currentVersion) {
            var oldVersions = blueprints.keySet()
                                        .stream()
                                        .filter(a -> artifactBase.matches(a) && !a.version()
                                                                                  .equals(currentVersion))
                                        .toList();

            for (var oldArtifact : oldVersions) {
                log.info("Removing old version {} after rolling update completion", oldArtifact);
                blueprints.remove(oldArtifact);
                issueDeallocationCommands(oldArtifact);
            }
        }

        private void trackSliceState(SliceNodeKey sliceKey, SliceNodeValue sliceNodeValue) {
            var state = sliceNodeValue.state();
            var previousState = sliceStates.put(sliceKey, state);

            updateTransitionalTimestamp(sliceKey, state, sliceNodeValue.transitionedAt());
            log.trace("Slice {} on {} state: {} -> {}",
                      sliceKey.artifact(),
                      sliceKey.nodeId(),
                      previousState,
                      state);
            if (state == SliceState.LOADED) {
                tryActivateIfDependenciesReady(sliceKey);
            }

            if (state == SliceState.ACTIVE) {
                handleSliceActive(sliceKey);
            }

            if (state == SliceState.FAILED) {
                handleSliceFailure(sliceKey, sliceNodeValue);
            }
        }

        private void handleSliceActive(SliceNodeKey sliceKey) {
            retryCounters.remove(sliceKey.asString());
            activateDependentSlices(sliceKey.artifact());
            trackBlueprintSliceActive(sliceKey.artifact());
        }

        private void handleSliceFailure(SliceNodeKey sliceKey, SliceNodeValue sliceNodeValue) {
            var failureReason = sliceNodeValue.failureReason().or("Unknown failure");

            sliceStates.remove(sliceKey);
            transitionalStateTimestamps.remove(sliceKey);
            reportedSchemaHolds.remove(sliceKey);
            issueUnloadCommand(sliceKey);
            if (sliceNodeValue.fatal()) {
                handleDeterministicFailure(sliceKey, failureReason);
            } else {
                handleTransientFailure(sliceKey, failureReason);
            }
        }

        private void handleDeterministicFailure(SliceNodeKey sliceKey, String failureReason) {
            var artifact = sliceKey.artifact();

            if (permanentlyFailed.contains(artifact)) {
                return;
            }

            permanentlyFailed.add(artifact);
            log.error("Deterministic failure for {} on {}: {} — will NOT retry",
                      artifact,
                      sliceKey.nodeId(),
                      failureReason);
            ctx.router()
               .route(DeploymentFailed.deploymentFailed(artifact,
                                                        sliceKey.nodeId(),
                                                        SliceState.FAILED,
                                                        failureReason,
                                                        ctx.nowMs()));
            if (ctx.atomicity() == DeploymentAtomicity.ALL_OR_NOTHING) {
                rollbackBlueprintForArtifact(artifact, failureReason);
            } else {
                recordBestEffortFailureOutcome(artifact, failureReason);
            }
        }

        /// #760/#724 review round 3 GAP fix (151b11d94): BEST_EFFORT deployments now populate
        /// `inFlightBlueprints` too (`trackInFlightBlueprint` tracks both atomicities), so this
        /// path is no longer the only terminal a BEST_EFFORT artifact can reach. A slice that
        /// reaches ACTIVE is retired via `trackBlueprintSliceActive`, whose own terminal is
        /// `recordSucceededOutcome` once every slice of the owning blueprint is active. This
        /// method is the terminal for the other branch: `handleDeterministicFailure` marks the
        /// artifact `permanentlyFailed` and calls here instead of retrying it. A slice with no
        /// owning blueprint (`Blueprint::owner` empty — a standalone deploy, not part of any
        /// blueprint) has no `DeploymentOutcomeKey` to write against and is correctly a no-op
        /// here. Merges into any existing FAILED record for the same blueprint (read-then-Put,
        /// not a blind overwrite) so a second independently-failing slice in one partial
        /// deployment is added to `failingSlices` instead of erasing the first.
        private void recordBestEffortFailureOutcome(Artifact artifact, String failureReason) {
            Option.option(blueprints.get(artifact))
                  .flatMap(Blueprint::owner)
                  .onPresent(blueprintId -> submitBatch(List.of(bestEffortFailureCommand(blueprintId,
                                                                                         artifact,
                                                                                         failureReason))));
        }

        private KVCommand<AetherKey> bestEffortFailureCommand(BlueprintId blueprintId,
                                                              Artifact artifact,
                                                              String failureReason) {
            var key = DeploymentOutcomeKey.deploymentOutcomeKey(blueprintId);
            var existingSlices = ctx.kvStore()
                                    .get(key)
                                    .filter(v -> v instanceof DeploymentOutcomeValue)
                                    .map(v -> ((DeploymentOutcomeValue) v).failingSlices())
                                    .or(List.of());
            var slices = new ArrayList<>(existingSlices);

            if (!slices.contains(artifact.asString())) {
                slices.add(artifact.asString());
            }

            var value = DeploymentOutcomeValue.failed(slices, failureReason, ctx.nowMs());

            return new KVCommand.Put<>(key, value);
        }

        private void handleTransientFailure(SliceNodeKey sliceKey, String failureReason) {
            var retryCount = retryCounters.merge(sliceKey.asString(), 1, Integer::sum);

            if (retryCount > MAX_RETRIES) {
                logMaxRetriesExceeded(sliceKey, failureReason);

                return;
            }

            var delaySeconds = Math.min(1L << (retryCount - 1), MAX_RETRY_DELAY_SECONDS);
            var jitteredMs = JitterUtil.applyJitter(delaySeconds * 1000L,
                                                    JitterUtil.MIN_FACTOR_DEFAULT,
                                                    JitterUtil.MAX_FACTOR_DEFAULT);

            log.warn("Transient failure for {} on {} (attempt {}/{}): {} — retrying in {}ms (base {}s)",
                     sliceKey.artifact(),
                     sliceKey.nodeId(),
                     retryCount,
                     MAX_RETRIES,
                     failureReason,
                     jitteredMs,
                     delaySeconds);
            SharedScheduler.schedule(this::reconcile, timeSpan(jitteredMs).millis());
        }

        private void logMaxRetriesExceeded(SliceNodeKey sliceKey, String failureReason) {
            log.error("Max retries ({}) exceeded for {} on {}: {} — giving up",
                      MAX_RETRIES,
                      sliceKey.artifact(),
                      sliceKey.nodeId(),
                      failureReason);
            retryCounters.remove(sliceKey.asString());
            ctx.router()
               .route(DeploymentFailed.deploymentFailed(sliceKey.artifact(),
                                                        sliceKey.nodeId(),
                                                        SliceState.FAILED,
                                                        failureReason,
                                                        ctx.nowMs()));
        }

        /// The activation gate for a slice's schema migrations, scoped to the slice's OWN blueprint.
        /// Datasource names are cluster-global (`BlueprintArtifactParser` derives `"database"` from
        /// the default script layout for every blueprint), so an unrelated blueprint's record must
        /// never hold this slice — that unscoped scan was the other half of #542.
        ///
        /// Blocking statuses are PENDING, MIGRATING and FAILED. FAILED blocks: a permanently failed
        /// migration leaves the physical schema at a version the slice was not built against, which
        /// is exactly the corruption this gate exists to prevent. Note the pre-#542 gate was
        /// inverted — `scheduleRetry` writes PENDING (blocked) while `emitPermanentFailure` writes
        /// FAILED (released), so the slice was held during recoverable retries and let through on
        /// permanent failure. The hold now clears only via `/api/schema/{ds}/retry`
        /// (FAILED -> PENDING -> COMPLETED) or a redeploy that republishes the record.
        ///
        /// A slice whose owning blueprint cannot be resolved — no `Blueprint` entry, or an entry
        /// carrying no owner — is reported READY. No record can be attributed to it, so blocking
        /// would be an unclearable hold: nothing that ever completes could match it, and the slice
        /// would sit in LOADED forever. Records only ever exist because some blueprint declared
        /// migrations, and that blueprint's own slices do carry its owner, so the safety property is
        /// held from the owning side rather than by refusing to decide here.
        boolean areSchemasReady(SliceNodeKey sliceKey) {
            return blockingSchemaRecords(sliceKey).isEmpty();
        }

        /// Named records rather than a boolean so the hold can be reported with detail (#760) — the
        /// prior `noBlockingSchemaRecords` collapsed the same scan into a single flag, which is all
        /// [#areSchemasReady(SliceNodeKey)] needs but nothing an operator-facing log could name.
        private List<SchemaVersionValue> blockingSchemaRecords(SliceNodeKey sliceKey) {
            return Option.option(blueprints.get(sliceKey.artifact()))
                         .filter(Blueprint::schemaRequired)
                         .flatMap(Blueprint::owner)
                         .map(this::collectBlockingSchemaRecords)
                         .or(List.of());
        }

        private List<SchemaVersionValue> collectBlockingSchemaRecords(BlueprintId owner) {
            var blocking = new ArrayList<SchemaVersionValue>();
            var kvStore = ctx.kvStore();

            kvStore.forEach(SchemaVersionKey.class,
                            SchemaVersionValue.class,
                            (_, value) -> collectIfBlocking(owner, value, kvStore, blocking));

            return blocking;
        }

        /// Ownership matches on `ArtifactBase` (version stripped), so a blueprint that advanced from
        /// `my-app:1.0.0` to `my-app:1.0.1` still owns the records its earlier version wrote — the
        /// same rule `hasConflictingOwnership` and `BlueprintService`'s deploy-time gate apply.
        ///
        /// #760 review BLOCKING 1: delegates to the shared [ClusterDeploymentState#blocksSliceActivation]
        /// predicate instead of re-checking status/ownership inline, so this gate and
        /// `SchemaRoutes.heldSlices` cannot drift apart. `SliceState.LOADED` is passed explicitly —
        /// this method is reachable only from [#tryActivateIfDependenciesReady(SliceNodeKey)], itself
        /// only invoked when a slice's state just transitioned to LOADED, so the slice is always
        /// LOADED at this call site even though nothing here re-reads its live state.
        private static void collectIfBlocking(BlueprintId owner,
                                              SchemaVersionValue value,
                                              KVStore<AetherKey, AetherValue> kvStore,
                                              List<SchemaVersionValue> blocking) {
            if (ClusterDeploymentState.blocksSliceActivation(SliceState.LOADED, Option.some(owner), value, kvStore)) {
                blocking.add(value);
            }
        }

        private void tryActivateIfDependenciesReady(SliceNodeKey sliceKey) {
            var artifact = sliceKey.artifact();
            var blockingRecords = blockingSchemaRecords(sliceKey);

            if (!blockingRecords.isEmpty()) {
                reportSchemaHold(sliceKey, artifact, blockingRecords);

                return;
            }

            reportSchemaHoldCleared(sliceKey, artifact);
            var dependencies = sliceDependencies.getOrDefault(artifact, Set.of());

            if (dependencies.isEmpty()) {
                log.debug("Slice {} has no dependencies, activating immediately", artifact);
                issueActivateCommand(sliceKey);

                return;
            }

            if (allDependenciesActive(dependencies)) {
                log.debug("All {} dependencies of {} are ACTIVE, activating", dependencies.size(), artifact);
                issueActivateCommand(sliceKey);
            } else {
                log.debug("Slice {} waiting for dependencies to become ACTIVE: {}",
                          artifact,
                          dependencies.stream().filter(dep -> !isDependencyActive(dep)).toList());
            }
        }

        /// #760 follow-up: [#tryActivateIfDependenciesReady(SliceNodeKey)] is event-driven — fired
        /// on the slice's own LOAD, on ANY schema record reaching `COMPLETED`, on a sibling
        /// dependency activating, and once per blueprint at leader rebuild — never on a fixed
        /// timer ([#reconcile()] never calls it). A slice held on one datasource still gets
        /// re-evaluated every time an unrelated datasource completes or a dependency activates
        /// elsewhere, so a long hold can accumulate many re-observations with nothing about THIS
        /// slice's hold having changed. WARN once per distinct hold signature (first observation,
        /// or the set of blocking datasources/statuses actually changing) and DEBUG on a repeat
        /// observation of the same signature, so the operator-visible signal tracks state
        /// transitions rather than event-loop noise.
        ///
        /// #760/#724 review round 2 item l: `reportedSchemaHolds` is keyed per [SliceNodeKey] —
        /// one entry per slice INSTANCE on one node, not per artifact or per datasource — so two
        /// instances of the same slice held on the same datasource dedup independently and each
        /// gets its own first WARN. It is plain in-memory `Active` FSM state, never written to the
        /// KV store, so it does not survive a leader failover: the new leader rebuilds `Active`
        /// with an empty map and will WARN once more for every hold still standing at that point,
        /// even one that has been open and unchanged for a long time. This is accepted, not a
        /// defect — the dedup's job is to suppress noise from an event-driven loop re-evaluating
        /// an unchanged hold on the SAME leader, not to give the hold itself a durable identity
        /// across the cluster's whole lifetime. An operator correlating hold WARNs across a
        /// failover should expect exactly one extra WARN per still-open hold, not a signal that
        /// the hold is new.
        private void reportSchemaHold(SliceNodeKey sliceKey,
                                      Artifact artifact,
                                      List<SchemaVersionValue> blockingRecords) {
            var signature = schemaHoldSignature(blockingRecords);
            var previous = reportedSchemaHolds.put(sliceKey, signature);

            if (signature.equals(previous)) {
                log.debug("Slice {} still held in LOADED, waiting for schema migrations to complete: {}",
                          artifact,
                          signature);
            } else {
                log.warn("Slice {} held in LOADED, waiting for schema migrations to complete: {}", artifact, signature);
            }
        }

        // #760 review TEST GAP 3: `blockingRecords` is built by `forEach` over a
        // `ConcurrentHashMap` (blockingSchemaRecords -> collectIfBlocking), so its iteration order
        // is unspecified and can differ between two evaluations that observe the exact same set of
        // blocking datasources. Sorted by datasourceName() so the signature is a stable function of
        // the blocking SET, not of map iteration order — otherwise a slice blocked on two
        // datasources could see its signature flip between equivalent evaluations and fire a
        // spurious second WARN for a hold that never actually changed. Extracted to a standalone,
        // package-visible method (#760/#724 review round 2 item d) so the sort-neutralizes-order
        // property is pinned directly, independent of whatever iteration order the KV store
        // actually produces in a given test run.
        static String schemaHoldSignature(List<SchemaVersionValue> blockingRecords) {
            return blockingRecords.stream()
                                  .sorted(Comparator.comparing(SchemaVersionValue::datasourceName))
                                  .map(v -> v.datasourceName() + "=" + v.status())
                                  .collect(Collectors.joining(", "));
        }

        /// Companion to [#reportSchemaHold(SliceNodeKey, Artifact, List)]: fires the single WARN
        /// that closes the hold, and only when this slice actually had one on record — an
        /// already-clear slice re-evaluated by an unrelated event must stay silent.
        private void reportSchemaHoldCleared(SliceNodeKey sliceKey, Artifact artifact) {
            if (reportedSchemaHolds.remove(sliceKey) != null) {
                log.warn("Slice {} schema hold cleared, resuming activation", artifact);
            }
        }

        private boolean allDependenciesActive(Set<Artifact> dependencies) {
            return dependencies.stream()
                               .allMatch(this::isDependencyActive);
        }

        private boolean isDependencyActive(Artifact dependency) {
            return sliceStates.entrySet()
                              .stream()
                              .anyMatch(entry -> entry.getKey()
                                                      .artifact()
                                                      .equals(dependency) && entry.getValue() == SliceState.ACTIVE);
        }

        private void activateDependentSlices(Artifact activatedArtifact) {
            sliceStates.entrySet()
                       .stream()
                       .filter(entry -> entry.getValue() == SliceState.LOADED)
                       .map(Map.Entry::getKey)
                       .filter(key -> dependsOn(key.artifact(),
                                                activatedArtifact))
                       .forEach(this::tryActivateIfDependenciesReady);
        }

        private boolean dependsOn(Artifact dependent, Artifact dependency) {
            return sliceDependencies.getOrDefault(dependent,
                                                  Set.of())
                                    .contains(dependency);
        }

        private void issueActivateCommand(SliceNodeKey sliceKey) {
            log.debug("Issuing ACTIVATE command for {}", sliceKey);
            applyStateWrite(sliceKey, SliceState.ACTIVATE).onFailure(cause -> log.error("Failed to issue ACTIVATE command for {}: {}",
                                                                                        sliceKey,
                                                                                        cause.message()));
        }

        // Fire-and-forget LOAD: callers (allocation/reconcile sweeps, forEach state-mutators) ignore
        // the outcome; failure is handled inline via handleSliceNodeWriteFailure. void is the contract.
        @Contract
        void issueLoadCommand(SliceNodeKey sliceKey) {
            log.debug("Issuing LOAD command for {}", sliceKey);
            var timestamp = ctx.nowMs();

            applyStateWrite(sliceKey, SliceState.LOAD).withSuccess(_ -> ctx.router()
                                                                           .route(DeploymentStarted.deploymentStarted(sliceKey.artifact(),
                                                                                                                      sliceKey.nodeId(),
                                                                                                                      timestamp)))
                           .onFailure(cause -> handleSliceNodeWriteFailure(sliceKey, cause));
        }

        // Fire-and-forget UNLOAD: callers (forEach(active::issueUnloadCommand), reconcile/eviction
        // sweeps) ignore the outcome; failure is logged inline. void is the contract.
        @Contract
        void issueUnloadCommand(SliceNodeKey sliceKey) {
            log.debug("Issuing UNLOAD command for {}", sliceKey);
            applyStateWrite(sliceKey, SliceState.UNLOAD).onFailure(cause -> log.error("Failed to issue UNLOAD command for {}: {}",
                                                                                      sliceKey,
                                                                                      cause.message()));
        }

        private Promise<Unit> applyStateWrite(SliceNodeKey sliceKey, SliceState state) {
            var transitionedAt = state.isTransitional()
                                 ? ctx.nowMs()
                                 : 0L;
            KVCommand<AetherKey> putArtifact = new KVCommand.Put<>(NodeArtifactKey.nodeArtifactKey(sliceKey.nodeId(),
                                                                                                   sliceKey.artifact()),
                                                                   NodeArtifactValue.nodeArtifactValue(state,
                                                                                                       transitionedAt));

            return ctx.cluster()
                      .apply(List.of(putArtifact))
                      .mapToUnit();
        }

        // Fire-and-forget KV removal: callers (forEach(this::removeNodeArtifactKey), cleanup sweeps)
        // ignore the outcome; failure is logged inline. void is the contract.
        @Contract
        void removeNodeArtifactKey(SliceNodeKey sliceKey) {
            KVCommand<AetherKey> removeArtifact = new KVCommand.Remove<>(NodeArtifactKey.nodeArtifactKey(sliceKey.nodeId(),
                                                                                                         sliceKey.artifact()));

            ctx.cluster()
               .apply(List.of(removeArtifact))
               .onFailure(cause -> log.error("Failed to remove node-artifact-key for {}: {}",
                                             sliceKey,
                                             cause.message()));
        }

        private void submitBatch(List<KVCommand<AetherKey>> commands) {
            if (commands.isEmpty()) {
                return;
            }

            ctx.cluster().apply(commands).onFailure(cause -> handleBatchFailure(cause, commands));
        }

        private void handleBatchFailure(Cause cause, List<KVCommand<AetherKey>> commands) {
            if (deactivated.get()) {
                log.debug("Suppressing batch failure handling - Active state deactivated");

                return;
            }

            log.error("Batch consensus write failed ({} commands): {}", commands.size(), cause.message());
            SharedScheduler.schedule(this::reconcile, timeSpan(5).seconds());
        }

        private void handleSliceNodeWriteFailure(SliceNodeKey sliceKey, Cause cause) {
            log.error("DHT write failed for {}: {}", sliceKey, cause.message());
            sliceStates.remove(sliceKey);
            SharedScheduler.schedule(this::reconcile, timeSpan(5).seconds());
        }

        private Promise<Unit> handleNodeRemoval(NodeId removedNode) {
            rebuildSliceStateFromKVStoreEntries();
            var sliceKeysToRemove = sliceStates.keySet()
                                               .stream()
                                               .filter(key -> key.nodeId()
                                                                 .equals(removedNode))
                                               .toList();

            sliceKeysToRemove.forEach(sliceStates::remove);
            sliceKeysToRemove.forEach(transitionalStateTimestamps::remove);
            sliceKeysToRemove.forEach(this::removeNodeArtifactKey);
            var artifactKeysToRemove = findNodeArtifactKeysForNode(removedNode);
            var nodeRouteCommands = cleanupNodeRoutesForNode(removedNode);
            List<KVCommand<AetherKey>> consensusCommands = new ArrayList<>();

            artifactKeysToRemove.stream()
                                .<KVCommand<AetherKey>> map(KVCommand.Remove::new)
                                .forEach(consensusCommands::add);
            consensusCommands.addAll(nodeRouteCommands);
            workerNodes.remove(removedNode);
            log.info("Removed {} slice states, {} node-artifact entries, and {} node-routes updates for departed node {}",
                     sliceKeysToRemove.size(),
                     artifactKeysToRemove.size(),
                     nodeRouteCommands.size(),
                     removedNode);
            if (!consensusCommands.isEmpty()) {
                return ctx.cluster()
                          .apply(consensusCommands)
                          .mapToUnit()
                          .onFailure(cause -> log.error("Failed to remove keys for departed node {}: {}",
                                                        removedNode,
                                                        cause.message()));
            }

            return Promise.unitPromise();
        }

        private List<NodeArtifactKey> findNodeArtifactKeysForNode(NodeId nodeId) {
            var result = new ArrayList<NodeArtifactKey>();

            ctx.kvStore()
               .forEach(NodeArtifactKey.class,
                        NodeArtifactValue.class,
                        (key, _) -> collectNodeArtifactKeyForNode(result, key, nodeId));

            return result;
        }

        private void collectNodeArtifactKeyForNode(List<NodeArtifactKey> result, NodeArtifactKey key, NodeId nodeId) {
            if (key.nodeId().equals(nodeId)) {
                result.add(key);
            }
        }

        private List<KVCommand<AetherKey>> cleanupNodeRoutesForNode(NodeId removedNode) {
            var commands = new ArrayList<KVCommand<AetherKey>>();

            ctx.kvStore()
               .forEach(NodeRoutesKey.class,
                        AetherValue.NodeRoutesValue.class,
                        (key, _) -> collectNodeRoutesKeyForNode(commands, key, removedNode));

            return commands;
        }

        private void collectNodeRoutesKeyForNode(List<KVCommand<AetherKey>> commands,
                                                 NodeRoutesKey key,
                                                 NodeId removedNode) {
            if (key.nodeId().equals(removedNode)) {
                commands.add(new KVCommand.Remove<>(key));
            }
        }

        @Contract
        public void cleanupStaleNodeRoutes() {
            staleEntryCleaner().cleanupStaleNodeRoutes();
        }

        @Contract
        public void cleanupStaleSliceEntries() {
            staleEntryCleaner().cleanupStaleSliceEntries();
        }

        @Contract
        public void cleanupStaleNodeArtifactEntries() {
            staleEntryCleaner().cleanupStaleNodeArtifactEntries();
        }

        private void cleanupOrphanedSliceEntries() {
            staleEntryCleaner().cleanupOrphanedSliceEntries();
        }

        private void issueAllocationCommands(Artifact artifact, int desiredInstances) {
            allocationEngine().issueAllocationCommands(artifact, desiredInstances);
        }

        private int issueAllocationsForNodes(Artifact artifact, int toAdd, Set<NodeId> targetNodes) {
            return allocationEngine().issueAllocationsForNodes(artifact, toAdd, targetNodes);
        }

        private boolean tryAllocate(Artifact artifact, NodeId node) {
            return allocationEngine().tryAllocate(artifact, node);
        }

        List<SliceNodeKey> getCurrentInstances(Artifact artifact) {
            var currentNodes = activeNodes();

            return sliceStates.entrySet()
                              .stream()
                              .filter(entry -> entry.getKey()
                                                    .artifact()
                                                    .equals(artifact))
                              .filter(entry -> currentNodes.contains(entry.getKey().nodeId()))
                              .filter(entry -> isLiveState(entry.getValue()))
                              .map(Map.Entry::getKey)
                              .toList();
        }

        private boolean isLiveState(SliceState state) {
            return state != SliceState.FAILED
                   && state != SliceState.UNLOAD
                   && state != SliceState.UNLOADING;
        }

        private void updateTransitionalTimestamp(SliceNodeKey sliceKey,
                                                 SliceState state,
                                                 long persistedTransitionedAt) {
            if (state.isTransitional()) {
                var effectiveTimestamp = persistedTransitionedAt > 0L
                                         ? persistedTransitionedAt
                                         : ctx.nowMs();

                transitionalStateTimestamps.putIfAbsent(sliceKey, effectiveTimestamp);
            } else {
                transitionalStateTimestamps.remove(sliceKey);
            }
        }

        private void detectStuckTransitionalStates() {
            stuckRemediator().detectStuckTransitionalStates();
        }

        private void issueDeallocationCommands(Artifact artifact) {
            getCurrentInstances(artifact).forEach(this::issueUnloadCommand);
            removeWorkerDirective(artifact);
        }

        private void issueAllocationCommandsWithPlacement(Artifact artifact, int desiredInstances, String placement) {
            allocationEngine().issueAllocationCommandsWithPlacement(artifact, desiredInstances, placement);
        }

        private void removeWorkerDirective(Artifact artifact) {
            var commands = new ArrayList<KVCommand<AetherKey>>();

            commands.add(new KVCommand.Remove<>(WorkerSliceDirectiveKey.workerSliceDirectiveKey(artifact)));
            for (var communityId : communityPlanner().activeCommunityIds()) {
                commands.add(new KVCommand.Remove<>(WorkerSliceDirectiveKey.workerSliceDirectiveKey(artifact,
                                                                                                    communityId)));
            }

            ctx.cluster()
               .apply(commands)
               .onFailure(cause -> log.debug("No worker directive to remove for {}: {}",
                                             artifact,
                                             cause.message()));
        }

        @SuppressWarnings("unused")
        private String lookupPlacement(Artifact artifact) {
            return ctx.kvStore()
                      .get(SliceTargetKey.sliceTargetKey(artifact.base()))
                      .filter(v -> v instanceof SliceTargetValue)
                      .map(v -> ((SliceTargetValue) v).effectivePlacement())
                      .or("CORE_ONLY");
        }

        @Contract
        public void reconcile() {
            if (deactivated.get()) {
                log.debug("Suppressing reconciliation - Active state deactivated");

                return;
            }

            log.debug("Performing cluster reconciliation with {} blueprints and {} active nodes",
                      blueprints.size(),
                      activeNodes().size());
            var reconciled = 0;
            var rebalanceBudget = new java.util.concurrent.atomic.AtomicInteger(1);
            var blueprintSnapshot = List.copyOf(blueprints.values());

            for (var blueprint : blueprintSnapshot) {
                if (reconcileBlueprint(blueprint, rebalanceBudget)) {
                    reconciled++;
                }
            }

            log.debug("Reconciliation complete: {} of {} blueprints required adjustment", reconciled, blueprints.size());
            evaluateCommunityStates();
            cleanupOrphanedSliceEntries();
            cleanupStaleNodeRoutes();
            cleanupStaleNodeArtifactEntries();
            cleanupStaleSliceEntries();
            detectStuckTransitionalStates();
            // A schema sweep does NOT belong here. It was added and reverted on 2026-08-31: sweeping
            // PENDING records on every reconcile re-dispatches a migration that is already running —
            // reconcile() is driven from many call sites, so three dispatches landed within two
            // seconds, and `SchemaOrchestratorService.acquireLock` is check-then-act (`isLockHeld`
            // then `cluster.apply(Put)`), not atomic across nodes. The second runner reached
            // `aether_schema_history` and died on `23505 duplicate key`, marking the whole datasource
            // FAILED and holding every slice in the blueprint — the exact outage the sweep was meant
            // to prevent, caused by the sweep.
            //
            // The hole it was closing is real: a PENDING Put lost AFTER activation is unreachable by
            // the rebuild-time sweep. Closing it needs a stalled-record test (record age, or an
            // atomic compare-and-set on the lock key) so recovery fires only for a migration nobody
            // is working on. Rebuild-time recovery in `rebuildStateFromKVStore` stays: it runs once,
            // cannot race a live migration, and is mutation-proven.
        }

        /// Per-community FSM evaluation (worker-membership-spec §3.3): the leader walks every committed
        /// community, recomputes its desired `state` from observed live membership, and commits ONE
        /// batch of `Put`s for exactly the communities whose state changed. Edge-driven — an unchanged
        /// state emits NO command. Reached only from `reconcile()`, which is leader-guarded by the
        /// `deactivated` check, so this evaluation runs on the leader alone.
        @Contract
        private void evaluateCommunityStates() {
            var batch = new ArrayList<KVCommand<AetherKey>>();

            ctx.kvStore()
               .forEach(CommunityKey.class,
                        CommunityValue.class,
                        (key, value) -> collectCommunityTransition(batch, key, value));
            if (!batch.isEmpty()) {
                ctx.cluster()
                   .apply(batch)
                   .onFailure(cause -> log.error("Failed to apply {} community state transition(s): {}",
                                                 batch.size(),
                                                 cause.message()));
            }
        }

        private void collectCommunityTransition(List<KVCommand<AetherKey>> batch,
                                                CommunityKey key,
                                                CommunityValue value) {
            var floor = ctx.communitySizing().viabilityFloor();
            var liveMembers = communityLiveMembers(key.communityId());
            var next = nextCommunityState(value.state(), liveMembers, floor);

            if (next != value.state()) {
                log.info("Community '{}' {} -> {} ({} live member(s), floor {})",
                         key.communityId(),
                         value.state(),
                         next,
                         liveMembers,
                         floor);
                batch.add(new KVCommand.Put<>(key, value.withState(next)));
            }
        }

        /// Observed live membership of a community (worker-membership-spec §3.3), corrected for
        /// core-observed absence (#590).
        ///
        /// The announcement's `memberCount` is the community's own SELF-REPORT, and under a
        /// core/community partition the governor cannot rewrite it — so it freezes at its last healthy
        /// value instead of expiring, and the community stayed `ACTIVE` forever while unreachable. The
        /// reported count is therefore reduced by the members the leader has positively observed to be
        /// absent (pong silence beyond `timeouts.cluster.community_absence`).
        ///
        /// Deliberately a SUBTRACTION from `memberCount` rather than a recount of `members()`: the two
        /// are independent fields and `governorAnnouncementValue(governorId, memberCount)` leaves
        /// `members` empty with a non-zero count, so recounting would read 0 live members for a
        /// perfectly healthy community. With nothing absent this returns exactly what it returned
        /// before.
        ///
        /// No announcement (no governor yet) still reads as `0`, which keeps a FORMING community below
        /// the floor and demotes an ACTIVE one to DEGRADED.
        private int communityLiveMembers(String communityId) {
            return ctx.kvStore()
                      .get(GovernorAnnouncementKey.forCommunity(communityId))
                      .filter(GovernorAnnouncementValue.class::isInstance)
                      .map(GovernorAnnouncementValue.class::cast)
                      .map(this::observedLiveMembers)
                      .or(0);
        }

        /// `memberCount` minus the positively-absent members. When the announcement carries no member
        /// list there is only one identity to check — the governor's own — which still detects the
        /// case this exists for: a whole community that has gone silent.
        private int observedLiveMembers(GovernorAnnouncementValue announcement) {
            var liveness = ctx.communityLiveness();

            if (announcement.members().isEmpty()) {
                return liveness.isAbsent(announcement.governorId())
                       ? 0
                       : announcement.memberCount();
            }

            var absent = (int) announcement.members().stream().filter(liveness::isAbsent).count();

            return Math.max(0, announcement.memberCount() - absent);
        }

        /// Pure per-community state edge (worker-membership-spec §3.3). FORMING/DEGRADED promote to
        /// ACTIVE once observed live membership reaches the viability `floor`; ACTIVE demotes to DEGRADED
        /// below it. The terminal teardown states (DISSOLVING/DISSOLVED) are leader-decision/scale-down
        /// concerns (Phase C) and are left unchanged here.
        private static CommunityState nextCommunityState(CommunityState current, int liveMembers, int floor) {
            return switch (current) {
                case FORMING -> liveMembers >= floor
                                ? CommunityState.ACTIVE
                                : CommunityState.FORMING;
                case ACTIVE -> liveMembers < floor
                               ? CommunityState.DEGRADED
                               : CommunityState.ACTIVE;
                case DEGRADED -> liveMembers >= floor
                                 ? CommunityState.ACTIVE
                                 : CommunityState.DEGRADED;
                case DISSOLVING, DISSOLVED -> current;
            };
        }

        private boolean reconcileBlueprint(Blueprint blueprint,
                                           java.util.concurrent.atomic.AtomicInteger rebalanceBudget) {
            var artifact = blueprint.artifact();

            if (permanentlyFailed.contains(artifact)) {
                return false;
            }

            if (hasInstancesOnDrainingNodes(artifact)) {
                return false;
            }

            var desiredInstances = blueprint.instances();
            var currentInstances = getCurrentInstances(artifact);

            if (currentInstances.size() == desiredInstances) {
                return rebalanceIfNeeded(artifact, currentInstances, rebalanceBudget);
            }

            consecutiveImbalancedTicks.remove(artifact);
            log.info("Reconciliation: {} has {} instances, desired {} - adjusting",
                     artifact,
                     currentInstances.size(),
                     desiredInstances);
            emitScalingEvent(artifact, currentInstances.size(), desiredInstances);
            issueAllocationCommands(artifact, desiredInstances);

            return true;
        }

        private static final int REBALANCE_HYSTERESIS_TICKS = 2;

        private boolean rebalanceIfNeeded(Artifact artifact,
                                          List<SliceNodeKey> currentInstances,
                                          java.util.concurrent.atomic.AtomicInteger rebalanceBudget) {
            if (currentInstances.isEmpty()) {
                consecutiveImbalancedTicks.remove(artifact);

                return false;
            }

            var allocatable = allocatableNodes();

            if (allocatable.size() < 2) {
                consecutiveImbalancedTicks.remove(artifact);

                return false;
            }

            var nodesHostingThisArtifact = currentInstances.stream()
                                                           .map(SliceNodeKey::nodeId)
                                                           .collect(Collectors.toUnmodifiableSet());
            var totalLoadByNode = sliceStates.entrySet()
                                             .stream()
                                             .filter(entry -> isLiveState(entry.getValue()))
                                             .map(Map.Entry::getKey)
                                             .collect(Collectors.groupingBy(SliceNodeKey::nodeId,
                                                                            Collectors.counting()));
            var maxLoad = allocatable.stream()
                                     .mapToLong(node -> totalLoadByNode.getOrDefault(node, 0L))
                                     .max()
                                     .orElse(0L);
            var minLoad = allocatable.stream()
                                     .mapToLong(node -> totalLoadByNode.getOrDefault(node, 0L))
                                     .min()
                                     .orElse(0L);

            if (maxLoad - minLoad <= 1) {
                consecutiveImbalancedTicks.remove(artifact);

                return false;
            }

            var donorOpt = nodesHostingThisArtifact.stream()
                                                   .max(Comparator.comparingLong(node -> totalLoadByNode.getOrDefault(node,
                                                                                                                      0L)));
            var targetOpt = allocatable.stream()
                                       .filter(node -> !nodesHostingThisArtifact.contains(node))
                                       .min(Comparator.comparingLong(node -> totalLoadByNode.getOrDefault(node, 0L)));

            if (donorOpt.isEmpty() || targetOpt.isEmpty()) {
                consecutiveImbalancedTicks.remove(artifact);

                return false;
            }

            var donorLoad = totalLoadByNode.getOrDefault(donorOpt.get(), 0L);
            var targetLoad = totalLoadByNode.getOrDefault(targetOpt.get(), 0L);

            if (donorLoad - targetLoad <= 1) {
                consecutiveImbalancedTicks.remove(artifact);

                return false;
            }

            var ticks = consecutiveImbalancedTicks.merge(artifact, 1, Integer::sum);

            if (ticks < REBALANCE_HYSTERESIS_TICKS) {
                log.debug("Rebalance hysteresis: {} imbalanced for {} tick(s) (need {})",
                          artifact,
                          ticks,
                          REBALANCE_HYSTERESIS_TICKS);

                return false;
            }

            if (rebalanceBudget.getAndDecrement() <= 0) {
                log.debug("Rebalance: {} eligible but per-tick budget exhausted; deferred to next tick", artifact);

                return false;
            }

            consecutiveImbalancedTicks.remove(artifact);

            return executeSingleRebalanceMove(artifact, donorOpt.get(), donorLoad, targetOpt.get(), targetLoad);
        }

        private boolean executeSingleRebalanceMove(Artifact artifact,
                                                   NodeId donor,
                                                   long donorLoad,
                                                   NodeId target,
                                                   long targetLoad) {
            var donorKey = SliceNodeKey.sliceNodeKey(artifact, donor);

            log.info("Rebalance: moving {} from {} (load={}) to {} (load={})",
                     artifact,
                     donor,
                     donorLoad,
                     target,
                     targetLoad);
            issueUnloadCommand(donorKey);
            tryAllocate(artifact, target);

            return true;
        }

        private boolean hasInstancesOnDrainingNodes(Artifact artifact) {
            var draining = drainingNodes();

            return sliceStates.keySet()
                              .stream()
                              .anyMatch(key -> key.artifact()
                                                  .equals(artifact) && draining.contains(key.nodeId()));
        }

        private void emitScalingEvent(Artifact artifact, int currentCount, int desiredCount) {
            if (currentCount < desiredCount) {
                AuditLog.reconciliationScaleUp(artifact.asString(), currentCount, desiredCount);
            } else {
                AuditLog.reconciliationScaleDown(artifact.asString(), currentCount, desiredCount);
            }

            ctx.router().route(ReconciliationAdjustment.reconciliationAdjustment(artifact, currentCount, desiredCount));
        }

        private void trackBlueprintSliceActive(Artifact artifact) {
            for (var entry : inFlightBlueprints.entrySet()) {
                var inflight = entry.getValue();

                if (inflight.pendingSlices().remove(artifact)) {
                    inflight.activeSlices().add(artifact);
                    if (inflight.pendingSlices().isEmpty()) {
                        log.info("Blueprint {} fully deployed — all {} slices active",
                                 entry.getKey().asString(),
                                 inflight.activeSlices().size());
                        inFlightBlueprints.remove(entry.getKey());
                        recordSucceededOutcome(entry.getKey());
                    }

                    break;
                }
            }
        }

        /// Durable SUCCEEDED counterpart to the FAILED outcome written in `unloadBlueprintSlices`
        /// (#759 review, BLOCKING 3) — the terminal outcome record must exist for both branches of
        /// the deployment attempt, not just the failure branch, so a caller can distinguish "no
        /// outcome yet" from "succeeded" once the blueprint leaves `inFlightBlueprints`.
        ///
        /// #760/#724 review round 2 item l: does NOT go through the shared [#submitBatch(List)] —
        /// see [#handleSucceededOutcomeWriteFailure(BlueprintId, Cause, List)] for why this call
        /// site's failure needs a targeted WARN instead of `submitBatch`'s generic ERROR.
        private void recordSucceededOutcome(BlueprintId blueprintId) {
            var key = DeploymentOutcomeKey.deploymentOutcomeKey(blueprintId);
            var value = DeploymentOutcomeValue.succeeded(ctx.nowMs());
            var command = List.<KVCommand<AetherKey>> of(new KVCommand.Put<>(key, value));

            ctx.cluster()
               .apply(command)
               .onFailure(cause -> handleSucceededOutcomeWriteFailure(blueprintId, cause, command));
        }

        /// Unlike every other write funneled through [#submitBatch(List)], this one cannot be
        /// recovered by `handleBatchFailure`'s `reconcile()` reschedule: by the time this call
        /// happens the blueprint has already been removed from `inFlightBlueprints` (one line above
        /// this call, in `trackBlueprintSliceActive`), and `reconcile()` only acts on in-flight and
        /// slice state — it never revisits deployment-outcome durability. `submitBatch`'s generic
        /// `log.error` would therefore describe a write that gets automatically retried, when in
        /// fact this one does not: the SUCCEEDED record for a blueprint that genuinely finished
        /// deploying is permanently lost, silently in effect even though not silently in logging.
        /// This WARN names the blueprint and says so explicitly, then still delegates to
        /// `handleBatchFailure` for its `deactivated`-guard and existing log volume — no retry path
        /// is added here; recording the loss for an operator to notice is the whole fix.
        private void handleSucceededOutcomeWriteFailure(BlueprintId blueprintId,
                                                        Cause cause,
                                                        List<KVCommand<AetherKey>> command) {
            if (!deactivated.get()) {
                log.warn("SUCCEEDED deployment-outcome record for blueprint {} was NOT persisted ({}) and will NOT be retried"
                        + " — the blueprint already left in-flight tracking before this write, so no reconciliation pass"
                        + " revisits it",
                         blueprintId.asString(),
                         cause.message());
            }

            handleBatchFailure(cause, command);
        }

        private void rollbackBlueprintForArtifact(Artifact failedArtifact, String cause) {
            for (var entry : inFlightBlueprints.entrySet()) {
                var blueprintId = entry.getKey();
                var inflight = entry.getValue();

                if (!inflight.pendingSlices().contains(failedArtifact) && !inflight.activeSlices()
                                                                                   .contains(failedArtifact)) {
                    continue;
                }

                if (activeRoutings.contains(failedArtifact.base())) {
                    log.info("Skipping blueprint rollback for {} — artifact {} is in active rolling update",
                             blueprintId.asString(),
                             failedArtifact);
                    continue;
                }

                log.warn("ALL_OR_NOTHING: Deterministic failure of {} triggers rollback of blueprint {}",
                         failedArtifact,
                         blueprintId.asString());
                inFlightBlueprints.remove(blueprintId);
                inflight.previousBlueprint()
                        .apply(() -> unloadBlueprintSlices(inflight, cause),
                               previous -> restorePreviousBlueprint(inflight, previous, cause));
                break;
            }
        }

        private void unloadBlueprintSlices(InFlightBlueprint inflight, String cause) {
            var allSlices = new HashSet<>(inflight.pendingSlices());

            allSlices.addAll(inflight.activeSlices());
            var consensusCommands = new ArrayList<KVCommand<AetherKey>>();

            for (var artifact : allSlices) {
                blueprints.remove(artifact);
                issueDeallocationCommands(artifact);
                consensusCommands.add(new KVCommand.Remove<>(SliceTargetKey.sliceTargetKey(artifact.base())));
            }

            var bpKey = AppBlueprintKey.appBlueprintKey(inflight.id());

            consensusCommands.add(new KVCommand.Remove<>(bpKey));
            consensusCommands.add(failedOutcomeCommand(inflight, allSlices, cause));
            log.info("ALL_OR_NOTHING: Unloading {} slices from failed blueprint {}",
                     allSlices.size(),
                     inflight.id().asString());
            submitBatch(consensusCommands);
        }

        /// FAILED outcome for the no-previous-blueprint rollback branch (#759 review, BLOCKING 3).
        /// Bundled into `unloadBlueprintSlices`'s own consensus batch — same commit as the
        /// `AppBlueprintKey` removal it survives — rather than a second `submitBatch` call, so the
        /// removal and the outcome record land atomically (both or neither).
        private KVCommand<AetherKey> failedOutcomeCommand(InFlightBlueprint inflight,
                                                          Set<Artifact> failingSlices,
                                                          String cause) {
            var key = DeploymentOutcomeKey.deploymentOutcomeKey(inflight.id());
            var slices = failingSlices.stream().map(Artifact::asString).toList();
            var value = DeploymentOutcomeValue.failed(slices, cause, ctx.nowMs());

            return new KVCommand.Put<>(key, value);
        }

        /// #809: bundles the failed blueprint's OWN `AppBlueprintKey` removal into this SAME batch,
        /// after `bpCommand` and before `outcomeCommand`. Ordering is load-bearing, not cosmetic —
        /// [KVStore#process] applies a batch's commands via a strictly sequential (non-parallel)
        /// stream, and [org.pragmatica.messaging.MessageRouter#route] plus [Fsm#dispatch] are both
        /// synchronous, so the resulting KV-watch notification for `bpCommand`'s Put is fully
        /// handled by `handleAppBlueprintChange` — re-owning every artifact in `previous`'s load
        /// order to `previous.id()` in the `blueprints` map — before this Remove's notification
        /// reaches `handleAppBlueprintRemoval`. That handler filters `blueprints` by CURRENT owner
        /// at the time it fires, so any artifact `previous` shares with the failed `inflight`
        /// blueprint is already re-owned and left untouched; only an artifact that existed solely
        /// under the failed `inflight` blueprint (net-new in the failed deploy, absent from
        /// `previous`) is still owned by `inflight.id()` and gets correctly deallocated as rollback
        /// cleanup. Reversing the order would let `handleAppBlueprintRemoval` fire while shared
        /// artifacts are still owned by `inflight.id()`, wrongly deallocating the very slices being
        /// restored. [mechanism: KVStore.process (sequential Stream.map, no parallel()) +
        /// MessageRouter.route (synchronous) + Fsm.dispatch (synchronous state.handle) + this list's
        /// order]
        ///
        /// The re-owning step this ordering depends on is itself conditional: `handleAppBlueprintChange`
        /// early-returns before touching `blueprints` when `hasConflictingOwnership` reports a
        /// conflict, compared on blueprint BASE — so a `previous` of a DIFFERENT base than `inflight`
        /// would not be re-owned, and the Remove below would deallocate the shared slices instead of
        /// leaving them untouched. `previous` and `inflight` share a base by construction (`previous`
        /// is `inflight`'s own captured prior version of the SAME blueprint), so this does not arise
        /// on the path that reaches this method today — noted because the safety this method leans on
        /// is conditional, not unconditional.
        private void restorePreviousBlueprint(InFlightBlueprint inflight, ExpandedBlueprint previous, String cause) {
            restoringBlueprints.add(previous.id());
            log.info("ALL_OR_NOTHING: Restoring previous blueprint {} with {} slices",
                     previous.id().asString(),
                     previous.loadOrder().size());
            var bpKey = AppBlueprintKey.appBlueprintKey(previous.id());
            var bpValue = AppBlueprintValue.appBlueprintValue(previous);
            var bpCommand = new KVCommand.Put<AetherKey, AetherValue>(bpKey, bpValue);
            var removeCommand = new KVCommand.Remove<AetherKey>(AppBlueprintKey.appBlueprintKey(inflight.id()));
            var outcomeCommand = rolledBackOutcomeCommand(inflight, cause);

            ctx.cluster()
               .apply(List.of(bpCommand, removeCommand, outcomeCommand))
               .onSuccess(_ -> SharedScheduler.schedule(() -> restoringBlueprints.remove(previous.id()),
                                                        timeSpan(5).seconds()))
               .onFailure(restoreFailure -> handleBlueprintRestoreFailure(previous.id(),
                                                                          restoreFailure));
        }

        /// ROLLED_BACK outcome for the previous-blueprint-exists rollback branch (#760/#724 review
        /// round 2 item g) — bundled into the SAME `ctx.cluster().apply` batch as the previous
        /// blueprint's own `AppBlueprintKey` Put, mirroring `failedOutcomeCommand`'s atomicity
        /// rationale, so a caller reading the outcome for the FAILING blueprint's id never observes
        /// the restore having landed without also observing the terminal record, or vice versa.
        /// Recorded against `inflight.id()` — the blueprint being replaced — never `previous.id()`,
        /// which names a separate, still-healthy blueprint that was never in a failure state.
        private KVCommand<AetherKey> rolledBackOutcomeCommand(InFlightBlueprint inflight, String cause) {
            var allSlices = new HashSet<>(inflight.pendingSlices());

            allSlices.addAll(inflight.activeSlices());
            var key = DeploymentOutcomeKey.deploymentOutcomeKey(inflight.id());
            var slices = allSlices.stream().map(Artifact::asString).toList();
            var value = DeploymentOutcomeValue.rolledBack(slices, cause, ctx.nowMs());

            return new KVCommand.Put<>(key, value);
        }

        private void handleBlueprintRestoreFailure(BlueprintId blueprintId, Cause cause) {
            log.error("ALL_OR_NOTHING: Failed to restore previous blueprint {}: {}",
                      blueprintId.asString(),
                      cause.message());
            restoringBlueprints.remove(blueprintId);
        }

        // --- package-private test seams (exercise the private transactional/isolation logic) ---
        boolean hasConflictingOwnershipForTest(ExpandedBlueprint expanded) {
            return hasConflictingOwnership(expanded);
        }

        Option<ExpandedBlueprint> capturePreviousBlueprintForTest(ExpandedBlueprint expanded) {
            return capturePreviousBlueprint(expanded);
        }

        @Contract
        void restorePreviousBlueprintForTest(InFlightBlueprint inflight, ExpandedBlueprint previous, String cause) {
            restorePreviousBlueprint(inflight, previous, cause);
        }

        public record InFlightBlueprint(BlueprintId id,
                                        ExpandedBlueprint expanded,
                                        Set<Artifact> pendingSlices,
                                        Set<Artifact> activeSlices,
                                        Option<ExpandedBlueprint> previousBlueprint) {
            public static InFlightBlueprint inFlightBlueprint(BlueprintId id,
                                                              ExpandedBlueprint expanded,
                                                              Option<ExpandedBlueprint> previousBlueprint) {
                Set<Artifact> pending = ConcurrentHashMap.newKeySet();

                expanded.loadOrder().forEach(slice -> pending.add(slice.artifact()));

                return new InFlightBlueprint(id, expanded, pending, ConcurrentHashMap.newKeySet(), previousBlueprint);
            }
        }
    }
}
