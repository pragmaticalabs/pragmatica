// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.fsm;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.config.ConfigNotificationManager;
import org.pragmatica.aether.deployment.drain.DrainReason;
import org.pragmatica.aether.deployment.node.NodeDeploymentManager.SliceDeployment;
import org.pragmatica.aether.deployment.node.NodeDeploymentManager.SuspendedSlice;
import org.pragmatica.aether.deployment.node.RoutingEpochAckTracker;
import org.pragmatica.aether.deployment.node.fsm.NodeDeploymentEvents.LeavingRequested;
import org.pragmatica.aether.deployment.node.fsm.NodeDeploymentEvents.NodeArtifactPutReceived;
import org.pragmatica.aether.deployment.node.fsm.NodeDeploymentEvents.NodeArtifactRemoveReceived;
import org.pragmatica.aether.deployment.node.fsm.NodeDeploymentEvents.NodeRoutesPutReceived;
import org.pragmatica.aether.http.HttpRoutePublisher;
import org.pragmatica.aether.invoke.CronExpression;
import org.pragmatica.aether.invoke.ScheduledTaskManager;
import org.pragmatica.aether.metrics.deployment.DeploymentEvent.DeploymentCompleted;
import org.pragmatica.aether.metrics.deployment.DeploymentEvent.DeploymentFailed;
import org.pragmatica.aether.metrics.deployment.DeploymentEvent.StateTransition;
import org.pragmatica.aether.slice.ConfigFacade;
import org.pragmatica.aether.slice.DefaultSliceBridge;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.TopicAddressResolver;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.BlueprintStreamBindingsKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamRegistrationKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamRegistryKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.TopicSubscriptionKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.BlueprintStreamBindingsValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceNodeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamRegistrationValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamRegistryValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopicSubscriptionValue;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.stream.StreamRegistryEntry;
import org.pragmatica.aether.resource.ScheduleConfig;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.resource.TopicConfig;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.config.ConfigService;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.ProviderBasedConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.fsm.ClusterFsmEvent.QuorumDisappeared;
import org.pragmatica.consensus.fsm.ClusterFsmEvent.QuorumEstablished;
import org.pragmatica.consensus.fsm.ClusterFsmEvent.Shutdown;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn3;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.statemachine.FsmState;
import org.pragmatica.statemachine.TransitionRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@SuppressWarnings("JBCT-RET-01")
public sealed interface NodeDeploymentState extends FsmState<NodeDeploymentState, ClusterFsmEvent> permits NodeDeploymentState.Dormant, NodeDeploymentState.Active, NodeDeploymentState.Leaving, NodeDeploymentState.Stopped {
    Logger LOG = LoggerFactory.getLogger(NodeDeploymentState.class);
    NodeDeploymentContext ctx();

