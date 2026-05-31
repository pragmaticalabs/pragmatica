// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.config.PlacementPolicy;
import org.pragmatica.aether.deployment.AuditLog;
import org.pragmatica.aether.deployment.cluster.AllocationPool;
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
import org.pragmatica.aether.metrics.deployment.DeploymentEvent.DeploymentFailed;
import org.pragmatica.aether.metrics.deployment.DeploymentEvent.DeploymentStarted;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.BlueprintParser;
import org.pragmatica.aether.slice.blueprint.DeploymentConfig;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.generation.CoreMember;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.HealthSignal;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
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
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaMigrationLockValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceNodeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamMetadataValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.VersionRoutingValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.WorkerSliceDirectiveValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


public sealed interface ClusterDeploymentState extends FsmState<ClusterDeploymentState, ClusterFsmEvent> permits ClusterDeploymentState.Dormant, ClusterDeploymentState.Active, ClusterDeploymentState.Stopped {
    Logger LOG = LoggerFactory.getLogger(ClusterDeploymentState.class);
    ClusterDeploymentContext ctx();

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
                  AtomicInteger allocationIndex,
                  AtomicBoolean deactivated,
                  CancellableTask reconcileTimer) implements ClusterDeploymentState {
        private static final Logger log = LoggerFactory.getLogger(Active.class);
        private static final int MAX_RETRIES = 5;
        private static final long MAX_RETRY_DELAY_SECONDS = 30;
        private static final int STUCK_TIMEOUT_MULTIPLIER = 3;

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
                case FAILED -> log.warn("Schema migration failed for datasource: {}", datasource);
                case MIGRATING -> log.debug("Schema migration in progress for datasource: {}", datasource);
            }
        }

        @Contract
        public void startReconcileTimer() {
            reconcileTimer.set(SharedScheduler.scheduleAtFixedRate(this::reconcileIfActive, ctx.reconcileInterval()));
        }

        private void reconcileIfActive() {
            if (!deactivated.get()) {reconcile();}
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
            if (deactivated.get()) {return;}

            cleanupStaleNodeRoutes();
            cleanupStaleSliceEntries();
            cleanupStaleNodeArtifactEntries();
            reconcile();
        }

        private void resumeDrainEvictions() {
            var draining = drainingNodes();

            if (draining.isEmpty()) {return;}

            log.info("Resuming drain evictions for {} nodes", draining.size());
            draining.forEach(this::evictNextSliceFromNode);
        }

        private void recoverStalledSchemaMigrations() {
            var stalledDatasources = new ArrayList<String>();
            ctx.kvStore().forEach(SchemaVersionKey.class,
                                  SchemaVersionValue.class,
                                  (_, value) -> collectStalledMigration(value, stalledDatasources));

            if (stalledDatasources.isEmpty()) {return;}

            log.info("Found {} stalled schema migrations, resetting to PENDING", stalledDatasources.size());
            stalledDatasources.forEach(this::resetStalledMigration);
        }

        private void collectStalledMigration(SchemaVersionValue value, List<String> stalledDatasources) {
            if (value.status() != SchemaStatus.MIGRATING) {return;}

            var lockKey = SchemaMigrationLockKey.schemaMigrationLockKey(value.datasourceName());
            var lockExpired = ctx.kvStore().get(lockKey).filter(SchemaMigrationLockValue.class::isInstance).map(SchemaMigrationLockValue.class::cast).map(SchemaMigrationLockValue::isExpired).or(true);

            if (lockExpired) {stalledDatasources.add(value.datasourceName());}
        }

        private void resetStalledMigration(String datasourceName) {
            log.info("Resetting stalled schema migration for '{}' to PENDING", datasourceName);
            var versionKey = SchemaVersionKey.schemaVersionKey(datasourceName);
            ctx.kvStore().get(versionKey).filter(SchemaVersionValue.class::isInstance).map(SchemaVersionValue.class::cast).onPresent(value -> submitStalledMigrationReset(datasourceName,
                                                                                                                                                                          versionKey,
                                                                                                                                                                          value));
        }

        private void submitStalledMigrationReset(String datasourceName,
                                                 SchemaVersionKey versionKey,
                                                 SchemaVersionValue value) {
            var updated = SchemaVersionValue.schemaVersionValue(datasourceName,
                                                                value.currentVersion(),
                                                                value.lastMigration(),
                                                                SchemaStatus.PENDING,
                                                                value.artifactCoords(),
                                                                value.attemptCount());
            var lockKey = SchemaMigrationLockKey.schemaMigrationLockKey(datasourceName);
            var commands = List.<KVCommand<AetherKey>> of(new KVCommand.Put<>(versionKey, updated),
                                                          new KVCommand.Remove<>(lockKey));
            ctx.cluster().apply(commands).onFailure(cause -> log.error("Failed to reset stalled migration for '{}': {}",
                                                                       datasourceName,
                                                                       cause.message()));
        }

        private void triggerLoadedSliceActivation() {
            var loadedSlices = sliceStates.entrySet().stream().filter(e -> e.getValue() == SliceState.LOADED).map(Map.Entry::getKey).toList();
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

            for (var slice : expanded.loadOrder()) {
                var artifact = slice.artifact();
                if (shouldSuppressActivation(artifact, registerOnly)) {
                    log.trace("Restore: register-only blueprint {} — skipping in-memory blueprints.put for slice {} (existing SliceTargetValue present)",
                              expanded.id().asString(),
                              artifact);
                    continue;
                }
                blueprints.put(artifact,
                               Blueprint.blueprint(artifact,
                                                   slice.instances(),
                                                   slice.minAvailable(),
                                                   Option.some(expanded.id())));
            }
        }

        private void restoreSliceTarget(SliceTargetKey sliceTargetKey, SliceTargetValue sliceTargetValue) {
            var artifact = sliceTargetKey.artifactBase().withVersion(sliceTargetValue.currentVersion());
            var instances = sliceTargetValue.targetInstances();
            var minInstances = sliceTargetValue.effectiveMinInstances();
            blueprints.put(artifact,
                           Blueprint.blueprint(artifact, instances, minInstances, sliceTargetValue.owningBlueprint()));
            log.trace("Restored slice target: {} with {} instances (min: {})", artifact, instances, minInstances);
        }

        private void rebuildSliceStateFromKVStoreEntries() {
            ctx.kvStore().forEach(NodeArtifactKey.class,
                                  NodeArtifactValue.class,
                                  this::restoreSliceStateFromNodeArtifact);
            log.info("Restored {} slice states from KV-Store", sliceStates.size());
        }

        private void restoreSliceStateFromNodeArtifact(NodeArtifactKey key, NodeArtifactValue value) {
            var sliceKey = SliceNodeKey.sliceNodeKey(key.artifact(), key.nodeId());
            sliceStates.put(sliceKey, value.state());
            updateTransitionalTimestamp(sliceKey, value.state(), value.transitionedAt());
        }

        private void handleSchemaPending(String datasource) {
            log.info("Schema migration pending for datasource: {}", datasource);
            ctx.schemaOrchestrator().migrateIfNeeded(datasource).onFailure(cause -> log.error("Schema migration failed for {}: {}",
                                                                                              datasource,
                                                                                              cause.message()));
        }

        private void handleSchemaCompleted(String datasource) {
            log.info("Schema migration completed for datasource: {}", datasource);
            sliceStates.entrySet().stream().filter(entry -> entry.getValue() == SliceState.LOADED).map(Map.Entry::getKey).toList().forEach(this::tryActivateIfDependenciesReady);
        }

        private void handleSliceNodeRemoval(SliceNodeKey sliceNodeKey) {
            sliceStates.remove(sliceNodeKey);
            transitionalStateTimestamps.remove(sliceNodeKey);

            if (permanentlyFailed.contains(sliceNodeKey.artifact())) {return;}

            SharedScheduler.schedule(this::reconcile, timeSpan(1).seconds());
        }

        private void handleAppBlueprintRemoval(AppBlueprintKey key) {
            var removedBlueprintId = key.blueprintId();
            var rollingUpdateArtifacts = blueprints.entrySet().stream().filter(e -> e.getValue()
                                                                                     .owner()
                                                                                     .equals(Option.some(removedBlueprintId))).map(Map.Entry::getKey).filter(a -> activeRoutings.contains(a.base())).toList();

            if (!rollingUpdateArtifacts.isEmpty()) {
                log.warn("Cannot delete blueprint '{}' — artifacts {} have active rolling updates",
                         removedBlueprintId.artifact().asString(),
                         rollingUpdateArtifacts);
                return;
            }

            log.info("App blueprint '{}' removed",
                     removedBlueprintId.artifact().asString());
            var artifactsToRemove = blueprints.entrySet().stream().filter(e -> e.getValue()
                                                                                .owner()
                                                                                .equals(Option.some(removedBlueprintId))).map(Map.Entry::getKey).toList();
            var consensusCommands = new ArrayList<KVCommand<AetherKey>>();

            for (var artifact : artifactsToRemove) {
                blueprints.remove(artifact);
                issueDeallocationCommands(artifact);
                consensusCommands.add(new KVCommand.Remove<>(SliceTargetKey.sliceTargetKey(artifact.base())));
            }

            submitBatch(consensusCommands);
            SharedScheduler.schedule(this::reconcile, timeSpan(5).seconds());
        }

        private void assignNodeRole(NodeId addedNode) {
            var currentCoreCount = activeNodes().size();
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
                submitActivationDirective(addedNode, ActivationDirectiveValue.worker());
            }
        }

        private void submitActivationDirective(NodeId targetNode, ActivationDirectiveValue directive) {
            var command = new KVCommand.Put<AetherKey, AetherValue>(ActivationDirectiveKey.activationDirectiveKey(targetNode),
                                                                    directive);
            ctx.cluster().apply(List.of(command)).onFailure(cause -> log.error("Failed to submit activation directive for {}: {}",
                                                                               targetNode,
                                                                               cause.message()));
        }

        private boolean shouldPromoteToCore(int currentCoreCount) {
            var effectiveMax = effectiveCoreMax();

            return effectiveMax == 0 || currentCoreCount <effectiveMax;
        }

        private int effectiveCoreMax() {
            return ctx.kvStore()
                      .get(ClusterConfigKey.CURRENT)
                      .flatMap(v -> v instanceof ClusterConfigValue cfg
                                    ? Option.some(cfg.coreCount())
                                    : Option.<Integer> none())
                      .or(ctx.coreMax());
        }

        public List<NodeId> activeNodes() {
            return ctx.snapshotSupplier()
                      .get()
                      .map(snapshot -> snapshot.coreMembers()
                                               .values()
                                               .stream()
                                               .map(CoreMember::nodeId)
                                               .filter(id -> !ctx.topologyManager()
                                                                 .isPassive(id))
                                               .toList())
                      .or(List::of);
        }

        public Set<NodeId> drainingNodes() {
            return ctx.drainingNodesSupplier().get();
        }

        private Set<String> activeCommunityIds() {
            return ctx.snapshotSupplier()
                      .get()
                      .map(snapshot -> Set.copyOf(snapshot.communities().keySet()))
                      .or(Set::of);
        }

        private Option<GovernorAnnouncementValue> communityGovernor(String communityId) {
            return ctx.kvStore()
                      .get(GovernorAnnouncementKey.forCommunity(communityId))
                      .filter(GovernorAnnouncementValue.class::isInstance)
                      .map(GovernorAnnouncementValue.class::cast);
        }

        private List<NodeId> allocatableNodes() {
            var readyNodes = ctx.readyNodesSupplier().get();

            return activeNodes().stream()
                                .filter(readyNodes::contains)
                                .toList();
        }

        AllocationPool buildAllocationPool() {
            var communityWorkers = buildCommunityWorkerMap();

            return AllocationPool.allocationPool(allocatableNodes(), List.copyOf(workerNodes), communityWorkers);
        }

        private Map<String, List<NodeId>> buildCommunityWorkerMap() {
            var communityIds = activeCommunityIds();

            if (communityIds.isEmpty()) {return Map.of();}

            var result = new HashMap<String, List<NodeId>>();
            communityIds.forEach(communityId -> communityGovernor(communityId).onPresent(announcement -> result.put(communityId,
                                                                                                                    announcement.members()
                                                                                                                                .isEmpty()
                                                                                                                    ? List.of(announcement.governorId())
                                                                                                                    : announcement.members())));

            return Map.copyOf(result);
        }

        private Map<String, GovernorAnnouncementValue> activeCommunities() {
            var communityIds = activeCommunityIds();

            if (communityIds.isEmpty()) {return Map.of();}

            var result = new HashMap<String, GovernorAnnouncementValue>();
            communityIds.forEach(communityId -> communityGovernor(communityId).onPresent(announcement -> result.put(communityId,
                                                                                                                    announcement)));

            return Map.copyOf(result);
        }

        private void cleanupAfterLifecycleDepartedAtomic(NodeId departedNode) {
            log.info("Snapshot-delta cleanup triggered for departed node {} (lifecycle=DECOMMISSIONED)", departedNode);
            var sliceKeysToRemove = sliceStates.keySet().stream().filter(key -> key.nodeId()
                                                                                   .equals(departedNode)).toList();
            sliceKeysToRemove.forEach(sliceStates::remove);
            sliceKeysToRemove.forEach(transitionalStateTimestamps::remove);
            var artifactKeysToRemove = findNodeArtifactKeysForNode(departedNode);
            var nodeRouteCommands = cleanupNodeRoutesForNode(departedNode);
            var consensusCommands = new ArrayList<KVCommand<AetherKey>>();
            artifactKeysToRemove.stream().<KVCommand<AetherKey>> map(KVCommand.Remove::new).forEach(consensusCommands::add);
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
            ctx.cluster().apply(consensusCommands).onFailure(cause -> log.error("Snapshot-delta cleanup for {} failed: {}",
                                                                                departedNode,
                                                                                cause.message()));
        }

        private void processActivationDirectivePut(NodeId nodeId, String role) {
            if (ActivationDirectiveValue.WORKER.equals(role)) {addWorkerNode(nodeId);} else {workerNodes.remove(nodeId);}
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
            if (deactivated.get() || !drainingNodes().contains(drainingNode)) {return;}

            var slicesOnNode = sliceStates.keySet().stream().filter(key -> key.nodeId()
                                                                              .equals(drainingNode)).filter(key -> isLiveState(sliceStates.getOrDefault(key,
                                                                                                                                                        SliceState.FAILED))).toList();

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
            var targetNodes = allocatableNodes().stream().filter(n -> !n.equals(drainingNode)).collect(Collectors.toSet());
            var allocated = issueAllocationsForNodes(artifact, 1, targetNodes);

            if (allocated == 0) {
                log.warn("Drain eviction: no allocatable node for replacement of {} (will retry)", artifact);
                SharedScheduler.schedule(() -> evictNextSliceFromNode(drainingNode), timeSpan(5).seconds());

                return;
            }

            SharedScheduler.schedule(() -> checkReplacementAndUnload(originalKey), timeSpan(3).seconds());
        }

        private void checkReplacementAndUnload(SliceNodeKey originalKey) {
            if (deactivated.get() || !drainingNodes().contains(originalKey.nodeId())) {return;}

            var artifact = originalKey.artifact();
            var drainingNode = originalKey.nodeId();
            var hasActiveReplacement = sliceStates.entrySet().stream().filter(e -> e.getKey()
                                                                                    .artifact()
                                                                                    .equals(artifact)).filter(e -> !e.getKey()
                                                                                                                     .nodeId()
                                                                                                                     .equals(drainingNode)).anyMatch(e -> e.getValue() == SliceState.ACTIVE);

            if (hasActiveReplacement) {
                log.info("Drain eviction: replacement ACTIVE for {}, unloading from {}", artifact, drainingNode);
                issueUnloadCommand(originalKey);
                SharedScheduler.schedule(() -> evictNextSliceFromNode(drainingNode), timeSpan(2).seconds());
            } else {
                log.debug("Drain eviction: replacement not yet ACTIVE for {}, rechecking", artifact);
                SharedScheduler.schedule(() -> checkReplacementAndUnload(originalKey), timeSpan(3).seconds());
            }
        }

        private void completeDrain(NodeId drainingNode) {
            log.info("Drain complete for node {}, emitting DrainCompleted signal to LifecycleWriter", drainingNode);
            ctx.healthSignalSink().emit(new HealthSignal.DrainCompleted(drainingNode, Epoch.ZERO));
        }

        private void handleSliceTargetChange(SliceTargetKey key, SliceTargetValue value) {
            var artifactBase = key.artifactBase();
            var newVersion = value.currentVersion();
            var newArtifact = artifactBase.withVersion(newVersion);
            var desiredInstances = value.targetInstances();

            if (!activeRoutings.contains(artifactBase)) {
                var oldVersions = blueprints.keySet().stream().filter(a -> artifactBase.matches(a) && !a.version()
                                                                                                        .equals(newVersion)).toList();
                for (var oldArtifact : oldVersions) {
                    log.info("Removing old version {} (new version: {})", oldArtifact, newArtifact);
                    blueprints.remove(oldArtifact);
                    issueDeallocationCommands(oldArtifact);
                }
            }

            var minInstances = value.effectiveMinInstances();
            log.info("Slice target changed for {}: {} instances (min: {})", newArtifact, desiredInstances, minInstances);
            blueprints.put(newArtifact,
                           Blueprint.blueprint(newArtifact, desiredInstances, minInstances, value.owningBlueprint()));
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

            if (hasConflictingOwnership(expanded)) {return;}

            var consensusCommands = new ArrayList<KVCommand<AetherKey>>();
            var schemaRequired = resolveSchemaRequired(expanded.id());

            for (var slice : expanded.loadOrder()) {
                var artifact = slice.artifact();
                log.info("Scheduling {} with {} requested instances ({} allocatable nodes)",
                         artifact,
                         slice.instances(),
                         nodes.size());
                permanentlyFailed.remove(artifact);

                if (shouldSuppressActivation(artifact, registerOnly)) {
                    log.info("Blueprint {} registered-only — skipping SliceTargetValue Put for slice {} (existing currentVersion preserved)",
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
                consensusCommands.add(new KVCommand.Put<>(SliceTargetKey.sliceTargetKey(artifact.base()),
                                                          SliceTargetValue.sliceTargetValue(artifact.version(),
                                                                                            slice.instances(),
                                                                                            slice.minAvailable(),
                                                                                            Option.some(expanded.id()))));
            }

            collectStreamMetadataCommands(expanded.id(), consensusCommands);
            submitBatch(consensusCommands);
            trackInFlightBlueprint(expanded, previousExpanded);
        }

        /// Returns `true` when the per-slice `SliceTargetValue` Put MUST be suppressed because:
        /// the publishing blueprint was registered via `/api/blueprints/publish` (`registerOnly=true`),
        /// AND an existing `SliceTargetValue` already pins this slice base to an active version.
        /// First-ever publishes (no existing `SliceTargetValue`) always proceed — register-only
        /// cannot suppress fresh-slice bootstrap, otherwise the slice would have no target at all.
        private boolean shouldSuppressActivation(Artifact artifact, boolean registerOnly) {
            if (!registerOnly) {return false;}
            return ctx.kvStore()
                      .get(SliceTargetKey.sliceTargetKey(artifact.base()))
                      .filter(SliceTargetValue.class::isInstance)
                      .isPresent();
        }

        private boolean hasConflictingOwnership(ExpandedBlueprint expanded) {
            for (var slice : expanded.loadOrder()) {
                var artifactBase = slice.artifact().base();
                for (var bp : blueprints.values()) {
                    if (!artifactBase.equals(bp.artifact().base())) {continue;}

                    var conflict = bp.owner().filter(o -> !o.equals(expanded.id()));

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

        private boolean resolveSchemaRequired(BlueprintId blueprintId) {
            // After the Batch 1 envelope cleanup, slice META-INF/resources.toml no longer round-trips
            // through KV (BlueprintResourcesKey/Value were removed). The resources TOML is now
            // embedded directly on the persisted ExpandedBlueprint, so we read it from AppBlueprint.
            var blueprintKey = AppBlueprintKey.appBlueprintKey(blueprintId);

            return ctx.kvStore()
                      .get(blueprintKey)
                      .filter(AppBlueprintValue.class::isInstance)
                      .map(AppBlueprintValue.class::cast)
                      .flatMap(value -> value.blueprint()
                                             .resourcesConfig())
                      .flatMap(toml -> BlueprintParser.parse(toml).option())
                      .flatMap(org.pragmatica.aether.slice.blueprint.Blueprint::deploymentConfig)
                      .map(DeploymentConfig::schemaRequired)
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
            if (ctx.atomicity() != DeploymentAtomicity.ALL_OR_NOTHING || restoringBlueprints.contains(expanded.id())) {return Option.empty();}
            return Option.option(inFlightBlueprints.get(expanded.id())).map(InFlightBlueprint::expanded);
        }

        private void trackInFlightBlueprint(ExpandedBlueprint expanded, Option<ExpandedBlueprint> previousExpanded) {
            if (ctx.atomicity() == DeploymentAtomicity.ALL_OR_NOTHING && !restoringBlueprints.contains(expanded.id())) {
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
            ctx.kvStore().get(targetKey).filter(v -> v instanceof SliceTargetValue).map(v -> (SliceTargetValue) v).onPresent(targetValue -> removeNonTargetVersions(artifactBase,
                                                                                                                                                                    targetValue.currentVersion()));
        }

        private void removeNonTargetVersions(ArtifactBase artifactBase, Version currentVersion) {
            var oldVersions = blueprints.keySet().stream().filter(a -> artifactBase.matches(a) && !a.version()
                                                                                                    .equals(currentVersion)).toList();
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

            if (state == SliceState.LOADED) {tryActivateIfDependenciesReady(sliceKey);}
            if (state == SliceState.ACTIVE) {handleSliceActive(sliceKey);}
            if (state == SliceState.FAILED) {handleSliceFailure(sliceKey, sliceNodeValue);}
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
            issueUnloadCommand(sliceKey);

            if (sliceNodeValue.fatal()) {handleDeterministicFailure(sliceKey, failureReason);} else {
                handleTransientFailure(sliceKey, failureReason);
            }
        }

        private void handleDeterministicFailure(SliceNodeKey sliceKey, String failureReason) {
            var artifact = sliceKey.artifact();

            if (permanentlyFailed.contains(artifact)) {return;}

            permanentlyFailed.add(artifact);
            log.error("Deterministic failure for {} on {}: {} — will NOT retry",
                      artifact,
                      sliceKey.nodeId(),
                      failureReason);
            ctx.router().route(DeploymentFailed.deploymentFailed(artifact,
                                                                 sliceKey.nodeId(),
                                                                 SliceState.FAILED,
                                                                 failureReason,
                                                                 ctx.nowMs()));

            if (ctx.atomicity() == DeploymentAtomicity.ALL_OR_NOTHING) {rollbackBlueprintForArtifact(artifact);}
        }

        private void handleTransientFailure(SliceNodeKey sliceKey, String failureReason) {
            var retryCount = retryCounters.merge(sliceKey.asString(), 1, Integer::sum);

            if (retryCount > MAX_RETRIES) {
                logMaxRetriesExceeded(sliceKey, failureReason);

                return;
            }

            var delaySeconds = Math.min(1L <<(retryCount - 1), MAX_RETRY_DELAY_SECONDS);
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
            ctx.router().route(DeploymentFailed.deploymentFailed(sliceKey.artifact(),
                                                                 sliceKey.nodeId(),
                                                                 SliceState.FAILED,
                                                                 failureReason,
                                                                 ctx.nowMs()));
        }

        private boolean areSchemasReady(SliceNodeKey sliceKey) {
            var blueprint = blueprints.get(sliceKey.artifact());

            if (blueprint != null && !blueprint.schemaRequired()) {return true;}

            var schemasReady = new AtomicBoolean(true);
            ctx.kvStore().forEach(SchemaVersionKey.class,
                                  SchemaVersionValue.class,
                                  (_, value) -> checkSchemaBlocking(value, schemasReady));

            return schemasReady.get();
        }

        private static void checkSchemaBlocking(SchemaVersionValue value, AtomicBoolean schemasReady) {
            if (value.status() == SchemaStatus.PENDING || value.status() == SchemaStatus.MIGRATING) {schemasReady.set(false);}
        }

        private void tryActivateIfDependenciesReady(SliceNodeKey sliceKey) {
            var artifact = sliceKey.artifact();

            if (!areSchemasReady(sliceKey)) {
                log.debug("Slice {} waiting for schema migrations to complete", artifact);

                return;
            }

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
            sliceStates.entrySet().stream().filter(entry -> entry.getValue() == SliceState.LOADED).map(Map.Entry::getKey).filter(key -> dependsOn(key.artifact(),
                                                                                                                                                  activatedArtifact)).forEach(this::tryActivateIfDependenciesReady);
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

        private void issueLoadCommand(SliceNodeKey sliceKey) {
            log.debug("Issuing LOAD command for {}", sliceKey);
            var timestamp = ctx.nowMs();
            applyStateWrite(sliceKey, SliceState.LOAD).withSuccess(_ -> ctx.router()
                                                                           .route(DeploymentStarted.deploymentStarted(sliceKey.artifact(),
                                                                                                                      sliceKey.nodeId(),
                                                                                                                      timestamp))).onFailure(cause -> handleSliceNodeWriteFailure(sliceKey,
                                                                                                                                                                                  cause));
        }

        private void issueUnloadCommand(SliceNodeKey sliceKey) {
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

        private void removeNodeArtifactKey(SliceNodeKey sliceKey) {
            KVCommand<AetherKey> removeArtifact = new KVCommand.Remove<>(NodeArtifactKey.nodeArtifactKey(sliceKey.nodeId(),
                                                                                                         sliceKey.artifact()));
            ctx.cluster().apply(List.of(removeArtifact)).onFailure(cause -> log.error("Failed to remove node-artifact-key for {}: {}",
                                                                                      sliceKey,
                                                                                      cause.message()));
        }

        private void submitBatch(List<KVCommand<AetherKey>> commands) {
            if (commands.isEmpty()) {return;}
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
            var sliceKeysToRemove = sliceStates.keySet().stream().filter(key -> key.nodeId()
                                                                                   .equals(removedNode)).toList();
            sliceKeysToRemove.forEach(sliceStates::remove);
            sliceKeysToRemove.forEach(transitionalStateTimestamps::remove);
            sliceKeysToRemove.forEach(this::removeNodeArtifactKey);
            var artifactKeysToRemove = findNodeArtifactKeysForNode(removedNode);
            var nodeRouteCommands = cleanupNodeRoutesForNode(removedNode);
            List<KVCommand<AetherKey>> consensusCommands = new ArrayList<>();
            artifactKeysToRemove.stream().<KVCommand<AetherKey>> map(KVCommand.Remove::new).forEach(consensusCommands::add);
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
            ctx.kvStore().forEach(NodeArtifactKey.class,
                                  NodeArtifactValue.class,
                                  (key, _) -> collectNodeArtifactKeyForNode(result, key, nodeId));

            return result;
        }

        private void collectNodeArtifactKeyForNode(List<NodeArtifactKey> result, NodeArtifactKey key, NodeId nodeId) {
            if (key.nodeId().equals(nodeId)) {result.add(key);}
        }

        private List<KVCommand<AetherKey>> cleanupNodeRoutesForNode(NodeId removedNode) {
            var commands = new ArrayList<KVCommand<AetherKey>>();
            ctx.kvStore().forEach(NodeRoutesKey.class,
                                  AetherValue.NodeRoutesValue.class,
                                  (key, _) -> collectNodeRoutesKeyForNode(commands, key, removedNode));

            return commands;
        }

        private void collectNodeRoutesKeyForNode(List<KVCommand<AetherKey>> commands,
                                                 NodeRoutesKey key,
                                                 NodeId removedNode) {
            if (key.nodeId().equals(removedNode)) {commands.add(new KVCommand.Remove<>(key));}
        }

        @Contract
        public void cleanupStaleNodeRoutes() {
            var currentNodes = new HashSet<>(activeNodes());
            var commands = new ArrayList<KVCommand<AetherKey>>();
            ctx.kvStore().forEach(NodeRoutesKey.class,
                                  AetherValue.NodeRoutesValue.class,
                                  (key, _) -> collectStaleNodeRoutesKey(commands, key, currentNodes));

            if (!commands.isEmpty()) {
                log.debug("Cleaning up {} stale node-routes entries", commands.size());
                ctx.cluster().apply(commands).onFailure(cause -> log.error("Failed to clean up stale node routes: {}",
                                                                           cause.message()));
            }
        }

        private void collectStaleNodeRoutesKey(List<KVCommand<AetherKey>> commands,
                                               NodeRoutesKey key,
                                               Set<NodeId> currentNodes) {
            if (!currentNodes.contains(key.nodeId())) {commands.add(new KVCommand.Remove<>(key));}
        }

        @Contract
        public void cleanupStaleSliceEntries() {
            var currentNodes = new HashSet<>(activeNodes());
            var staleKeys = sliceStates.keySet().stream().filter(key -> !currentNodes.contains(key.nodeId())).toList();

            if (staleKeys.isEmpty()) {return;}

            staleKeys.forEach(sliceStates::remove);
            List<KVCommand<AetherKey>> commands = staleKeys.stream().<KVCommand<AetherKey>> map(KVCommand.Remove::new).toList();
            log.info("Cleaning up {} stale slice entries", staleKeys.size());
            ctx.cluster().apply(commands).onFailure(cause -> log.error("Failed to clean up stale slice entries: {}",
                                                                       cause.message()));
        }

        @Contract
        public void cleanupStaleNodeArtifactEntries() {
            var currentNodes = new HashSet<>(activeNodes());
            var staleKeys = new ArrayList<NodeArtifactKey>();
            ctx.kvStore().forEach(NodeArtifactKey.class,
                                  NodeArtifactValue.class,
                                  (key, _) -> collectStaleNodeArtifactKey(staleKeys, key, currentNodes));

            if (staleKeys.isEmpty()) {return;}

            List<KVCommand<AetherKey>> commands = staleKeys.stream().<KVCommand<AetherKey>> map(KVCommand.Remove::new).toList();
            log.info("Cleaning up {} stale node-artifact entries", staleKeys.size());
            ctx.cluster().apply(commands).onFailure(cause -> log.error("Failed to clean up stale node-artifact entries: {}",
                                                                       cause.message()));
        }

        private void collectStaleNodeArtifactKey(List<NodeArtifactKey> result,
                                                 NodeArtifactKey key,
                                                 Set<NodeId> currentNodes) {
            if (!currentNodes.contains(key.nodeId())) {result.add(key);}
        }

        private void cleanupOrphanedSliceEntries() {
            var orphanedEntries = sliceStates.entrySet().stream().filter(entry -> !blueprints.containsKey(entry.getKey()
                                                                                                               .artifact())).toList();

            if (orphanedEntries.isEmpty()) {return;}
            for (var entry : orphanedEntries) {
                var key = entry.getKey();
                var state = entry.getValue();
                sliceStates.remove(key);

                if (state == SliceState.UNLOAD || state == SliceState.UNLOADING) {removeNodeArtifactKey(key);} else {issueUnloadCommand(key);}
            }

            log.info("Cleaning up {} orphaned slice entries (no matching blueprint)", orphanedEntries.size());
        }

        private void issueAllocationCommands(Artifact artifact, int desiredInstances) {
            if (hasNoAllocatableNodes(artifact)) {return;}

            var currentInstances = getCurrentInstances(artifact);
            logAllocationAttempt(artifact, desiredInstances, currentInstances);
            issueAdjustmentCommands(artifact, desiredInstances, currentInstances);
        }

        private boolean hasNoAllocatableNodes(Artifact artifact) {
            if (allocatableNodes().isEmpty()) {
                log.warn("No allocatable nodes available for allocation of {}", artifact);

                return true;
            }
            return false;
        }

        private void logAllocationAttempt(Artifact artifact,
                                          int desiredInstances,
                                          List<SliceNodeKey> currentInstances) {
            log.debug("Allocating {} instances of {} (current: {}) across {} allocatable nodes",
                      desiredInstances,
                      artifact,
                      currentInstances.size(),
                      allocatableNodes().size());
        }

        private void issueAdjustmentCommands(Artifact artifact,
                                             int desiredInstances,
                                             List<SliceNodeKey> currentInstances) {
            var currentCount = currentInstances.size();
            if (desiredInstances > currentCount) {
                issueScaleUpCommands(artifact, desiredInstances - currentCount, currentInstances);
            } else if (desiredInstances <currentCount) {
                issueScaleDownCommands(artifact, currentCount - desiredInstances, currentInstances);
            }
        }

        private void issueScaleUpCommands(Artifact artifact, int toAdd, List<SliceNodeKey> existingInstances) {
            var nodes = allocatableNodes();
            log.debug("issueScaleUpCommands: artifact={}, toAdd={}, allocatableNodes={}, nodeIds={}",
                      artifact,
                      toAdd,
                      nodes.size(),
                      nodes);
            var nodesWithInstances = existingInstances.stream().map(SliceNodeKey::nodeId).collect(Collectors.toSet());
            var trulyEmptyNodes = findTrulyEmptyNodes();
            log.debug("issueScaleUpCommands: found {} truly empty nodes: {}", trulyEmptyNodes.size(), trulyEmptyNodes);
            var allocated = issueAllocationsForNodes(artifact, toAdd, trulyEmptyNodes);
            log.debug("issueScaleUpCommands: allocated {} instances to truly empty nodes", allocated);
            var remaining = toAdd - allocated;

            if (remaining <= 0) {return;}

            var emptyForArtifactCount = issueAllocationsForEmptyNodes(artifact, remaining, nodesWithInstances);
            allocated += emptyForArtifactCount;
            log.debug("issueScaleUpCommands: allocated {} instances to nodes without this artifact, remaining={}",
                      emptyForArtifactCount,
                      remaining - emptyForArtifactCount);
            issueRoundRobinAllocations(artifact, toAdd - allocated);
        }

        private Set<NodeId> findTrulyEmptyNodes() {
            var nodesWithAnySlice = sliceStates.keySet().stream().map(SliceNodeKey::nodeId).collect(Collectors.toSet());

            return allocatableNodes().stream()
                                   .filter(node -> !nodesWithAnySlice.contains(node))
                                   .collect(Collectors.toSet());
        }

        private int issueAllocationsForNodes(Artifact artifact, int toAdd, Set<NodeId> targetNodes) {
            var allocated = 0;

            for (var node : targetNodes) {
                if (allocated >= toAdd) {break;}
                if (tryAllocate(artifact, node)) {allocated++;}
            }

            return allocated;
        }

        private int issueAllocationsForEmptyNodes(Artifact artifact, int toAdd, Set<NodeId> nodesWithInstances) {
            var nodes = allocatableNodes();
            var nodeCount = nodes.size();

            if (nodeCount == 0) {return 0;}

            var allocated = 0;

            for (var i = 0;i <nodeCount && allocated <toAdd;i++) {
                var nodeIndex = Math.floorMod(allocationIndex.getAndIncrement(), nodeCount);
                var node = nodes.get(nodeIndex);

                if (!nodesWithInstances.contains(node) && tryAllocate(artifact, node)) {allocated++;}
            }

            return allocated;
        }

        private boolean tryAllocate(Artifact artifact, NodeId node) {
            var sliceKey = SliceNodeKey.sliceNodeKey(artifact, node);
            var alreadyExists = sliceStates.containsKey(sliceKey);
            log.debug("tryAllocate: artifact={}, node={}, sliceKey={}, alreadyExists={}",
                      artifact,
                      node,
                      sliceKey,
                      alreadyExists);

            if (!alreadyExists) {
                sliceStates.put(sliceKey, SliceState.LOAD);
                issueLoadCommand(sliceKey);

                return true;
            }

            return false;
        }

        private void issueRoundRobinAllocations(Artifact artifact, int remaining) {
            if (remaining <= 0) {return;}

            var nodes = allocatableNodes();

            if (nodes.isEmpty()) {
                log.warn("No allocatable nodes for round-robin allocation of {}", artifact);

                return;
            }

            var nodeCount = nodes.size();
            var allocated = 0;
            var attempts = 0;
            var maxAttempts = nodeCount * 2;

            while (allocated <remaining && attempts <maxAttempts) {
                var nodeIndex = Math.floorMod(allocationIndex.getAndIncrement(), nodeCount);
                var node = nodes.get(nodeIndex);

                if (tryAllocate(artifact, node)) {allocated++;}

                attempts++;
            }
            if (allocated <remaining) {
                log.warn("Could only allocate {} of {} requested instances for {} (not enough nodes without instances)",
                         allocated,
                         remaining,
                         artifact);
            }
        }

        private void issueScaleDownCommands(Artifact artifact, int toRemove, List<SliceNodeKey> existingInstances) {
            var minInstances = Option.option(blueprints.get(artifact)).map(Blueprint::minInstances).or(1);
            var activeCount = existingInstances.size();
            var maxRemovable = Math.max(0, activeCount - minInstances);
            var actualRemove = Math.min(toRemove, maxRemovable);

            if (actualRemove <toRemove) {
                log.info("Budget enforcement: capping scale-down of {} from {} to {} (min: {}, active: {})",
                         artifact,
                         toRemove,
                         actualRemove,
                         minInstances,
                         activeCount);
            }
            if (actualRemove == 0) {return;}

            existingInstances.stream().skip(Math.max(0, activeCount - actualRemove)).forEach(this::issueUnloadCommand);
        }

        private List<SliceNodeKey> getCurrentInstances(Artifact artifact) {
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
            } else {transitionalStateTimestamps.remove(sliceKey);}
        }

        private void detectStuckTransitionalStates() {
            var now = ctx.nowMs();
            var stuckEntries = transitionalStateTimestamps.entrySet().stream().filter(entry -> isStuckTransitional(entry.getKey(),
                                                                                                                   entry.getValue(),
                                                                                                                   now)).map(Map.Entry::getKey).toList();

            if (stuckEntries.isEmpty()) {return;}

            log.warn("Detected {} slices stuck in transitional states", stuckEntries.size());
            stuckEntries.forEach(this::issueStuckRemediationCommand);
        }

        private boolean isStuckTransitional(SliceNodeKey sliceKey, long enteredAt, long now) {
            return Option.option(sliceStates.get(sliceKey))
                         .filter(SliceState::isTransitional)
                         .flatMap(SliceState::timeout)
                         .filter(timeout -> (now - enteredAt) > timeout.millis() * STUCK_TIMEOUT_MULTIPLIER)
                         .isPresent();
        }

        private void issueStuckRemediationCommand(SliceNodeKey sliceKey) {
            Option.option(sliceStates.get(sliceKey)).onPresent(state -> executeStuckRemediation(sliceKey, state));
        }

        private void executeStuckRemediation(SliceNodeKey sliceKey, SliceState state) {
            transitionalStateTimestamps.remove(sliceKey);
            switch (state) {
                case LOADING, ACTIVATING -> resetStuckLoadingSlice(sliceKey, state);
                case DEACTIVATING, UNLOADING -> forceRemoveStuckSlice(sliceKey, state);
                default -> {}
            }
        }

        private void resetStuckLoadingSlice(SliceNodeKey sliceKey, SliceState state) {
            log.warn("Force-resetting stuck {} slice {} on {} — issuing UNLOAD",
                     state,
                     sliceKey.artifact(),
                     sliceKey.nodeId());
            sliceStates.remove(sliceKey);
            issueUnloadCommand(sliceKey);
        }

        private void forceRemoveStuckSlice(SliceNodeKey sliceKey, SliceState state) {
            log.warn("Force-removing stuck {} slice {} on {} from DHT", state, sliceKey.artifact(), sliceKey.nodeId());
            sliceStates.remove(sliceKey);
            removeNodeArtifactKey(sliceKey);
        }

        private void issueDeallocationCommands(Artifact artifact) {
            getCurrentInstances(artifact).forEach(this::issueUnloadCommand);
            removeWorkerDirective(artifact);
        }

        private void issueAllocationCommandsWithPlacement(Artifact artifact, int desiredInstances, String placement) {
            var policy = PlacementPolicy.valueOf(placement);
            var pool = buildAllocationPool();
            var targetNodes = pool.nodesForPolicy(policy);

            if (targetNodes.isEmpty()) {
                log.warn("No nodes available for placement {} of {}, falling back to core", placement, artifact);
                issueAllocationCommands(artifact, desiredInstances);

                return;
            }
            if (policy != PlacementPolicy.CORE_ONLY && pool.hasWorkers()) {
                distributeWorkerOrCommunity(artifact, desiredInstances, placement, pool);
            }
            if (policy == PlacementPolicy.CORE_ONLY || (policy == PlacementPolicy.WORKERS_PREFERRED && !pool.hasWorkers()) || policy == PlacementPolicy.ALL) {
                issueAllocationCommands(artifact, desiredInstances);
            }
        }

        private void distributeWorkerOrCommunity(Artifact artifact,
                                                 int desiredInstances,
                                                 String placement,
                                                 AllocationPool pool) {
            if (pool.hasCommunities()) {distributeToCommunities(artifact, desiredInstances, placement);} else {
                writeWorkerDirective(artifact, desiredInstances, placement);
            }
        }

        private void writeWorkerDirective(Artifact artifact, int targetInstances, String placement) {
            var key = WorkerSliceDirectiveKey.workerSliceDirectiveKey(artifact);
            var value = WorkerSliceDirectiveValue.workerSliceDirectiveValue(artifact, targetInstances, placement);
            var command = new KVCommand.Put<AetherKey, AetherValue>(key, value);
            ctx.cluster().apply(List.of(command)).onSuccess(_ -> log.info("Written worker directive for {} with {} instances",
                                                                          artifact,
                                                                          targetInstances)).onFailure(cause -> log.error("Failed to write worker directive for {}: {}",
                                                                                                                         artifact,
                                                                                                                         cause.message()));
        }

        private void writeWorkerDirective(Artifact artifact,
                                          int targetInstances,
                                          String placement,
                                          String communityId) {
            var key = WorkerSliceDirectiveKey.workerSliceDirectiveKey(artifact, communityId);
            var value = WorkerSliceDirectiveValue.workerSliceDirectiveValue(artifact,
                                                                            targetInstances,
                                                                            placement,
                                                                            communityId);
            var command = new KVCommand.Put<AetherKey, AetherValue>(key, value);
            ctx.cluster().apply(List.of(command)).onSuccess(_ -> log.info("Written worker directive for {} community '{}' with {} instances",
                                                                          artifact,
                                                                          communityId,
                                                                          targetInstances)).onFailure(cause -> log.error("Failed to write worker directive for {} community '{}': {}",
                                                                                                                         artifact,
                                                                                                                         communityId,
                                                                                                                         cause.message()));
        }

        private void distributeToCommunities(Artifact artifact, int desiredInstances, String placement) {
            var communities = activeCommunities();
            var totalMembers = communities.values().stream().mapToInt(GovernorAnnouncementValue::memberCount).sum();

            if (totalMembers == 0) {
                writeWorkerDirective(artifact, desiredInstances, placement);

                return;
            }

            var sorted = new ArrayList<>(communities.entrySet());
            sorted.sort(Comparator.<Map.Entry<String, GovernorAnnouncementValue>> comparingInt(e -> e.getValue()
                                                                                                     .memberCount()).reversed());
            var remaining = desiredInstances;

            for (var i = 0;i <sorted.size();i++) {
                var share = computeCommunityShare(i, sorted, desiredInstances, totalMembers, remaining);
                if (share > 0) {
                    writeWorkerDirective(artifact,
                                         share,
                                         placement,
                                         sorted.get(i).getKey());
                    remaining -= share;
                }
            }

            assignRemainder(artifact, remaining, placement, sorted);
        }

        private int computeCommunityShare(int index,
                                          List<Map.Entry<String, GovernorAnnouncementValue>> sorted,
                                          int desiredInstances,
                                          int totalMembers,
                                          int remaining) {
            if (index == 0) {return computeLargestCommunityShare(sorted, desiredInstances, totalMembers, remaining);}

            var memberCount = sorted.get(index).getValue().memberCount();
            var proportional = Math.max(1, Math.round((float) desiredInstances * memberCount / totalMembers));

            return Math.min(proportional, remaining);
        }

        private int computeLargestCommunityShare(List<Map.Entry<String, GovernorAnnouncementValue>> sorted,
                                                 int desiredInstances,
                                                 int totalMembers,
                                                 int remaining) {
            var share = remaining;

            for (var j = 1;j <sorted.size();j++) {
                var otherCount = sorted.get(j).getValue().memberCount();
                share -= Math.max(1, Math.round((float) desiredInstances * otherCount / totalMembers));
            }

            return Math.min(Math.max(1, share), remaining);
        }

        private void assignRemainder(Artifact artifact,
                                     int remaining,
                                     String placement,
                                     List<Map.Entry<String, GovernorAnnouncementValue>> sorted) {
            if (remaining > 0) {
                writeWorkerDirective(artifact,
                                     remaining,
                                     placement,
                                     sorted.getFirst().getKey());
            }
        }

        private void removeWorkerDirective(Artifact artifact) {
            var commands = new ArrayList<KVCommand<AetherKey>>();
            commands.add(new KVCommand.Remove<>(WorkerSliceDirectiveKey.workerSliceDirectiveKey(artifact)));

            for (var communityId : activeCommunityIds()) {
                commands.add(new KVCommand.Remove<>(WorkerSliceDirectiveKey.workerSliceDirectiveKey(artifact,
                                                                                                    communityId)));
            }

            ctx.cluster().apply(commands).onFailure(cause -> log.debug("No worker directive to remove for {}: {}",
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

            for (var blueprint : blueprintSnapshot) {if (reconcileBlueprint(blueprint, rebalanceBudget)) {reconciled++;}}

            log.debug("Reconciliation complete: {} of {} blueprints required adjustment", reconciled, blueprints.size());
            cleanupOrphanedSliceEntries();
            cleanupStaleNodeRoutes();
            cleanupStaleNodeArtifactEntries();
            cleanupStaleSliceEntries();
            detectStuckTransitionalStates();
        }

        private boolean reconcileBlueprint(Blueprint blueprint,
                                           java.util.concurrent.atomic.AtomicInteger rebalanceBudget) {
            var artifact = blueprint.artifact();

            if (permanentlyFailed.contains(artifact)) {return false;}
            if (hasInstancesOnDrainingNodes(artifact)) {return false;}

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

            if (allocatable.size() <2) {
                consecutiveImbalancedTicks.remove(artifact);

                return false;
            }

            var nodesHostingThisArtifact = currentInstances.stream().map(SliceNodeKey::nodeId).collect(Collectors.toUnmodifiableSet());
            var totalLoadByNode = sliceStates.entrySet().stream().filter(entry -> isLiveState(entry.getValue())).map(Map.Entry::getKey).collect(Collectors.groupingBy(SliceNodeKey::nodeId,
                                                                                                                                                                      Collectors.counting()));
            var maxLoad = allocatable.stream().mapToLong(node -> totalLoadByNode.getOrDefault(node, 0L)).max().orElse(0L);
            var minLoad = allocatable.stream().mapToLong(node -> totalLoadByNode.getOrDefault(node, 0L)).min().orElse(0L);

            if (maxLoad - minLoad <= 1) {
                consecutiveImbalancedTicks.remove(artifact);

                return false;
            }

            var donorOpt = nodesHostingThisArtifact.stream().max(Comparator.comparingLong(node -> totalLoadByNode.getOrDefault(node,
                                                                                                                               0L)));
            var targetOpt = allocatable.stream().filter(node -> !nodesHostingThisArtifact.contains(node)).min(Comparator.comparingLong(node -> totalLoadByNode.getOrDefault(node,
                                                                                                                                                                            0L)));

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

            if (ticks <REBALANCE_HYSTERESIS_TICKS) {
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
            if (currentCount <desiredCount) {
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
                    }

                    break;
                }
            }
        }

        private void rollbackBlueprintForArtifact(Artifact failedArtifact) {
            for (var entry : inFlightBlueprints.entrySet()) {
                var blueprintId = entry.getKey();
                var inflight = entry.getValue();

                if (!inflight.pendingSlices().contains(failedArtifact) && !inflight.activeSlices().contains(failedArtifact)) {continue;}
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
                inflight.previousBlueprint().apply(() -> unloadBlueprintSlices(inflight),
                                                   previous -> restorePreviousBlueprint(blueprintId, previous));
                break;
            }
        }

        private void unloadBlueprintSlices(InFlightBlueprint inflight) {
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
            log.info("ALL_OR_NOTHING: Unloading {} slices from failed blueprint {}",
                     allSlices.size(),
                     inflight.id().asString());
            submitBatch(consensusCommands);
        }

        private void restorePreviousBlueprint(BlueprintId blueprintId, ExpandedBlueprint previous) {
            restoringBlueprints.add(blueprintId);
            log.info("ALL_OR_NOTHING: Restoring previous blueprint {} with {} slices",
                     blueprintId.asString(),
                     previous.loadOrder().size());
            var bpKey = AppBlueprintKey.appBlueprintKey(blueprintId);
            var bpValue = AppBlueprintValue.appBlueprintValue(previous);
            var command = new KVCommand.Put<AetherKey, AetherValue>(bpKey, bpValue);
            ctx.cluster().apply(List.of(command)).onSuccess(_ -> SharedScheduler.schedule(() -> restoringBlueprints.remove(blueprintId),
                                                                                          timeSpan(5).seconds())).onFailure(cause -> handleBlueprintRestoreFailure(blueprintId,
                                                                                                                                                                   cause));
        }

        private void handleBlueprintRestoreFailure(BlueprintId blueprintId, Cause cause) {
            log.error("ALL_OR_NOTHING: Failed to restore previous blueprint {}: {}",
                      blueprintId.asString(),
                      cause.message());
            restoringBlueprints.remove(blueprintId);
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