    record Dormant(NodeDeploymentContext ctx, List<SuspendedSlice> suspendedSlices) implements NodeDeploymentState {
        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<NodeDeploymentState, ClusterFsmEvent> tx) {
            switch (event) {
                case QuorumEstablished _ -> tx.transitionTo(ctx.newActive(suspendedSlices));
                case Shutdown _ -> tx.transitionTo(ctx.stopped());
                default -> tx.ignore();
            }
        }
    }

    record Stopped(NodeDeploymentContext ctx) implements NodeDeploymentState {
        @Override
        public void onEntry() {
            LOG.debug("NodeDeploymentManager stopped");
        }

        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<NodeDeploymentState, ClusterFsmEvent> tx) {
            tx.ignore();
        }
    }

    record Active(NodeDeploymentContext ctx,
                  ConcurrentHashMap<SliceNodeKey, SliceDeployment> deployments,
                  ConfigNotificationManager configNotificationManager,
                  RoutingEpochAckTracker routingEpochAckTracker,
                  List<SuspendedSlice> pendingReactivation) implements NodeDeploymentState {
        private static final Logger log = LoggerFactory.getLogger(Active.class);
        private static final TimeSpan CONSENSUS_OPERATION_TIMEOUT = TimeSpan.timeSpan(30).seconds();
        private static final int CONSENSUS_MAX_RETRIES = 2;
        private static final int MAX_TRANSITION_RETRIES = 5;

        private static final Fn1<Cause, SliceNodeKey> CLEANUP_FAILED = Causes.forOneValue("Failed to cleanup slice %s during abrupt removal");

        private static final Fn1<Cause, SliceNodeKey> STATE_UPDATE_FAILED = Causes.forOneValue("Failed to update slice state for %s");

        private static final Fn1<Cause, SliceNodeKey> UNLOAD_FAILED = Causes.forOneValue("Failed to unload slice %s");

        private static final Fn1<Cause, String> SLICE_NOT_FOUND_FOR_ACTIVATION = Causes.forOneValue("Slice %s state is ACTIVATE but not found in SliceStore");

        private static final Fn1<Cause, String> SLICE_NOT_LOADED_FOR_REGISTRATION = Causes.forOneValue("Slice not loaded for registration: %s");

        @Override
        public void onEntry() {
            log.info("Node {} NodeDeploymentManager activated",
                     ctx.self().id());
            ctx.activeOnEntryCallback().onPresent(Runnable::run);
            seedEpochAckExpectationsFromKvStore();
            processPendingLoadCommands();
            if (!pendingReactivation.isEmpty()) {
                log.info("Node {} has {} suspended slices to reactivate",
                         ctx.self().id(),
                         pendingReactivation.size());
                reactivateSuspendedSlices(pendingReactivation);
            }
        }

        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<NodeDeploymentState, ClusterFsmEvent> tx) {
            switch (event) {
                case NodeArtifactPutReceived(ValuePut<NodeArtifactKey, NodeArtifactValue> valuePut) -> handleNodeArtifactPut(valuePut,
                                                                                                                             tx);
                case NodeArtifactRemoveReceived(ValueRemove<NodeArtifactKey, NodeArtifactValue> valueRemove) -> handleNodeArtifactRemove(valueRemove,
                                                                                                                                         tx);
                case NodeRoutesPutReceived(var valuePut) -> handleNodeRoutesPut(valuePut, tx);
                case LeavingRequested(DrainReason reason) -> tx.transitionTo(ctx.newLeaving(reason));
                case QuorumDisappeared _ -> handleQuorumDisappeared(tx);
                case Shutdown _ -> handleShutdown(tx);
                default -> tx.ignore();
            }
        }

        private void handleQuorumDisappeared(TransitionRequest<NodeDeploymentState, ClusterFsmEvent> tx) {
            var suspended = suspendSlices();

            log.info("Node {} NodeDeploymentManager deactivated with {} suspended slices",
                     ctx.self().id(),
                     suspended.size());
            tx.transitionTo(ctx.newDormantWithSuspended(suspended));
        }

        private void handleShutdown(TransitionRequest<NodeDeploymentState, ClusterFsmEvent> tx) {
            var suspended = suspendSlices();

            log.info("Node {} NodeDeploymentManager shutting down with {} suspended slices",
                     ctx.self().id(),
                     suspended.size());
            tx.transitionTo(ctx.stopped());
        }

        private void handleNodeArtifactPut(ValuePut<NodeArtifactKey, NodeArtifactValue> valuePut,
                                           TransitionRequest<NodeDeploymentState, ClusterFsmEvent> tx) {
            var key = valuePut.cause().key();

            if (!key.isForNode(ctx.self())) {
                tx.ignore();

                return;
            }

            tx.handle(() -> processNodeArtifactPut(valuePut));
        }

        private void processNodeArtifactPut(ValuePut<NodeArtifactKey, NodeArtifactValue> valuePut) {
            var key = valuePut.cause().key();
            var value = valuePut.cause().value();
            var sliceKey = SliceNodeKey.sliceNodeKey(key.artifact(), key.nodeId());
            var sliceNodeValue = SliceNodeValue.sliceNodeValue(value.state());

            log.debug("NodeArtifactKey put received for key: {}, state: {}", key, value.state());
            recordDeployment(sliceKey, sliceNodeValue);
            processStateTransition(sliceKey, value.state());
        }

        private void handleNodeArtifactRemove(ValueRemove<NodeArtifactKey, NodeArtifactValue> valueRemove,
                                              TransitionRequest<NodeDeploymentState, ClusterFsmEvent> tx) {
            var key = valueRemove.cause().key();

            if (!key.isForNode(ctx.self())) {
                tx.ignore();

                return;
            }

            var sliceKey = SliceNodeKey.sliceNodeKey(key.artifact(), key.nodeId());

            tx.handle(() -> handleSliceValueRemove(sliceKey));
        }

        private void handleNodeRoutesPut(ValuePut<AetherKey.NodeRoutesKey, org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue> valuePut,
                                         TransitionRequest<NodeDeploymentState, ClusterFsmEvent> tx) {
            tx.handle(() -> processNodeRoutesPut(valuePut));
        }

        private void processNodeRoutesPut(ValuePut<AetherKey.NodeRoutesKey, org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue> valuePut) {
            var key = valuePut.cause().key();
            var value = valuePut.cause().value();
            var sliceKey = SliceNodeKey.sliceNodeKey(key.artifact(), ctx.self());

            routingEpochAckTracker.observeAck(sliceKey,
                                              key.nodeId(),
                                              value.observedCoreEpoch())
                                  .onPresent(this::fastTransitionToActive);
        }

        @Contract
        public void processPendingLoadCommands() {
            ctx.kvStore().forEach(NodeArtifactKey.class, NodeArtifactValue.class, this::processPendingLoadEntry);
        }

        private void processPendingLoadEntry(NodeArtifactKey key, NodeArtifactValue value) {
            if (!key.isForNode(ctx.self()) || value.state() != SliceState.LOAD) {
                return;
            }

            log.info("Node {} found pending LOAD command for {}, processing",
                     ctx.self().id(),
                     key.artifact());
            var sliceKey = SliceNodeKey.sliceNodeKey(key.artifact(), key.nodeId());

            recordDeployment(sliceKey,
                             SliceNodeValue.sliceNodeValue(value.state()));
            processStateTransition(sliceKey, value.state());
        }

        private Option<SliceStore.LoadedSlice> findLoadedSlice(Artifact artifact) {
            return Option.from(ctx.sliceStore()
                                  .loaded()
                                  .stream()
                                  .filter(ls -> ls.artifact()
                                                  .equals(artifact))
                                  .findFirst());
        }

        /// Look up the slice-composite for `artifact` and wrap it as a `ConfigService`.
        ///
        /// Returns `Option.none()` when the slice has no composite (e.g. node was started
        /// without a node-composite, slice isn't loaded yet, or load failed). Callers must
        /// fall back to global `ConfigService.instance()` or treat as missing config.
        private Option<ConfigService> sliceConfigService(Artifact artifact) {
            return ctx.sliceStore()
                      .sliceComposite(artifact)
                      .map(Active::wrapAsConfigService);
        }

        private static ConfigService wrapAsConfigService(ConfigurationProvider provider) {
            return ProviderBasedConfigService.providerBasedConfigService(provider);
        }

        private void fastTransitionToActive(SliceNodeKey sliceKey) {
            var current = Option.option(deployments.get(sliceKey)).map(SliceDeployment::state);

            if (!current.map(state -> state == SliceState.ROUTING).or(false)) {
                return;
            }

            log.debug("Cross-node ack threshold reached for {} — fast-transitioning ROUTING → ACTIVE",
                      sliceKey.artifact());
            transitionTo(sliceKey, SliceState.ACTIVE);
        }

        private void handleSliceValueRemove(SliceNodeKey sliceKey) {
            log.debug("ValueRemove received for key: {}", sliceKey);
            var deployment = Option.option(deployments.remove(sliceKey));

            routingEpochAckTracker.clear(sliceKey);
            if (shouldForceCleanup(deployment)) {
                forceCleanupSlice(sliceKey);
            }
        }

        private void recordDeployment(SliceNodeKey sliceKey, SliceNodeValue sliceNodeValue) {
            var state = sliceNodeValue.state();
            var timestamp = ctx.nowMs();
            var previousDeployment = Option.option(deployments.get(sliceKey));
            var previousState = previousDeployment.map(SliceDeployment::state);
            var deployment = SliceDeployment.sliceDeployment(sliceKey, state, timestamp);

            deployments.put(sliceKey, deployment);
            var effectiveFromState = previousState.or(state);

            ctx.router()
               .route(StateTransition.stateTransition(sliceKey.artifact(),
                                                      ctx.self(),
                                                      effectiveFromState,
                                                      state,
                                                      timestamp));
            if (state == SliceState.FAILED) {
                var errorMessage = sliceNodeValue.failureReason().or("Unknown failure");

                previousState.onPresent(prevState -> ctx.router()
                                                        .route(DeploymentFailed.deploymentFailed(sliceKey.artifact(),
                                                                                                 ctx.self(),
                                                                                                 prevState,
                                                                                                 errorMessage,
                                                                                                 timestamp)));
            }
        }

        private boolean shouldForceCleanup(Option<SliceDeployment> deployment) {
            return deployment.map(d -> d.state() == SliceState.ACTIVE)
                             .or(false);
        }

        private void forceCleanupSlice(SliceNodeKey sliceKey) {
            unpublishEndpoints(sliceKey).flatMap(this::unpublishTopicSubscriptions)
                              .flatMap(this::unpublishStreamSubscriptions)
                              .flatMap(key -> releaseStreamReferences(key).map(_ -> key))
                              .flatMap(this::unpublishScheduledTasks)
                              .flatMap(this::unpublishHttpRoutes)
                              .withSuccess(this::unregisterSliceFromInvocation)
                              .flatMap(key -> ctx.sliceStore()
                                                 .deactivateSlice(key.artifact()))
                              .flatMap(_ -> ctx.sliceStore()
                                               .unloadSlice(sliceKey.artifact()))
                              .onFailure(cause -> logCleanupFailure(sliceKey, cause));
        }

        private void logCleanupFailure(SliceNodeKey sliceKey, Cause cause) {
            logError(CLEANUP_FAILED, sliceKey, cause);
        }

        @Contract
        public void processStateTransition(SliceNodeKey sliceKey, SliceState state) {
            switch (state) {
                case LOAD -> handleLoading(sliceKey);
                case LOADING -> {}
                case LOADED -> handleLoaded(sliceKey);
                case ACTIVATE -> handleActivating(sliceKey);
                case ACTIVATING -> scheduleActivationRemediation(sliceKey);
                case ROUTING -> {}
                case ACTIVE -> handleActive(sliceKey);
                case DEACTIVATE -> handleDeactivating(sliceKey);
                case DEACTIVATING -> {}
                case FAILED -> handleFailed(sliceKey);
                case UNLOAD -> handleUnloading(sliceKey);
                case UNLOADING -> {}
            }
        }

        private void handleLoading(SliceNodeKey sliceKey) {
            transitionTo(sliceKey, SliceState.LOADING).flatMap(this::loadSliceWithTimeout)
                        .flatMap(key -> transitionTo(key, SliceState.LOADED))
                        .withFailure(cause -> handleLoadingFailure(sliceKey, cause));
        }

        private Promise<SliceNodeKey> loadSliceWithTimeout(SliceNodeKey sliceKey) {
            return ctx.configuration()
                      .timeoutFor(SliceState.LOADING)
                      .async()
                      .flatMap(timeout -> ctx.sliceStore()
                                             .loadSlice(sliceKey.artifact())
                                             .timeout(timeout))
                      .map(_ -> sliceKey);
        }

        private void handleLoadingFailure(SliceNodeKey sliceKey, Cause cause) {
            log.error("Failed to load slice {}: {}", sliceKey.artifact(), cause.message());
            transitionToFailed(sliceKey, cause);
        }

        private void handleLoaded(SliceNodeKey sliceKey) {
            log.debug("Slice {} loaded, awaiting activation", sliceKey.artifact());
        }

        private void handleActivating(SliceNodeKey sliceKey) {
            findLoadedSlice(sliceKey.artifact()).onEmpty(() -> handleSliceNotFoundForActivation(sliceKey))
                           .onPresent(_ -> performActivation(sliceKey));
        }

        private void handleSliceNotFoundForActivation(SliceNodeKey sliceKey) {
            var cause = SLICE_NOT_FOUND_FOR_ACTIVATION.apply(sliceKey.artifact().asString());

            log.error("Slice {} state is ACTIVATE but not found in SliceStore", sliceKey.artifact());
            transitionToFailed(sliceKey, cause);
        }

        private void performActivation(SliceNodeKey sliceKey) {
            transitionTo(sliceKey, SliceState.ACTIVATING).flatMap(this::activateSliceWithTimeout)
                        .flatMap(this::registerSliceForInvocation)
                        .flatMap(this::publishTopicSubscriptions)
                        .flatMap(this::publishStreamSubscriptions)
                        .flatMap(this::publishScheduledTasks)
                        .flatMap(this::registerAndNotifyConfig)
                        .flatMap(this::publishRoutesIfPresent)
                        .flatMap(this::transitionToActiveWithStreamRefs)
                        .flatMap(this::publishEndpoints)
                        .timeout(ctx.activationChainTimeout())
                        .withFailure(cause -> handleActivationFailure(sliceKey, cause));
        }

        private Promise<SliceNodeKey> publishRoutesIfPresent(SliceNodeKey sliceKey) {
            if (!sliceHasHttpRoutes(sliceKey.artifact())) {
                return Promise.success(sliceKey);
            }

            return transitionTo(sliceKey, SliceState.ROUTING).withSuccess(this::registerEpochAckExpectation)
                               .flatMap(key -> publishHttpRoutes(key).map(_ -> key));
        }

        private void registerEpochAckExpectation(SliceNodeKey sliceKey) {
            var targets = collectTargetNodesForArtifact(sliceKey);

            if (targets.isEmpty()) {
                return;
            }

            routingEpochAckTracker.registerExpectation(sliceKey, Epoch.ZERO, targets);
            scheduleRoutingRemediation(sliceKey);
        }

        /// #325 — node-local ROUTING-timeout remediation arm. A slice that has published its routes
        /// locally and entered ROUTING transitions to ACTIVE only on cross-node epoch-acks
        /// ([#fastTransitionToActive]). On a freshly reformed cluster (post-wipe S20 redeploy,
        /// leadership just handed off, route-table ownership in flux) those acks can be dropped or
        /// arrive epoch-stale, and the `case ROUTING -> {}` observer is a no-op — so the slice wedges
        /// in ROUTING indefinitely (the 2/3-active signature). This arm schedules a single one-shot
        /// at [`SliceState#ROUTING`]'s own timeout; if the slice is STILL ROUTING when it fires, the
        /// routes are already published and serving locally, so the cross-node ack is a confirmation
        /// optimization — NOT a local-serving correctness gate. Force ROUTING → ACTIVE rather than
        /// leaving the cluster-side stuck-remediator to UNLOAD-and-redeploy (which re-enters the same
        /// ack wait and can wedge again). Idempotent: the fire is guarded on the slice still being in
        /// ROUTING, and an ack that lands first clears the tracker and transitions to ACTIVE, so the
        /// later timeout no-ops.
        @Contract
        private void scheduleRoutingRemediation(SliceNodeKey sliceKey) {
            SliceState.ROUTING.timeout().onPresent(timeout -> SharedScheduler.schedule(() -> remediateStuckRouting(sliceKey),
                                                                                       timeout));
        }

        /// ROUTING-timeout fire: if the slice is still ROUTING (no ack-driven transition happened),
        /// force it to ACTIVE and clear the now-moot ack expectation. No-op when the slice already
        /// left ROUTING (acked through, failed, or was removed). Package-private so the
        /// remediation contract is unit-testable without waiting out the real routing timeout
        /// (mirrors the package-private [#routingEpochAckTracker] test accessor on this record).
        @Contract
        void remediateStuckRouting(SliceNodeKey sliceKey) {
            var current = Option.option(deployments.get(sliceKey)).map(SliceDeployment::state);

            if (!current.map(state -> state == SliceState.ROUTING).or(false)) {
                return;
            }

            log.warn("Slice {} still ROUTING after the routing timeout — cross-node acks not received; "
                    + "force-transitioning ROUTING → ACTIVE (routes are published and serving locally)",
                     sliceKey.artifact());
            routingEpochAckTracker.clear(sliceKey);
            transitionTo(sliceKey, SliceState.ACTIVE);
        }

        /// #601 — ACTIVATING had no node-local remediation at all, only a `case ACTIVATING -> {}` no-op
        /// observer: structurally the same gap #325 closed for ROUTING. A node whose activation stalled
        /// had nothing local to recover or report it, leaving only the leader-side stuck-remediator —
        /// which judges by a projection and, until `d9b37e180`, force-UNLOADed a slice that had been
        /// serving traffic 35 seconds earlier.
        ///
        /// Mirrors [#scheduleRoutingRemediation], with ONE deliberate difference: forcing ACTIVE is gated
        /// on positive local proof that the slice can actually serve.
        @Contract
        private void scheduleActivationRemediation(SliceNodeKey sliceKey) {
            SliceState.ACTIVATING.timeout().onPresent(timeout -> SharedScheduler.schedule(() -> remediateStuckActivating(sliceKey),
                                                                                          timeout));
        }

        /// ACTIVATING-timeout fire. No-op when the slice has already left ACTIVATING.
        ///
        /// THE PROOF MATTERS MORE THAN THE TRANSITION. `findLoadedSlice` is NOT sufficient evidence — the
        /// activation chain loads the slice at `activateSliceWithTimeout` and only registers it for
        /// invocation one step later, so a chain stalled in between leaves the slice LOADED but unable to
        /// serve. Forcing ACTIVE on that would manufacture a phantom-ACTIVE slice and the cluster would
        /// route traffic to something that cannot answer — strictly worse than leaving it stuck. The gate
        /// is therefore `invocationHandler().localSlice(...)`, which is present exactly when the bridge is
        /// registered and calls can be served. That was true in the observed incident: the chain had run
        /// past registration and published endpoints, and the node was serving `publish` calls.
        ///
        /// When the slice is NOT serving this deliberately does nothing beyond a loud warning. The
        /// activation chain carries its own longer `activationChainTimeout`, so failing the slice here
        /// would preempt a chain that may still legitimately complete; the chain's own timeout and the
        /// (now KV-confirming) cluster remediator remain the backstops.
        @Contract
        void remediateStuckActivating(SliceNodeKey sliceKey) {
            var stillActivating = Option.option(deployments.get(sliceKey))
                                        .map(SliceDeployment::state)
                                        .map(state -> state == SliceState.ACTIVATING)
                                        .or(false);

            if (!stillActivating) {
                return;
            }

            ctx.invocationHandler()
               .localSlice(sliceKey.artifact())
               .onPresent(_ -> forceActivatingToActive(sliceKey))
               .onEmpty(() -> log.warn("Slice {} still ACTIVATING after the activation timeout and is NOT registered for "
                                      + "invocation — NOT forcing ACTIVE, because a slice the cluster routes to but which "
                                      + "cannot serve is worse than one stuck activating. Leaving it to the activation "
                                      + "chain's own timeout and the cluster-side remediator.",
                                       sliceKey.artifact()));
        }

        @Contract
        private void forceActivatingToActive(SliceNodeKey sliceKey) {
            log.warn("Slice {} still ACTIVATING after the activation timeout but IS registered for invocation and serving "
                    + "locally — force-transitioning ACTIVATING → ACTIVE. The remaining chain steps are publication and "
                    + "confirmation, not local-serving correctness gates.",
                     sliceKey.artifact());
            transitionTo(sliceKey, SliceState.ACTIVE);
        }

        private Set<NodeId> collectTargetNodesForArtifact(SliceNodeKey sliceKey) {
            var targets = new HashSet<NodeId>();

            ctx.kvStore()
               .forEach(NodeArtifactKey.class,
                        NodeArtifactValue.class,
                        (k, v) -> collectTargetIfMatches(targets, sliceKey, k, v));

            return Set.copyOf(targets);
        }

        private void collectTargetIfMatches(Set<NodeId> targets,
                                            SliceNodeKey sliceKey,
                                            NodeArtifactKey key,
                                            NodeArtifactValue value) {
            if (!key.artifact().equals(sliceKey.artifact())) {
                return;
            }

            if (key.nodeId().equals(ctx.self())) {
                return;
            }

            if (value.state() == SliceState.FAILED || value.state() == SliceState.UNLOAD || value.state() == SliceState.UNLOADING) {
                return;
            }

            targets.add(key.nodeId());
        }

        private void seedEpochAckExpectationsFromKvStore() {
            ctx.kvStore().forEach(NodeRoutesKey.class, NodeRoutesValue.class, this::seedEpochAckExpectationForRoute);
        }

        private void seedEpochAckExpectationForRoute(NodeRoutesKey key, NodeRoutesValue value) {
            if (!key.nodeId().equals(ctx.self()) || value.routes().isEmpty()) {
                return;
            }

            var sliceKey = SliceNodeKey.sliceNodeKey(key.artifact(), key.nodeId());
            var targets = collectTargetNodesForArtifact(sliceKey);

            if (targets.isEmpty()) {
                return;
            }

            log.debug("Seeding epoch-ack expectation for {} from KVStore ({} targets)",
                      sliceKey.artifact(),
                      targets.size());
            // Epoch.ZERO matches the live registration path above: the stored observedCoreEpoch is
            // STRICTER than live (an acker whose KV lags fails the tracker's isAtLeast check and the
            // seeded slice wedges in ROUTING until the stuck-remediator unloads it).
            routingEpochAckTracker.registerExpectation(sliceKey, Epoch.ZERO, targets);
            // #325 — arm the node-local ROUTING timeout on the rejoin/boot seed path too: this is the
            // exact path the comment above flagged as wedge-prone (a lagging or already-moved-on acker
            // never satisfies the expectation). The arm force-progresses the slice to ACTIVE instead
            // of leaving it stuck for the cluster-side unload-and-redeploy loop. Guarded on the slice
            // being in ROUTING when the timeout fires, so a seed for an already-ACTIVE route no-ops.
            scheduleRoutingRemediation(sliceKey);
        }

        private boolean sliceHasHttpRoutes(Artifact artifact) {
            return ctx.httpRoutePublisher()
                      .flatMap(publisher -> findLoadedSlice(artifact).map(ls -> publisher.hasRoutes(ls.slice()
                                                                                                      .getClass()
                                                                                                      .getClassLoader(),
                                                                                                    ls.slice())))
                      .or(false);
        }

        private Promise<SliceNodeKey> activateSliceWithTimeout(SliceNodeKey sliceKey) {
            return ctx.configuration()
                      .timeoutFor(SliceState.ACTIVATING)
                      .async()
                      .flatMap(timeout -> ctx.sliceStore()
                                             .activateSlice(sliceKey.artifact())
                                             .timeout(timeout))
                      .map(_ -> sliceKey);
        }

        private void handleActivationFailure(SliceNodeKey sliceKey, Cause cause) {
            log.error("Activation failed for {}: {}", sliceKey.artifact(), cause.message());
            unregisterSliceFromInvocation(sliceKey);
            unpublishEndpoints(sliceKey).flatMap(this::unpublishHttpRoutes)
                              .flatMap(this::unpublishTopicSubscriptions)
                              .flatMap(this::unpublishStreamSubscriptions)
                              .flatMap(this::unpublishScheduledTasks)
                              .withFailure(partialCause -> log.warn("Partial unpublish during activation-failure cleanup for {}: {}",
                                                                    sliceKey.artifact(),
                                                                    partialCause.message()))
                              .withResult(_ -> transitionToFailed(sliceKey, cause));
        }

        private void handleActive(SliceNodeKey sliceKey) {
            if (findLoadedSlice(sliceKey.artifact()).isEmpty()) {
                redeployClaimedActiveSlice(sliceKey);

                return;
            }

            routingEpochAckTracker.clear(sliceKey);
            ctx.router().route(DeploymentCompleted.deploymentCompleted(sliceKey.artifact(), ctx.self(), ctx.nowMs()));
        }

        /// M5 boot/rejoin KV-convergence (cluster-topology-overhaul §5.9): the KV store claims
        /// this self-instance ACTIVE but the slice is not loaded locally — a wiped node
        /// (`down -v`) or local-vs-KV divergence. Converge to the KV desired state by re-running
        /// the standard deploy chain: LOADING → load → LOADED → ACTIVATE (the ACTIVATE branch
        /// drives the existing `performActivation` chain back to ACTIVE). Phantom-ACTIVE entries
        /// are impossible by construction — the claim is healed, not detected. Reached via the
        /// post-activation action-log replay of pre-activation `NodeArtifactKey` events (§5.8)
        /// or any live ACTIVE put observed without a locally loaded slice; a load failure lands
        /// in FAILED through the regular `handleLoadingFailure` path (CDM-visible).
        private void redeployClaimedActiveSlice(SliceNodeKey sliceKey) {
            log.info("Node {} KV claims ACTIVE for {} but the slice is not loaded locally — redeploying (KV convergence)",
                     ctx.self().id(),
                     sliceKey.artifact());
            transitionTo(sliceKey, SliceState.LOADING).flatMap(this::loadSliceWithTimeout)
                        .flatMap(key -> transitionTo(key, SliceState.LOADED))
                        .onSuccess(key -> processStateTransition(key, SliceState.ACTIVATE))
                        .onFailure(cause -> handleLoadingFailure(sliceKey, cause));
        }

        private Promise<Unit> publishHttpRoutes(SliceNodeKey sliceKey) {
            var artifact = sliceKey.artifact();

            log.debug("publishHttpRoutes called for {} - httpRoutePublisher.isPresent={}, sliceInvokerFacade.isPresent={}",
                      artifact,
                      ctx.httpRoutePublisher().isPresent(),
                      ctx.sliceInvokerFacade().isPresent());

            return ctx.httpRoutePublisher()
                      .flatMap(publisher -> ctx.sliceInvokerFacade()
                                               .flatMap(facade -> findLoadedSlice(artifact).map(ls -> doPublishHttpRoutes(artifact,
                                                                                                                          publisher,
                                                                                                                          facade,
                                                                                                                          ls))))
                      .or(Promise.unitPromise());
        }

        private Promise<Unit> doPublishHttpRoutes(Artifact artifact,
                                                  HttpRoutePublisher publisher,
                                                  SliceInvokerFacade facade,
                                                  SliceStore.LoadedSlice ls) {
            var classLoader = ls.slice().getClass().getClassLoader();

            log.debug("Publishing HTTP routes for {} using classLoader={}",
                      artifact,
                      classLoader.getClass().getName());

            return publisher.publishRoutes(artifact,
                                           classLoader,
                                           ls.slice(),
                                           facade)
                            .onFailure(cause -> log.warn("Failed to publish HTTP routes for {}: {}",
                                                         artifact,
                                                         cause.message()));
        }

        private Promise<SliceNodeKey> registerSliceForInvocation(SliceNodeKey sliceKey) {
            var artifact = sliceKey.artifact();

            return findLoadedSlice(artifact).toResult(SLICE_NOT_LOADED_FOR_REGISTRATION.apply(artifact.asString()))
                                  .flatMap(ls -> registerSliceBridge(artifact,
                                                                     ls.slice()))
                                  .async()
                                  .map(_ -> sliceKey);
        }

        private Result<Unit> registerSliceBridge(Artifact artifact, Slice slice) {
            var sliceCodec = slice.codec(ctx.nodeCodec());
            var sliceBridge = DefaultSliceBridge.defaultSliceBridge(artifact, slice, sliceCodec);

            ctx.invocationHandler().registerSlice(artifact, sliceBridge);
            log.debug("Registered slice {} for invocation", artifact);

            return Result.unitResult();
        }

        private void unregisterSliceFromInvocation(SliceNodeKey sliceKey) {
            var artifact = sliceKey.artifact();

            ctx.invocationHandler().unregisterSlice(artifact);
            log.debug("Unregistered slice {} from invocation", artifact);
        }

        private Promise<Unit> publishEndpoints(SliceNodeKey sliceKey) {
            var artifact = sliceKey.artifact();

            return findLoadedSlice(artifact).map(ls -> publishEndpointsForSlice(artifact,
                                                                                ls.slice()))
                                  .or(Promise.unitPromise());
        }

        private Promise<Unit> publishEndpointsForSlice(Artifact artifact, Slice slice) {
            var methods = slice.methods();

            if (methods.isEmpty()) {
                return Promise.unitPromise();
            }

            int instanceNumber = Math.abs(ctx.self().id().hashCode());
            var methodNames = methods.stream().map(m -> m.name()
                                                         .name()).toList();
            var nodeArtifactKey = NodeArtifactKey.nodeArtifactKey(ctx.self(), artifact);
            var nodeArtifactValue = NodeArtifactValue.activeNodeArtifactValue(instanceNumber, methodNames);
            KVCommand<AetherKey> command = new KVCommand.Put<>(nodeArtifactKey, nodeArtifactValue);

            return applyWithRetry(List.of(command),
                                  0).onSuccess(_ -> log.debug("Published {} endpoints for slice {}",
                                                              methods.size(),
                                                              artifact))
                                 .onFailure(cause -> log.error("Failed to publish endpoints for {}: {}",
                                                               artifact,
                                                               cause.message()));
        }

        private void handleDeactivating(SliceNodeKey sliceKey) {
            findLoadedSlice(sliceKey.artifact()).onEmpty(() -> handleSliceNotFoundForDeactivation(sliceKey))
                           .onPresent(_ -> performDeactivation(sliceKey));
        }

        private void handleSliceNotFoundForDeactivation(SliceNodeKey sliceKey) {
            log.warn("Slice {} not found in store during deactivation, transitioning to LOADED", sliceKey.artifact());
            transitionTo(sliceKey, SliceState.LOADED);
        }

        private void performDeactivation(SliceNodeKey sliceKey) {
            transitionTo(sliceKey, SliceState.DEACTIVATING).flatMap(this::unpublishEndpoints)
                        .flatMap(this::unpublishTopicSubscriptions)
                        .flatMap(this::unpublishStreamSubscriptions)
                        .flatMap(key -> releaseStreamReferences(key).map(_ -> key))
                        .flatMap(this::unpublishScheduledTasks)
                        .flatMap(this::unpublishHttpRoutes)
                        .withSuccess(this::unregisterSliceFromInvocation)
                        .withSuccess(this::unregisterConfig)
                        .flatMap(this::deactivateSliceWithTimeout)
                        .flatMap(key -> transitionTo(key, SliceState.LOADED))
                        .withFailure(cause -> handleDeactivationFailure(sliceKey, cause));
        }

        private Promise<SliceNodeKey> deactivateSliceWithTimeout(SliceNodeKey sliceKey) {
            return ctx.configuration()
                      .timeoutFor(SliceState.DEACTIVATING)
                      .async()
                      .flatMap(timeout -> ctx.sliceStore()
                                             .deactivateSlice(sliceKey.artifact())
                                             .timeout(timeout))
                      .map(_ -> sliceKey);
        }

        private void handleDeactivationFailure(SliceNodeKey sliceKey, Cause cause) {
            log.error("Deactivation failed for {}: {}", sliceKey.artifact(), cause.message());
            transitionToFailed(sliceKey, cause);
        }

        private Promise<SliceNodeKey> unpublishHttpRoutes(SliceNodeKey sliceKey) {
            return ctx.httpRoutePublisher()
                      .map(publisher -> publisher.unpublishRoutes(sliceKey.artifact()))
                      .or(Promise.unitPromise())
                      .map(_ -> sliceKey);
        }

        private Promise<SliceNodeKey> unpublishEndpoints(SliceNodeKey sliceKey) {
            var artifact = sliceKey.artifact();

            return findLoadedSlice(artifact).map(ls -> unpublishEndpointsForSlice(artifact,
                                                                                  ls.slice()))
                                  .or(Promise.unitPromise())
                                  .map(_ -> sliceKey);
        }

        private Promise<Unit> unpublishEndpointsForSlice(Artifact artifact, Slice slice) {
            var methods = slice.methods();

            if (methods.isEmpty()) {
                return Promise.unitPromise();
            }

            var nodeArtifactKey = NodeArtifactKey.nodeArtifactKey(ctx.self(), artifact);
            var nodeArtifactValue = NodeArtifactValue.nodeArtifactValue(SliceState.ACTIVE);
            KVCommand<AetherKey> command = new KVCommand.Put<>(nodeArtifactKey, nodeArtifactValue);

            return applyWithRetry(List.of(command),
                                  0).onSuccess(_ -> log.debug("Unpublished {} endpoints for slice {}",
                                                              methods.size(),
                                                              artifact))
                                 .onFailure(cause -> log.error("Failed to unpublish endpoints for {}: {}",
                                                               artifact,
                                                               cause.message()));
        }

        private Promise<SliceNodeKey> publishTopicSubscriptions(SliceNodeKey sliceKey) {
            var artifact = sliceKey.artifact();

            return findLoadedSlice(artifact).map(ls -> doPublishTopicSubscriptions(artifact,
                                                                                   ls.slice()))
                                  .or(Promise.unitPromise())
                                  .map(_ -> sliceKey);
        }

        private Promise<Unit> doPublishTopicSubscriptions(Artifact artifact, Slice slice) {
            var entries = readSubscriptionsFromManifest(artifact, slice);

            if (entries.isEmpty()) {
                return Promise.unitPromise();
            }

            var commands = entries.stream()
                                  .<KVCommand<AetherKey>> map(entry -> buildTopicSubscriptionPutCommand(artifact, entry))
                                  .toList();

            return applyWithRetry(commands, 0).onSuccess(_ -> log.debug("Published {} topic subscriptions for {}",
                                                                        entries.size(),
                                                                        artifact))
                                 .onFailure(cause -> log.error("Failed to publish topic subscriptions for {}: {}",
                                                               artifact,
                                                               cause.message()));
        }

        private KVCommand<AetherKey> buildTopicSubscriptionPutCommand(Artifact artifact,
                                                                      SubscriptionManifestEntry entry) {
            var key = TopicSubscriptionKey.topicSubscriptionKey(entry.address(), artifact, entry.methodName());
            var value = TopicSubscriptionValue.topicSubscriptionValue(ctx.self());

            return new KVCommand.Put<>(key, value);
        }

        private Promise<SliceNodeKey> unpublishTopicSubscriptions(SliceNodeKey sliceKey) {
            var artifact = sliceKey.artifact();

            return findLoadedSlice(artifact).map(ls -> doUnpublishTopicSubscriptions(artifact,
                                                                                     ls.slice()))
                                  .or(Promise.unitPromise())
                                  .map(_ -> sliceKey);
        }

        private Promise<Unit> doUnpublishTopicSubscriptions(Artifact artifact, Slice slice) {
            var entries = readSubscriptionsFromManifest(artifact, slice);

            if (entries.isEmpty()) {
                return Promise.unitPromise();
            }

            var commands = entries.stream()
                                  .<KVCommand<AetherKey>> map(entry -> buildTopicSubscriptionRemoveCommand(artifact,
                                                                                                           entry))
                                  .toList();

            return applyWithRetry(commands, 0).onSuccess(_ -> log.debug("Unpublished {} topic subscriptions for {}",
                                                                        entries.size(),
                                                                        artifact))
                                 .onFailure(cause -> log.error("Failed to unpublish topic subscriptions for {}: {}",
                                                               artifact,
                                                               cause.message()));
        }

        private KVCommand<AetherKey> buildTopicSubscriptionRemoveCommand(Artifact artifact,
                                                                         SubscriptionManifestEntry entry) {
            var key = TopicSubscriptionKey.topicSubscriptionKey(entry.address(), artifact, entry.methodName());

            return new KVCommand.Remove<>(key);
        }

        private record SubscriptionManifestEntry(ResourceAddress address, MethodName methodName) {}

        private record ScheduledTaskManifestEntry(String configSection, MethodName methodName) {}

        private record ReactiveManifestEntry(String category,
                                             String method,
                                             String config,
                                             Properties manifest,
                                             String prefix) {
            String getProperty(String key) {
                return manifest.getProperty(prefix + key, "");
            }
        }

        @SuppressWarnings("JBCT-EX-01")
        private List<ReactiveManifestEntry> readReactiveBindingsFromManifest(Slice slice) {
            var result = new ArrayList<ReactiveManifestEntry>();
            var classLoader = slice.getClass().getClassLoader();

            for (var iface : slice.getClass().getInterfaces()) {
                if (iface == Slice.class) {
                    continue;
                }

                var manifestPath = "META-INF/slice/" + iface.getSimpleName() + ".manifest";

                readReactiveEntriesFromManifest(classLoader, manifestPath, result);
            }

            return result;
        }

        @SuppressWarnings("JBCT-EX-01")
        private void readReactiveEntriesFromManifest(ClassLoader classLoader,
                                                     String manifestPath,
                                                     List<ReactiveManifestEntry> result) {
            try (var is = classLoader.getResourceAsStream(manifestPath)) {
                if (is == null) {
                    return;
                }

                var props = new Properties();

                props.load(is);
                var count = Integer.parseInt(props.getProperty("reactive.count", "0"));

                for (int i = 0; i < count; i++) {
                    var prefix = "reactive." + i + ".";
                    var category = props.getProperty(prefix + "category", "");
                    var method = props.getProperty(prefix + "method", "");
                    var config = props.getProperty(prefix + "config", "");

                    result.add(new ReactiveManifestEntry(category, method, config, props, prefix));
                }
            } catch (Exception e) {
                log.debug("Could not read reactive manifest {}: {}", manifestPath, e.getMessage());
            }
        }

        @SuppressWarnings("JBCT-EX-01")
        private List<SubscriptionManifestEntry> readSubscriptionsFromManifest(Artifact artifact, Slice slice) {
            var reactive = readReactiveBindingsFromManifest(slice);
            var result = new ArrayList<SubscriptionManifestEntry>();

            for (var entry : reactive) {
                if ("subscription".equals(entry.category())) {
                    resolveSubscriptionAddress(artifact, entry).flatMap(address -> MethodName.methodName(entry.method()).map(method -> new SubscriptionManifestEntry(address,
                                                                                                                                                                     method)))
                                              .option()
                                              .onPresent(result::add);
                }
            }

            return result;
        }

        /// Resolve a subscription's topic address, preferring the manifest-generated `topicName`
        /// derived from the single-source `Topic<T>` constant (#396). When present, the address is
        /// resolved directly from that name via [TopicAddressResolver] — the author no longer writes a
        /// resources.toml `topic_name`, so the legacy [TopicConfig] section lookup would fail. Falls
        /// back to the legacy `config`-section lookup for subscriptions declared the old way.
        private Result<ResourceAddress> resolveSubscriptionAddress(Artifact artifact, ReactiveManifestEntry entry) {
            var manifestTopicName = entry.getProperty("topicName");

            return manifestTopicName.isEmpty()
                   ? resolveTopicAddress(artifact, entry.config())
                   : TopicAddressResolver.resolve(artifact, manifestTopicName);
        }

        /// Resolve a subscription's declared topic config to a canonical [ResourceAddress].
        ///
        /// Reads the declared `topicName` via [TopicConfig] and delegates the namespace derivation
        /// to [TopicAddressResolver] — the single source of truth shared with the publisher side
        /// ([org.pragmatica.aether.invoke.PublisherFactory]) so a co-deployed pub/sub pair always
        /// resolves the same declared topic to the same address.
        private Result<ResourceAddress> resolveTopicAddress(Artifact artifact, String configSection) {
            return sliceConfigService(artifact).orElse(ConfigService::instance)
                                     .toResult(Causes.cause("ConfigService not available for topic resolution"))
                                     .flatMap(svc -> svc.config(configSection, TopicConfig.class))
                                     .flatMap(config -> resolveTopicAddress(artifact, config));
        }

        private Result<ResourceAddress> resolveTopicAddress(Artifact artifact, TopicConfig config) {
            return TopicAddressResolver.resolve(artifact, config.topicName());
        }

        private Promise<SliceNodeKey> publishScheduledTasks(SliceNodeKey sliceKey) {
            var artifact = sliceKey.artifact();

            return findLoadedSlice(artifact).map(ls -> doPublishScheduledTasks(artifact,
                                                                               ls.slice()))
                                  .or(Promise.unitPromise())
                                  .map(_ -> sliceKey);
        }

        /// The interval/cron STRING is node-local config, resolved here — at activation time — not
        /// visible to `BlueprintService.validate` (blueprint DSL parsing happens strictly earlier and
        /// has no access to per-node `ScheduleConfig`). This is therefore the earliest point an invalid
        /// schedule string CAN be rejected. Rejection must be observable, not a log line: an invalid
        /// entry fails [#buildValidatedScheduledTaskPutCommand] with a named [Cause]; [Result#allOf]
        /// aggregates every invalid entry into one composite cause; the failed [Promise] propagates
        /// through [#performActivation]'s `.withFailure` into [#handleActivationFailure] /
        /// `transitionToFailed`, landing in [NodeArtifactValue#failureReason] — which
        /// `ClusterEventAggregator.handleDeploymentFailed` already surfaces as a WARNING-severity
        /// `DeploymentFailed` event naming the task, the offending string, and the parser's message.
        private Promise<Unit> doPublishScheduledTasks(Artifact artifact, Slice slice) {
            var entries = readScheduledTasksFromManifest(slice);

            if (entries.isEmpty()) {
                return Promise.unitPromise();
            }

            var results = new ArrayList<Result<KVCommand<AetherKey>>>();

            for (var entry : entries) {
                resolveScheduleConfig(artifact, entry.configSection()).onPresent(config -> results.add(buildValidatedScheduledTaskPutCommand(artifact,
                                                                                                                                             entry,
                                                                                                                                             config)));
            }

            if (results.isEmpty()) {
                return Promise.unitPromise();
            }

            return Promise.resolved(Result.allOf(results))
                          .flatMap(commands -> applyWithRetry(commands, 0))
                          .onSuccess(_ -> log.debug("Published {} scheduled tasks for {}",
                                                    results.size(),
                                                    artifact))
                          .onFailure(cause -> log.error("Failed to publish scheduled tasks for {}: {}",
                                                        artifact,
                                                        cause.message()));
        }

        private static final Fn3<Cause, String, String, String> INVALID_SCHEDULE_STRING = Causes.forThreeValues("Scheduled task '%s' has an invalid schedule '%s': %s");

        /// Validates the schedule string BEFORE building the [ScheduledTaskValue] — an invalid
        /// interval/cron string must never reach the KV Put. See [#doPublishScheduledTasks] for why
        /// this is the earliest real validation gate and how the rejection reaches the operator.
        private Result<KVCommand<AetherKey>> buildValidatedScheduledTaskPutCommand(Artifact artifact,
                                                                                   ScheduledTaskManifestEntry entry,
                                                                                   ScheduleConfig config) {
            var key = ScheduledTaskKey.scheduledTaskKey(entry.configSection(), artifact, entry.methodName());
            var taskName = entry.configSection() + "." + entry.methodName().name();
            Result<ScheduledTaskValue> validated = config.interval().isEmpty()
                                                   ? CronExpression.parse(config.cron())
                                                                   .map(_ -> ScheduledTaskValue.cronTask(ctx.self(),
                                                                                                         config.cron(),
                                                                                                         config.executionMode()))
                                                                   .mapError(cause -> INVALID_SCHEDULE_STRING.apply(taskName,
                                                                                                                    config.cron(),
                                                                                                                    cause.message()))
                                                   : ScheduledTaskManager.IntervalParser.parse(config.interval())
                                                                                        .map(_ -> ScheduledTaskValue.intervalTask(ctx.self(),
                                                                                                                                  config.interval(),
                                                                                                                                  config.executionMode()))
                                                                                        .mapError(cause -> INVALID_SCHEDULE_STRING.apply(taskName,
                                                                                                                                         config.interval(),
                                                                                                                                         cause.message()));

            return validated.map(value -> new KVCommand.Put<>(key, value.withPaused(existingPausedFlag(key))));
        }

        /// Operator pause (set via the management API into [ScheduledTaskValue#paused]) lives only on
        /// this cluster-scoped KV entry. Slice (re)activation republishes the SAME key — on rebalance,
        /// leader-failover republish, or node-wipe convergence — so the freshly built value MUST carry
        /// over the existing `paused` flag, or an unrelated deploy would silently resume a paused task.
        /// Absent prior value → `false` (the historical default for a brand-new registration).
        boolean existingPausedFlag(ScheduledTaskKey key) {
            return ctx.kvStore()
                      .get(key)
                      .filter(ScheduledTaskValue.class::isInstance)
                      .map(ScheduledTaskValue.class::cast)
                      .map(ScheduledTaskValue::paused)
                      .or(false);
        }

        private Promise<SliceNodeKey> unpublishScheduledTasks(SliceNodeKey sliceKey) {
            var artifact = sliceKey.artifact();

            return findLoadedSlice(artifact).map(ls -> doUnpublishScheduledTasks(artifact,
                                                                                 ls.slice()))
                                  .or(Promise.unitPromise())
                                  .map(_ -> sliceKey);
        }

        private Promise<Unit> doUnpublishScheduledTasks(Artifact artifact, Slice slice) {
            var entries = readScheduledTasksFromManifest(slice);

            if (entries.isEmpty()) {
                return Promise.unitPromise();
            }
            // ScheduledTaskKey is CLUSTER-scoped (scheduled-task/{section}/{artifact}/{method}, no
            // NodeId — see AetherKey.ScheduledTaskKey): all replicas hosting the task share one entry.
            // A single replica's deactivate (CDM rebalance, drain) must NOT Remove it while another
            // replica still hosts the task — that would destroy the live registration AND the operator
            // pause. Remove only when this node is the LAST hosting replica.
            if (artifactHostedElsewhere(artifact)) {
                log.debug("Skipping scheduled-task unpublish for {} — still hosted on other nodes", artifact);

                return Promise.unitPromise();
            }

            var commands = entries.stream()
                                  .<KVCommand<AetherKey>> map(entry -> buildScheduledTaskRemoveCommand(artifact, entry))
                                  .toList();

            return applyWithRetry(commands, 0).onSuccess(_ -> log.debug("Unpublished {} scheduled tasks for {}",
                                                                        entries.size(),
                                                                        artifact))
                                 .onFailure(cause -> log.error("Failed to unpublish scheduled tasks for {}: {}",
                                                               artifact,
                                                               cause.message()));
        }

        /// True when at least one OTHER live node still hosts `artifact`. Reuses the same
        /// `NodeArtifactKey` scan as [#collectTargetNodesForArtifact] (excludes self and
        /// FAILED/UNLOAD/UNLOADING replicas) so the last-replica decision matches the topology the
        /// rest of the FSM observes.
        boolean artifactHostedElsewhere(Artifact artifact) {
            return ! collectTargetNodesForArtifact(SliceNodeKey.sliceNodeKey(artifact, ctx.self())).isEmpty();
        }

        private KVCommand<AetherKey> buildScheduledTaskRemoveCommand(Artifact artifact,
                                                                     ScheduledTaskManifestEntry entry) {
            var key = ScheduledTaskKey.scheduledTaskKey(entry.configSection(), artifact, entry.methodName());

            return new KVCommand.Remove<>(key);
        }

        private record ConfigUpdateManifestEntry(String configSection, String factoryClassName) {}

        private Promise<SliceNodeKey> registerAndNotifyConfig(SliceNodeKey sliceKey) {
            var artifact = sliceKey.artifact();

            findLoadedSlice(artifact).onPresent(ls -> registerSliceForConfigUpdates(artifact, ls.slice()));

            return Promise.success(sliceKey);
        }

        private void registerSliceForConfigUpdates(Artifact artifact, Slice slice) {
            var entries = readConfigUpdateEntriesFromManifest(slice);

            if (entries.isEmpty()) {
                return;
            }

            var classLoader = slice.getClass().getClassLoader();
            var factoryClassName = entries.getFirst().factoryClassName();
            var sliceInstance = extractSliceInstance(slice);

            configNotificationManager.register(artifact, sliceInstance, classLoader, factoryClassName);
            var sections = entries.stream().map(ConfigUpdateManifestEntry::configSection).toList();

            configNotificationManager.notifyInitial(artifact, sections, buildConfigFacade(artifact));
            log.debug("Registered slice {} for config updates on sections: {}", artifact, sections);
        }

        private void unregisterConfig(SliceNodeKey sliceKey) {
            configNotificationManager.unregister(sliceKey.artifact());
        }

        private Object extractSliceInstance(Slice slice) {
            for (var iface : slice.getClass().getInterfaces()) {
                if (iface != Slice.class) {
                    return slice;
                }
            }

            return slice;
        }

        @SuppressWarnings("JBCT-EX-01")
        private List<ConfigUpdateManifestEntry> readConfigUpdateEntriesFromManifest(Slice slice) {
            var result = new ArrayList<ConfigUpdateManifestEntry>();
            var classLoader = slice.getClass().getClassLoader();

            for (var iface : slice.getClass().getInterfaces()) {
                if (iface == Slice.class) {
                    continue;
                }

                var manifestPath = "META-INF/slice/" + iface.getSimpleName() + ".manifest";

                readConfigUpdateEntries(classLoader, manifestPath, result);
            }

            return result;
        }

        @SuppressWarnings("JBCT-EX-01")
        private void readConfigUpdateEntries(ClassLoader classLoader,
                                             String manifestPath,
                                             List<ConfigUpdateManifestEntry> result) {
            try (var is = classLoader.getResourceAsStream(manifestPath)) {
                if (is == null) {
                    return;
                }

                var props = new Properties();

                props.load(is);
                var factoryClassName = props.getProperty("slice.factory", "");

                if (factoryClassName.isEmpty()) {
                    return;
                }

                var count = Integer.parseInt(props.getProperty("reactive.count", "0"));

                for (int i = 0; i < count; i++) {
                    var prefix = "reactive." + i + ".";
                    var category = props.getProperty(prefix + "category", "");

                    if ("config-update".equals(category)) {
                        var configSection = props.getProperty(prefix + "config");

                        if (configSection != null) {
                            result.add(new ConfigUpdateManifestEntry(configSection, factoryClassName));
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Could not read config update manifest {}: {}", manifestPath, e.getMessage());
            }
        }

        private ConfigFacade buildConfigFacade(Artifact artifact) {
            return sliceConfigService(artifact).orElse(ConfigService::instance)
                                     .map(org.pragmatica.aether.deployment.node.NodeDeploymentManager::configServiceToFacade)
                                     .or(org.pragmatica.aether.deployment.node.NodeDeploymentManager.NO_OP_CONFIG);
        }

        @SuppressWarnings("JBCT-EX-01")
        private List<ScheduledTaskManifestEntry> readScheduledTasksFromManifest(Slice slice) {
            var reactive = readReactiveBindingsFromManifest(slice);
            var result = new ArrayList<ScheduledTaskManifestEntry>();

            for (var entry : reactive) {
                if ("scheduled".equals(entry.category())) {
                    MethodName.methodName(entry.method())
                              .map(method -> new ScheduledTaskManifestEntry(entry.config(),
                                                                            method))
                              .option()
                              .onPresent(result::add);
                }
            }

            return result;
        }

        private Option<ScheduleConfig> resolveScheduleConfig(Artifact artifact, String configSection) {
            return sliceConfigService(artifact).orElse(ConfigService::instance)
                                     .flatMap(svc -> svc.config(configSection, ScheduleConfig.class)
                                                        .onFailure(cause -> log.warn("Slice {} schedule config binding failed for section {}: {}",
                                                                                     artifact,
                                                                                     configSection,
                                                                                     cause.message()))
                                                        .option());
        }

        private Promise<SliceNodeKey> publishStreamSubscriptions(SliceNodeKey sliceKey) {
            var artifact = sliceKey.artifact();

            return findLoadedSlice(artifact).map(ls -> doPublishStreamSubscriptions(artifact,
                                                                                    ls.slice()))
                                  .or(Promise.unitPromise())
                                  .map(_ -> sliceKey);
        }

        private Promise<Unit> doPublishStreamSubscriptions(Artifact artifact, Slice slice) {
            var entries = readStreamSubscriptionsFromManifest(artifact, slice);

            if (entries.isEmpty()) {
                return Promise.unitPromise();
            }

            var commands = entries.stream()
                                  .<KVCommand<AetherKey>> map(entry -> buildStreamSubscriptionPutCommand(artifact, entry))
                                  .toList();

            return applyWithRetry(commands, 0).onSuccess(_ -> log.debug("Published {} stream subscriptions for {}",
                                                                        entries.size(),
                                                                        artifact))
                                 .onFailure(cause -> log.error("Failed to publish stream subscriptions for {}: {}",
                                                               artifact,
                                                               cause.message()));
        }

        private KVCommand<AetherKey> buildStreamSubscriptionPutCommand(Artifact artifact,
                                                                       StreamSubscriptionManifestEntry entry) {
            var streamName = entry.streamName();
            var key = StreamRegistrationKey.streamRegistrationKey(streamName,
                                                                  entry.configSection(),
                                                                  artifact,
                                                                  entry.methodName());
            var consumerGroup = artifact.base().asString() + "-" + entry.methodName().name();
            var value = StreamRegistrationValue.streamRegistrationValue(ctx.self(),
                                                                        consumerGroup,
                                                                        entry.batchMode(),
                                                                        entry.eventType());

            return new KVCommand.Put<>(key, value);
        }

        private Promise<SliceNodeKey> unpublishStreamSubscriptions(SliceNodeKey sliceKey) {
            var artifact = sliceKey.artifact();

            return findLoadedSlice(artifact).map(ls -> doUnpublishStreamSubscriptions(artifact,
                                                                                      ls.slice()))
                                  .or(Promise.unitPromise())
                                  .map(_ -> sliceKey);
        }

        private Promise<Unit> doUnpublishStreamSubscriptions(Artifact artifact, Slice slice) {
            var entries = readStreamSubscriptionsFromManifest(artifact, slice);

            if (entries.isEmpty()) {
                return Promise.unitPromise();
            }

            var commands = entries.stream()
                                  .<KVCommand<AetherKey>> map(entry -> buildStreamSubscriptionRemoveCommand(artifact,
                                                                                                            entry))
                                  .toList();

            return applyWithRetry(commands, 0).onSuccess(_ -> log.debug("Unpublished {} stream subscriptions for {}",
                                                                        entries.size(),
                                                                        artifact))
                                 .onFailure(cause -> log.error("Failed to unpublish stream subscriptions for {}: {}",
                                                               artifact,
                                                               cause.message()));
        }

        private KVCommand<AetherKey> buildStreamSubscriptionRemoveCommand(Artifact artifact,
                                                                          StreamSubscriptionManifestEntry entry) {
            var streamName = entry.streamName();
            var key = StreamRegistrationKey.streamRegistrationKey(streamName,
                                                                  entry.configSection(),
                                                                  artifact,
                                                                  entry.methodName());

            return new KVCommand.Remove<>(key);
        }

        private record StreamSubscriptionManifestEntry(String streamName,
                                                       String configSection,
                                                       MethodName methodName,
                                                       boolean batchMode,
                                                       String eventType) {}

        @SuppressWarnings("JBCT-EX-01")
        private List<StreamSubscriptionManifestEntry> readStreamSubscriptionsFromManifest(Artifact artifact,
                                                                                          Slice slice) {
            var reactive = readReactiveBindingsFromManifest(slice);
            var result = new ArrayList<StreamSubscriptionManifestEntry>();

            for (var entry : reactive) {
                if ("stream".equals(entry.category())) {
                    var batchMode = Boolean.parseBoolean(entry.getProperty("batch"));
                    var eventType = entry.getProperty("eventType");

                    resolveStreamName(artifact,
                                      entry.config()).flatMap(streamName -> MethodName.methodName(entry.method()).map(method -> new StreamSubscriptionManifestEntry(streamName,
                                                                                                                                                                    entry.config(),
                                                                                                                                                                    method,
                                                                                                                                                                    batchMode,
                                                                                                                                                                    eventType)))
                                     .onFailure(cause -> log.error("Declarative stream consumer {}.{} on section [{}] could NOT be registered — it will receive nothing: {}",
                                                                   artifact,
                                                                   entry.method(),
                                                                   entry.config(),
                                                                   cause.message()))
                                     .option()
                                     .onPresent(result::add);
                }
            }

            return result;
        }

        /// Resolve the stream a `[streams.X]` consumer subscribes to.
        ///
        /// Binds [StreamConfig] — the SAME type the stream resource itself is provisioned with — and takes
        /// its `name`, so a consumer always resolves to exactly the stream its publisher writes to.
        ///
        /// This previously bound a dedicated `StreamNameConfig(String streamName)`, which required a
        /// `stream-name` key that no `resources.toml` carries (the stream's name comes from the config
        /// section, e.g. `[streams.orders]` → `orders`). Resolution therefore always failed and every
        /// declarative registration was silently dropped by the `.option()` below — a consumer that
        /// declared correctly still received nothing, with no diagnostic anywhere. Part of #488.
        private Result<String> resolveStreamName(Artifact artifact, String configSection) {
            return sliceConfigService(artifact).orElse(ConfigService::instance)
                                     .toResult(Causes.cause("ConfigService not available for stream name resolution"))
                                     .flatMap(svc -> svc.config(configSection, StreamConfig.class))
                                     .map(StreamConfig::name);
        }

        private void handleFailed(SliceNodeKey sliceKey) {
            log.warn("Slice {} entered FAILED state", sliceKey.artifact());
        }

        private void handleUnloading(SliceNodeKey sliceKey) {
            transitionTo(sliceKey, SliceState.UNLOADING).flatMap(this::unpublishEndpoints)
                        .flatMap(this::unpublishTopicSubscriptions)
                        .flatMap(this::unpublishStreamSubscriptions)
                        .flatMap(key -> releaseStreamReferences(key).map(_ -> key))
                        .flatMap(this::unpublishScheduledTasks)
                        .flatMap(this::unpublishHttpRoutes)
                        .withSuccess(this::unregisterSliceFromInvocation)
                        .flatMap(this::unloadSliceWithTimeout)
                        .flatMap(this::deleteSliceNodeKey)
                        .withSuccess(this::removeFromDeployments)
                        .withFailure(cause -> handleUnloadFailure(sliceKey, cause));
        }

        private Promise<SliceNodeKey> unloadSliceWithTimeout(SliceNodeKey sliceKey) {
            return ctx.configuration()
                      .timeoutFor(SliceState.UNLOADING)
                      .async()
                      .flatMap(timeout -> ctx.sliceStore()
                                             .unloadSlice(sliceKey.artifact())
                                             .timeout(timeout))
                      .map(_ -> sliceKey);
        }

        private void handleUnloadFailure(SliceNodeKey sliceKey, Cause cause) {
            log.error("Failed to unload {}: {}", sliceKey.artifact(), cause.message());
            deleteSliceNodeKey(sliceKey).onSuccess(this::removeFromDeployments)
                              .onFailure(deleteCause -> log.error("Failed to delete slice-node-key {} after unload failure: {}",
                                                                  sliceKey,
                                                                  deleteCause.message()));
        }

        private Promise<SliceNodeKey> deleteSliceNodeKey(SliceNodeKey sliceKey) {
            var nodeArtifactKey = NodeArtifactKey.nodeArtifactKey(ctx.self(), sliceKey.artifact());
            KVCommand<AetherKey> removeArtifact = new KVCommand.Remove<>(nodeArtifactKey);

            return applyWithRetry(List.of(removeArtifact),
                                  0).map(_ -> sliceKey)
                                 .onSuccess(_ -> log.debug("Deleted node-artifact-key {} from KV-Store", nodeArtifactKey));
        }

        private Promise<SliceNodeKey> transitionTo(SliceNodeKey sliceKey, SliceState newState) {
            return updateSliceState(sliceKey, SliceNodeValue.sliceNodeValue(newState));
        }

        // ── Stream reference counting (event-stream-namespaces §8.5) ──────────────────────────
        // Per-instance refcount accounting tied to slice ACTIVE state. ACQUIRE is consensus-batched
        // onto the same SliceNodeValue ACTIVE put so the refcount mutation is consensus-ordered
        // relative to the slice-state mutation. RELEASE rides the deactivate / unload / force-cleanup
        // exit paths. Address resolution goes through the per-blueprint BlueprintStreamBindings written
        // by BlueprintService at deploy time. Command construction reuses Stage-1's
        // KvBackedStreamRegistry acquire/release command builders (NOT the Promise-returning
        // acquireReference/releaseReference wrappers, which would split the consensus round and break
        // the §8.5 atomic-batch invariant).
        /// Spec §8.5: piggyback per-stream refcount increments on the same consensus round as the
        /// SliceNodeValue ACTIVE put. N active replicas ⇒ N refs (per-instance accounting).
        private Promise<SliceNodeKey> transitionToActiveWithStreamRefs(SliceNodeKey sliceKey) {
            var refCommands = buildStreamRefCommands(sliceKey, RefcountAction.ACQUIRE);

            return updateSliceStateWithExtraCommands(sliceKey,
                                                     SliceNodeValue.sliceNodeValue(SliceState.ACTIVE),
                                                     refCommands);
        }

        /// Mirror of [#transitionToActiveWithStreamRefs] for transitions OUT of ACTIVE. Decrements
        /// (or removes when refcount drops to zero) the per-stream refcount entries this slice
        /// instance had been holding. Best-effort: a release failure is logged but does not abort
        /// the deactivate/unload chain (the slice is already leaving ACTIVE).
        private Promise<Unit> releaseStreamReferences(SliceNodeKey sliceKey) {
            var commands = buildStreamRefCommands(sliceKey, RefcountAction.RELEASE);

            if (commands.isEmpty()) {
                return Promise.unitPromise();
            }

            return applyWithRetry(commands, 0).onSuccess(_ -> log.debug("Released {} stream refs for {}",
                                                                        commands.size(),
                                                                        sliceKey.artifact()))
                                 .onFailure(cause -> log.warn("Failed to release stream refs for {}: {}",
                                                              sliceKey.artifact(),
                                                              cause.message()));
        }

        private enum RefcountAction {
            ACQUIRE,
            RELEASE
        }

        private List<KVCommand<AetherKey>> buildStreamRefCommands(SliceNodeKey sliceKey, RefcountAction action) {
            var loaded = findLoadedSlice(sliceKey.artifact());

            if (loaded.isEmpty()) {
                return List.of();
            }

            var declarations = readStreamRoleDeclarationsFromManifest(loaded.unwrap().slice());

            if (declarations.isEmpty()) {
                return List.of();
            }

            var bindings = lookupStreamBindings(sliceKey);

            if (bindings.isEmpty()) {
                return List.of();
            }

            var registry = streamRegistry();
            var commands = new ArrayList<KVCommand<AetherKey>>();

            for (var declaration : declarations) {
                bindings.flatMap(b -> b.addressFor(declaration.alias()))
                        .onPresent(address -> appendRefCommand(registry, commands, address, action));
            }

            return List.copyOf(commands);
        }

        private void appendRefCommand(org.pragmatica.aether.slice.stream.KvBackedStreamRegistry registry,
                                      List<KVCommand<AetherKey>> sink,
                                      ResourceAddress address,
                                      RefcountAction action) {
            switch (action) {
                case ACQUIRE -> sink.add(registry.acquireCommand(blueprintTemplate(address)));
                case RELEASE -> registry.releaseCommand(address).onPresent(sink::add);
            }
        }

        private StreamRegistryEntry blueprintTemplate(ResourceAddress address) {
            return StreamRegistryEntry.blueprint(address,
                                                 RetentionPolicy.retentionPolicy(),
                                                 Instant.ofEpochMilli(ctx.nowMs()));
        }

        private org.pragmatica.aether.slice.stream.KvBackedStreamRegistry streamRegistry() {
            return org.pragmatica.aether.slice.stream.KvBackedStreamRegistry.kvBackedStreamRegistry(ctx.cluster(),
                                                                                                    ctx.kvStore());
        }

        private Option<BlueprintStreamBindingsValue> lookupStreamBindings(SliceNodeKey sliceKey) {
            return lookupOwningBlueprintId(sliceKey).flatMap(this::lookupBindingsFor);
        }

        private Option<BlueprintStreamBindingsValue> lookupBindingsFor(BlueprintId blueprintId) {
            return ctx.kvStore()
                      .get(BlueprintStreamBindingsKey.blueprintStreamBindingsKey(blueprintId))
                      .filter(BlueprintStreamBindingsValue.class::isInstance)
                      .map(BlueprintStreamBindingsValue.class::cast);
        }

        private Option<BlueprintId> lookupOwningBlueprintId(SliceNodeKey sliceKey) {
            return ctx.kvStore()
                      .get(SliceTargetKey.sliceTargetKey(sliceKey.artifact().base()))
                      .filter(SliceTargetValue.class::isInstance)
                      .map(SliceTargetValue.class::cast)
                      .flatMap(SliceTargetValue::owningBlueprint);
        }

        /// Per-slice (alias, role) pair extracted from the slice manifest. The `role` field is
        /// retained even though refcount accounting (§8.1) treats producer and consumer references
        /// uniformly.
        private record StreamRoleDeclaration(String alias, @SuppressWarnings("unused") String role) {}

        @SuppressWarnings("JBCT-EX-01")
        private List<StreamRoleDeclaration> readStreamRoleDeclarationsFromManifest(Slice slice) {
            var result = new ArrayList<StreamRoleDeclaration>();
            var classLoader = slice.getClass().getClassLoader();

            for (var iface : slice.getClass().getInterfaces()) {
                if (iface == Slice.class) {
                    continue;
                }

                appendStreamRoleDeclarations(classLoader,
                                             "META-INF/slice/" + iface.getSimpleName() + ".manifest",
                                             result);
            }

            return result;
        }

        @SuppressWarnings("JBCT-EX-01")
        private void appendStreamRoleDeclarations(ClassLoader classLoader,
                                                  String manifestPath,
                                                  List<StreamRoleDeclaration> sink) {
            try (var is = classLoader.getResourceAsStream(manifestPath)) {
                if (is == null) {
                    return;
                }

                var props = new Properties();

                props.load(is);
                appendRoleEntries(props, "stream.publisher.", "stream.publishers.count", "producer", sink);
                appendRoleEntries(props, "stream.access.", "stream.access.count", "consumer", sink);
            } catch (Exception e) {
                log.debug("Could not read stream role declarations from manifest {}: {}", manifestPath, e.getMessage());
            }
        }

        private static void appendRoleEntries(Properties props,
                                              String prefix,
                                              String countKey,
                                              String defaultRole,
                                              List<StreamRoleDeclaration> sink) {
            var count = parseManifestCount(props.getProperty(countKey));

            for (int i = 0; i < count; i++) {
                var configSection = props.getProperty(prefix + i + ".config");

                if (configSection == null || configSection.isEmpty()) {
                    continue;
                }

                var alias = aliasOf(configSection);

                if (alias.isEmpty()) {
                    continue;
                }

                sink.add(new StreamRoleDeclaration(alias, props.getProperty(prefix + i + ".role", defaultRole)));
            }
        }

        private static int parseManifestCount(String raw) {
            if (Option.option(raw).map(String::isEmpty).or(true)) {
                return 0;
            }

            try {
                return Integer.parseInt(raw.trim());
            } catch (NumberFormatException _) {
                return 0;
            }
        }

        private static String aliasOf(String configSection) {
            var prefix = "streams.";

            return configSection.startsWith(prefix)
                   ? configSection.substring(prefix.length())
                   : "";
        }

        /// Variant of [#updateSliceStateWithRetry] that batches `extraCommands` (the stream-ref
        /// mutations) into the same `cluster().apply(...)` round as the SliceNodeValue put, so the
        /// refcount mutation is consensus-ordered with the slice-state mutation (spec §8.5).
        private Promise<SliceNodeKey> updateSliceStateWithExtraCommands(SliceNodeKey sliceKey,
                                                                        SliceNodeValue value,
                                                                        List<KVCommand<AetherKey>> extraCommands) {
            return updateSliceStateWithExtraCommandsAndRetry(sliceKey, value, extraCommands, 0);
        }

        private Promise<SliceNodeKey> updateSliceStateWithExtraCommandsAndRetry(SliceNodeKey sliceKey,
                                                                                SliceNodeValue value,
                                                                                List<KVCommand<AetherKey>> extraCommands,
                                                                                int attempt) {
            log.debug("updateSliceStateWithExtraCommands: {} -> {} (attempt {}, +{} extras)",
                      sliceKey,
                      value.state(),
                      attempt,
                      extraCommands.size());
            var nodeArtifactKey = NodeArtifactKey.nodeArtifactKey(ctx.self(), sliceKey.artifact());
            var transitionedAt = value.state().isTransitional()
                                 ? ctx.nowMs()
                                 : 0L;
            var nodeArtifactValue = value.state() == SliceState.FAILED
                                    ? new NodeArtifactValue(SliceState.FAILED,
                                                            value.failureReason(),
                                                            value.fatal(),
                                                            0,
                                                            List.of(),
                                                            0L)
                                    : NodeArtifactValue.nodeArtifactValue(value.state(), transitionedAt);
            var commands = new ArrayList<KVCommand<AetherKey>>();

            commands.add(new KVCommand.Put<>(nodeArtifactKey, nodeArtifactValue));
            commands.addAll(extraCommands);

            return ctx.cluster()
                      .apply(commands)
                      .timeout(CONSENSUS_OPERATION_TIMEOUT)
                      .map(_ -> sliceKey)
                      .onSuccess(_ -> log.debug("State+refs update succeeded: {} -> {} (+{} extras)",
                                                sliceKey.artifact(),
                                                value.state(),
                                                extraCommands.size()))
                      .orElse(() -> retryConsensusBatch(sliceKey, value, extraCommands, attempt));
        }

        private Promise<SliceNodeKey> retryConsensusBatch(SliceNodeKey sliceKey,
                                                          SliceNodeValue value,
                                                          List<KVCommand<AetherKey>> extraCommands,
                                                          int attempt) {
            if (attempt >= CONSENSUS_MAX_RETRIES) {
                log.warn("Consensus batch failed after {} retries for {} -> {}",
                         CONSENSUS_MAX_RETRIES,
                         sliceKey.artifact(),
                         value.state());

                return Causes.cause("Consensus batch timed out after " + CONSENSUS_MAX_RETRIES + " retries").promise();
            }

            return updateSliceStateWithExtraCommandsAndRetry(sliceKey, value, extraCommands, attempt + 1);
        }

        private Promise<SliceNodeKey> transitionToFailed(SliceNodeKey sliceKey, Cause cause) {
            return transitionToFailedWithRetry(sliceKey, cause, 0);
        }

        private Promise<SliceNodeKey> transitionToFailedWithRetry(SliceNodeKey sliceKey,
                                                                  Cause originalCause,
                                                                  int attempt) {
            return updateSliceState(sliceKey, SliceNodeValue.failedSliceNodeValue(originalCause)).onFailure(writeCause -> handleFailedTransitionRetry(sliceKey,
                                                                                                                                                      originalCause,
                                                                                                                                                      writeCause,
                                                                                                                                                      attempt));
        }

        private void handleFailedTransitionRetry(SliceNodeKey sliceKey,
                                                 Cause originalCause,
                                                 Cause writeCause,
                                                 int attempt) {
            if (attempt < MAX_TRANSITION_RETRIES) {
                log.warn("Failed to write FAILED state for {} (attempt {}/{}), retrying in {}ms: {}",
                         sliceKey.artifact(),
                         attempt + 1,
                         MAX_TRANSITION_RETRIES,
                         ctx.transitionRetryDelay().millis(),
                         writeCause.message());
                SharedScheduler.schedule(() -> transitionToFailedWithRetry(sliceKey, originalCause, attempt + 1),
                                         ctx.transitionRetryDelay());
            } else {
                log.error("CRITICAL: Failed to write FAILED state for {} after {} attempts. Slice stuck in transitional state.",
                          sliceKey.artifact(),
                          MAX_TRANSITION_RETRIES);
            }
        }

        private void removeFromDeployments(SliceNodeKey sliceKey) {
            deployments.remove(sliceKey);
        }

        private Promise<SliceNodeKey> updateSliceState(SliceNodeKey sliceKey, SliceNodeValue value) {
            return updateSliceStateWithRetry(sliceKey, value, 0);
        }

        private Promise<SliceNodeKey> updateSliceStateWithRetry(SliceNodeKey sliceKey,
                                                                SliceNodeValue value,
                                                                int attempt) {
            log.debug("updateSliceState: {} -> {} (attempt {})",
                      sliceKey,
                      value.state(),
                      attempt);
            var nodeArtifactKey = NodeArtifactKey.nodeArtifactKey(ctx.self(), sliceKey.artifact());
            var transitionedAt = value.state().isTransitional()
                                 ? ctx.nowMs()
                                 : 0L;
            var nodeArtifactValue = value.state() == SliceState.FAILED
                                    ? new NodeArtifactValue(SliceState.FAILED,
                                                            value.failureReason(),
                                                            value.fatal(),
                                                            0,
                                                            List.of(),
                                                            0L)
                                    : NodeArtifactValue.nodeArtifactValue(value.state(), transitionedAt);
            KVCommand<AetherKey> putArtifact = new KVCommand.Put<>(nodeArtifactKey, nodeArtifactValue);

            return ctx.cluster()
                      .apply(List.of(putArtifact))
                      .timeout(CONSENSUS_OPERATION_TIMEOUT)
                      .map(_ -> sliceKey)
                      .onSuccess(_ -> log.debug("State update succeeded: {} -> {}",
                                                sliceKey.artifact(),
                                                value.state()))
                      .orElse(() -> retryConsensusOperation(sliceKey, value, attempt));
        }

        private Promise<SliceNodeKey> retryConsensusOperation(SliceNodeKey sliceKey,
                                                              SliceNodeValue value,
                                                              int attempt) {
            if (attempt >= CONSENSUS_MAX_RETRIES) {
                log.warn("Consensus operation failed after {} retries for {} -> {}",
                         CONSENSUS_MAX_RETRIES,
                         sliceKey.artifact(),
                         value.state());

                return Causes.cause("Consensus operation timed out after " + CONSENSUS_MAX_RETRIES + " retries").promise();
            }

            log.debug("Retrying consensus operation for {} -> {} (attempt {})",
                      sliceKey.artifact(),
                      value.state(),
                      attempt + 1);

            return updateSliceStateWithRetry(sliceKey, value, attempt + 1);
        }

        private Promise<Unit> applyWithRetry(List<KVCommand<AetherKey>> commands, int attempt) {
            return ctx.cluster()
                      .apply(commands)
                      .timeout(CONSENSUS_OPERATION_TIMEOUT)
                      .mapToUnit()
                      .orElse(() -> retryApply(commands, attempt));
        }

        private Promise<Unit> retryApply(List<KVCommand<AetherKey>> commands, int attempt) {
            if (attempt >= CONSENSUS_MAX_RETRIES) {
                log.warn("Consensus batch failed after {} retries ({} commands)", CONSENSUS_MAX_RETRIES, commands.size());

                return Causes.cause("Consensus batch timed out after " + CONSENSUS_MAX_RETRIES + " retries").promise();
            }

            log.debug("Retrying consensus batch ({} commands, attempt {}/{})",
                      commands.size(),
                      attempt + 1,
                      CONSENSUS_MAX_RETRIES);

            return applyWithRetry(commands, attempt + 1);
        }

        private void logError(Fn1<Cause, SliceNodeKey> errorTemplate, SliceNodeKey sliceKey, Cause cause) {
            log.error(errorTemplate.apply(sliceKey).message() + ": {}",
                      cause.message());
        }

        public List<SuspendedSlice> suspendSlices() {
            log.warn("Suspending {} slices due to quorum loss (keeping loaded in memory)", deployments.size());
            var suspended = new ArrayList<SuspendedSlice>();

            for (var entry : deployments.entrySet()) {
                var sliceKey = entry.getKey();
                var deployment = entry.getValue();

                if (deployment.state() == SliceState.ACTIVE) {
                    log.debug("Suspending active slice {}", sliceKey.artifact());
                    suspendSlice(sliceKey);
                    suspended.add(SuspendedSlice.suspendedSlice(sliceKey, deployment));
                } else {
                    log.debug("Slice {} in state {} - not suspending", sliceKey.artifact(), deployment.state());
                }
            }

            log.debug("Suspended {} active slices, ready for reactivation", suspended.size());

            return suspended;
        }

        private void suspendSlice(SliceNodeKey sliceKey) {
            ctx.httpRoutePublisher().onPresent(publisher -> unpublishRoutesForSuspension(publisher, sliceKey));
            unregisterSliceFromInvocation(sliceKey);
        }

        private void unpublishRoutesForSuspension(HttpRoutePublisher publisher, SliceNodeKey sliceKey) {
            publisher.unpublishRoutes(sliceKey.artifact());
            log.debug("Unpublished HTTP routes for suspended slice {}", sliceKey.artifact());
        }

        @Contract
        public void reactivateSuspendedSlices(List<SuspendedSlice> suspended) {
            if (suspended.isEmpty()) {
                log.debug("No suspended slices to reactivate");

                return;
            }

            log.info("Reactivating {} suspended slices after quorum restored", suspended.size());
            for (var suspendedSlice : suspended) {
                reactivateSingleSuspendedSlice(suspendedSlice);
            }
        }

        private void reactivateSingleSuspendedSlice(SuspendedSlice suspendedSlice) {
            var sliceKey = suspendedSlice.key();
            var deployment = suspendedSlice.deployment();

            deployments.put(sliceKey, deployment);
            var loadedSlice = findLoadedSlice(sliceKey.artifact());

            if (loadedSlice.isEmpty()) {
                log.warn("Suspended slice {} no longer in SliceStore, skipping reactivation", sliceKey.artifact());
                deployments.remove(sliceKey);

                return;
            }

            log.debug("Reactivating suspended slice {}", sliceKey.artifact());
            registerSliceForInvocation(sliceKey).flatMap(this::publishRoutesIfPresent)
                                      .flatMap(key -> publishEndpoints(key).map(_ -> key))
                                      .flatMap(this::publishTopicSubscriptions)
                                      .flatMap(this::publishScheduledTasks)
                                      .onSuccess(_ -> log.debug("Reactivated slice {}",
                                                                sliceKey.artifact()))
                                      .onFailure(cause -> handleReactivationFailure(sliceKey, cause));
        }

        @Contract
        private void handleReactivationFailure(SliceNodeKey sliceKey, Cause cause) {
            log.error("Failed to reactivate slice {}: {}", sliceKey.artifact(), cause.message());
            unregisterSliceFromInvocation(sliceKey);
            unpublishEndpoints(sliceKey).flatMap(this::unpublishTopicSubscriptions)
                              .flatMap(this::unpublishScheduledTasks)
                              .flatMap(this::unpublishHttpRoutes);
            deployments.remove(sliceKey);
        }

        @Contract
        public void deactivateAllSlices() {
            log.warn("Deactivating all {} slices due to quorum loss", deployments.size());
            for (var entry : deployments.entrySet()) {
                var sliceKey = entry.getKey();
                var deployment = entry.getValue();

                if (deployment.state() == SliceState.ACTIVE) {
                    forceCleanupSlice(sliceKey);
                }
            }

            deployments.clear();
        }
    }

    record Leaving(NodeDeploymentContext ctx, DrainReason reason, List<SuspendedSlice> suspendedAtEntry) implements NodeDeploymentState {
        @Override
        public void onEntry() {
            LOG.info("Node {} entering Leaving state (reason={}); suspending {} slices",
                     ctx.self().id(),
                     reason,
                     suspendedAtEntry.size());
        }

        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<NodeDeploymentState, ClusterFsmEvent> tx) {
            switch (event) {
                case Shutdown _ -> tx.transitionTo(ctx.stopped());
                default -> tx.ignore();
            }
        }
    }
}
