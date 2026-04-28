// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.fsm;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.config.ConfigNotificationManager;
import org.pragmatica.aether.deployment.drain.DrainCoordinator.DrainReason;
import org.pragmatica.aether.deployment.node.NodeDeploymentManager.SliceDeployment;
import org.pragmatica.aether.deployment.node.NodeDeploymentManager.SuspendedSlice;
import org.pragmatica.aether.deployment.node.RoutingEpochAckTracker;
import org.pragmatica.aether.deployment.node.fsm.NodeDeploymentEvents.LeavingRequested;
import org.pragmatica.aether.deployment.node.fsm.NodeDeploymentEvents.NodeArtifactPutReceived;
import org.pragmatica.aether.deployment.node.fsm.NodeDeploymentEvents.NodeArtifactRemoveReceived;
import org.pragmatica.aether.deployment.node.fsm.NodeDeploymentEvents.NodeRoutesPutReceived;
import org.pragmatica.aether.http.HttpRoutePublisher;
import org.pragmatica.aether.metrics.deployment.DeploymentEvent.DeploymentCompleted;
import org.pragmatica.aether.metrics.deployment.DeploymentEvent.DeploymentFailed;
import org.pragmatica.aether.metrics.deployment.DeploymentEvent.StateTransition;
import org.pragmatica.aether.slice.ConfigFacade;
import org.pragmatica.aether.slice.DefaultSliceBridge;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamRegistrationKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.TopicSubscriptionKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceNodeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamRegistrationValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopicSubscriptionValue;
import org.pragmatica.aether.resource.ScheduleConfig;
import org.pragmatica.aether.resource.StreamNameConfig;
import org.pragmatica.aether.resource.TopicConfig;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.fsm.ClusterFsmEvent.QuorumDisappeared;
import org.pragmatica.consensus.fsm.ClusterFsmEvent.QuorumEstablished;
import org.pragmatica.consensus.fsm.ClusterFsmEvent.Shutdown;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.statemachine.FsmState;
import org.pragmatica.statemachine.TransitionRequest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@SuppressWarnings("JBCT-RET-01") public sealed interface NodeDeploymentState extends FsmState<NodeDeploymentState, ClusterFsmEvent> permits NodeDeploymentState.Dormant, NodeDeploymentState.Active, NodeDeploymentState.Leaving, NodeDeploymentState.Stopped {
    Logger LOG = LoggerFactory.getLogger(NodeDeploymentState.class);

    NodeDeploymentContext ctx();

    record Dormant(NodeDeploymentContext ctx, List<SuspendedSlice> suspendedSlices) implements NodeDeploymentState {
        @Override public void handle(ClusterFsmEvent event,
                                     TransitionRequest<NodeDeploymentState, ClusterFsmEvent> tx) {
            switch (event){
                case QuorumEstablished _ -> tx.transitionTo(ctx.newActive(suspendedSlices));
                case Shutdown _ -> tx.transitionTo(ctx.stopped());
                default -> tx.ignore();
            }
        }
    }

    record Stopped(NodeDeploymentContext ctx) implements NodeDeploymentState {
        @Override public void onEntry() {
            LOG.debug("NodeDeploymentManager stopped");
        }

        @Override public void handle(ClusterFsmEvent event,
                                     TransitionRequest<NodeDeploymentState, ClusterFsmEvent> tx) {
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

        @Override public void onEntry() {
            log.info("Node {} NodeDeploymentManager activated",
                     ctx.self().id());
            ctx.activeOnEntryCallback().onPresent(Runnable::run);
            processPendingLoadCommands();
            if (!pendingReactivation.isEmpty()) {
                log.info("Node {} has {} suspended slices to reactivate",
                         ctx.self().id(),
                         pendingReactivation.size());
                reactivateSuspendedSlices(pendingReactivation);
            }
        }

        @Override public void handle(ClusterFsmEvent event,
                                     TransitionRequest<NodeDeploymentState, ClusterFsmEvent> tx) {
            switch (event){
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

        @Contract public void processPendingLoadCommands() {
            ctx.kvStore().forEach(NodeArtifactKey.class, NodeArtifactValue.class, this::processPendingLoadEntry);
        }

        private void processPendingLoadEntry(NodeArtifactKey key, NodeArtifactValue value) {
            if (!key.isForNode(ctx.self()) || value.state() != SliceState.LOAD) {return;}
            log.info("Node {} found pending LOAD command for {}, processing",
                     ctx.self().id(),
                     key.artifact());
            var sliceKey = SliceNodeKey.sliceNodeKey(key.artifact(), key.nodeId());
            recordDeployment(sliceKey,
                             SliceNodeValue.sliceNodeValue(value.state()));
            processStateTransition(sliceKey, value.state());
        }

        private Option<SliceStore.LoadedSlice> findLoadedSlice(Artifact artifact) {
            return Option.from(ctx.sliceStore().loaded()
                                             .stream()
                                             .filter(ls -> ls.artifact().equals(artifact))
                                             .findFirst());
        }

        private void fastTransitionToActive(SliceNodeKey sliceKey) {
            var current = Option.option(deployments.get(sliceKey)).map(SliceDeployment::state);
            if (!current.map(state -> state == SliceState.ROUTING).or(false)) {return;}
            log.debug("Cross-node ack threshold reached for {} — fast-transitioning ROUTING → ACTIVE",
                      sliceKey.artifact());
            transitionTo(sliceKey, SliceState.ACTIVE);
        }

        private void handleSliceValueRemove(SliceNodeKey sliceKey) {
            log.debug("ValueRemove received for key: {}", sliceKey);
            var deployment = Option.option(deployments.remove(sliceKey));
            routingEpochAckTracker.clear(sliceKey);
            if (shouldForceCleanup(deployment)) {forceCleanupSlice(sliceKey);}
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
            return deployment.map(d -> d.state() == SliceState.ACTIVE).or(false);
        }

        private void forceCleanupSlice(SliceNodeKey sliceKey) {
            unpublishEndpoints(sliceKey).flatMap(this::unpublishTopicSubscriptions)
                              .flatMap(this::unpublishStreamSubscriptions)
                              .flatMap(this::unpublishScheduledTasks)
                              .flatMap(this::unpublishHttpRoutes)
                              .withSuccess(this::unregisterSliceFromInvocation)
                              .flatMap(key -> ctx.sliceStore().deactivateSlice(key.artifact()))
                              .flatMap(_ -> ctx.sliceStore().unloadSlice(sliceKey.artifact()))
                              .onFailure(cause -> logCleanupFailure(sliceKey, cause));
        }

        private void logCleanupFailure(SliceNodeKey sliceKey, Cause cause) {
            logError(CLEANUP_FAILED, sliceKey, cause);
        }

        @Contract public void processStateTransition(SliceNodeKey sliceKey, SliceState state) {
            switch (state){
                case LOAD -> handleLoading(sliceKey);
                case LOADING -> {}
                case LOADED -> handleLoaded(sliceKey);
                case ACTIVATE -> handleActivating(sliceKey);
                case ACTIVATING -> {}
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
            return ctx.configuration().timeoutFor(SliceState.LOADING)
                                    .async()
                                    .flatMap(timeout -> ctx.sliceStore().loadSlice(sliceKey.artifact())
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
                        .flatMap(key -> transitionTo(key, SliceState.ACTIVE))
                        .flatMap(this::publishEndpoints)
                        .timeout(ctx.activationChainTimeout())
                        .withFailure(cause -> handleActivationFailure(sliceKey, cause));
        }

        private Promise<SliceNodeKey> publishRoutesIfPresent(SliceNodeKey sliceKey) {
            if (!sliceHasHttpRoutes(sliceKey.artifact())) {return Promise.success(sliceKey);}
            return transitionTo(sliceKey, SliceState.ROUTING).withSuccess(this::registerEpochAckExpectation)
                               .flatMap(key -> publishHttpRoutes(key).map(_ -> key));
        }

        private void registerEpochAckExpectation(SliceNodeKey sliceKey) {
            var targets = collectTargetNodesForArtifact(sliceKey);
            if (targets.isEmpty()) {return;}
            routingEpochAckTracker.registerExpectation(sliceKey, Epoch.ZERO, targets);
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
            if (!key.artifact().equals(sliceKey.artifact())) {return;}
            if (key.nodeId().equals(ctx.self())) {return;}
            if (value.state() == SliceState.FAILED || value.state() == SliceState.UNLOAD || value.state() == SliceState.UNLOADING) {return;}
            targets.add(key.nodeId());
        }

        private boolean sliceHasHttpRoutes(Artifact artifact) {
            return ctx.httpRoutePublisher().flatMap(publisher -> findLoadedSlice(artifact).map(ls -> publisher.hasRoutes(ls.slice().getClass()
                                                                                                                                 .getClassLoader(),
                                                                                                                         ls.slice())))
                                         .or(false);
        }

        private Promise<SliceNodeKey> activateSliceWithTimeout(SliceNodeKey sliceKey) {
            return ctx.configuration().timeoutFor(SliceState.ACTIVATING)
                                    .async()
                                    .flatMap(timeout -> ctx.sliceStore().activateSlice(sliceKey.artifact())
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
            routingEpochAckTracker.clear(sliceKey);
            ctx.router().route(DeploymentCompleted.deploymentCompleted(sliceKey.artifact(), ctx.self(), ctx.nowMs()));
        }

        private Promise<Unit> publishHttpRoutes(SliceNodeKey sliceKey) {
            var artifact = sliceKey.artifact();
            log.debug("publishHttpRoutes called for {} - httpRoutePublisher.isPresent={}, sliceInvokerFacade.isPresent={}",
                      artifact,
                      ctx.httpRoutePublisher().isPresent(),
                      ctx.sliceInvokerFacade().isPresent());
            return ctx.httpRoutePublisher().flatMap(publisher -> ctx.sliceInvokerFacade()
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
            var classLoader = ls.slice().getClass()
                                      .getClassLoader();
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
            if (methods.isEmpty()) {return Promise.unitPromise();}
            int instanceNumber = Math.abs(ctx.self().id()
                                                  .hashCode());
            var methodNames = methods.stream().map(m -> m.name().name())
                                            .toList();
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
                        .flatMap(this::unpublishScheduledTasks)
                        .flatMap(this::unpublishHttpRoutes)
                        .withSuccess(this::unregisterSliceFromInvocation)
                        .withSuccess(this::unregisterConfig)
                        .flatMap(this::deactivateSliceWithTimeout)
                        .flatMap(key -> transitionTo(key, SliceState.LOADED))
                        .withFailure(cause -> handleDeactivationFailure(sliceKey, cause));
        }

        private Promise<SliceNodeKey> deactivateSliceWithTimeout(SliceNodeKey sliceKey) {
            return ctx.configuration().timeoutFor(SliceState.DEACTIVATING)
                                    .async()
                                    .flatMap(timeout -> ctx.sliceStore().deactivateSlice(sliceKey.artifact())
                                                                      .timeout(timeout))
                                    .map(_ -> sliceKey);
        }

        private void handleDeactivationFailure(SliceNodeKey sliceKey, Cause cause) {
            log.error("Deactivation failed for {}: {}", sliceKey.artifact(), cause.message());
            transitionToFailed(sliceKey, cause);
        }

        private Promise<SliceNodeKey> unpublishHttpRoutes(SliceNodeKey sliceKey) {
            return ctx.httpRoutePublisher().map(publisher -> publisher.unpublishRoutes(sliceKey.artifact()))
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
            if (methods.isEmpty()) {return Promise.unitPromise();}
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
            var entries = readSubscriptionsFromManifest(slice);
            if (entries.isEmpty()) {return Promise.unitPromise();}
            var commands = entries.stream().<KVCommand<AetherKey>>map(entry -> buildTopicSubscriptionPutCommand(artifact,
                                                                                                                entry))
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
            var key = TopicSubscriptionKey.topicSubscriptionKey(entry.topicName(), artifact, entry.methodName());
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
            var entries = readSubscriptionsFromManifest(slice);
            if (entries.isEmpty()) {return Promise.unitPromise();}
            var commands = entries.stream().<KVCommand<AetherKey>>map(entry -> buildTopicSubscriptionRemoveCommand(artifact,
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
            var key = TopicSubscriptionKey.topicSubscriptionKey(entry.topicName(), artifact, entry.methodName());
            return new KVCommand.Remove<>(key);
        }

        private record SubscriptionManifestEntry(String topicName, MethodName methodName){}

        private record ScheduledTaskManifestEntry(String configSection, MethodName methodName){}

        private record ReactiveManifestEntry(String category,
                                             String method,
                                             String config,
                                             Properties manifest,
                                             String prefix) {
            String getProperty(String key) {
                return manifest.getProperty(prefix + key, "");
            }
        }

        @SuppressWarnings("JBCT-EX-01") private List<ReactiveManifestEntry> readReactiveBindingsFromManifest(Slice slice) {
            var result = new ArrayList<ReactiveManifestEntry>();
            var classLoader = slice.getClass().getClassLoader();
            for (var iface : slice.getClass().getInterfaces()) {
                if (iface == Slice.class) {continue;}
                var manifestPath = "META-INF/slice/" + iface.getSimpleName() + ".manifest";
                readReactiveEntriesFromManifest(classLoader, manifestPath, result);
            }
            return result;
        }

        @SuppressWarnings("JBCT-EX-01") private void readReactiveEntriesFromManifest(ClassLoader classLoader,
                                                                                     String manifestPath,
                                                                                     List<ReactiveManifestEntry> result) {
            try (var is = classLoader.getResourceAsStream(manifestPath)) {
                if (is == null) {return;}
                var props = new Properties();
                props.load(is);
                var count = Integer.parseInt(props.getProperty("reactive.count", "0"));
                for (int i = 0;i <count;i++) {
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

        @SuppressWarnings("JBCT-EX-01") private List<SubscriptionManifestEntry> readSubscriptionsFromManifest(Slice slice) {
            var reactive = readReactiveBindingsFromManifest(slice);
            var result = new ArrayList<SubscriptionManifestEntry>();
            for (var entry : reactive) {if ("subscription".equals(entry.category())) {resolveTopicName(entry.config()).flatMap(topicName -> MethodName.methodName(entry.method())
                                                                                                                                                                 .map(method -> new SubscriptionManifestEntry(topicName,
                                                                                                                                                                                                              method)))
                                                                                                      .option()
                                                                                                      .onPresent(result::add);}}
            return result;
        }

        private Result<String> resolveTopicName(String configSection) {
            return ConfigService.instance().toResult(Causes.cause("ConfigService not available for topic resolution"))
                                         .flatMap(svc -> svc.config(configSection, TopicConfig.class))
                                         .map(TopicConfig::topicName);
        }

        private Promise<SliceNodeKey> publishScheduledTasks(SliceNodeKey sliceKey) {
            var artifact = sliceKey.artifact();
            return findLoadedSlice(artifact).map(ls -> doPublishScheduledTasks(artifact,
                                                                               ls.slice()))
                                  .or(Promise.unitPromise())
                                  .map(_ -> sliceKey);
        }

        private Promise<Unit> doPublishScheduledTasks(Artifact artifact, Slice slice) {
            var entries = readScheduledTasksFromManifest(slice);
            if (entries.isEmpty()) {return Promise.unitPromise();}
            var commands = new ArrayList<KVCommand<AetherKey>>();
            for (var entry : entries) {resolveScheduleConfig(entry.configSection()).onPresent(config -> commands.add(buildScheduledTaskPutCommand(artifact,
                                                                                                                                                  entry,
                                                                                                                                                  config)));}
            if (commands.isEmpty()) {return Promise.unitPromise();}
            return applyWithRetry(commands, 0).onSuccess(_ -> log.debug("Published {} scheduled tasks for {}",
                                                                        commands.size(),
                                                                        artifact))
                                 .onFailure(cause -> log.error("Failed to publish scheduled tasks for {}: {}",
                                                               artifact,
                                                               cause.message()));
        }

        private KVCommand<AetherKey> buildScheduledTaskPutCommand(Artifact artifact,
                                                                  ScheduledTaskManifestEntry entry,
                                                                  ScheduleConfig config) {
            var key = ScheduledTaskKey.scheduledTaskKey(entry.configSection(), artifact, entry.methodName());
            var value = config.interval().isEmpty()
                       ? ScheduledTaskValue.cronTask(ctx.self(), config.cron(), config.executionMode())
                       : ScheduledTaskValue.intervalTask(ctx.self(), config.interval(), config.executionMode());
            return new KVCommand.Put<>(key, value);
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
            if (entries.isEmpty()) {return Promise.unitPromise();}
            var commands = entries.stream().<KVCommand<AetherKey>>map(entry -> buildScheduledTaskRemoveCommand(artifact,
                                                                                                               entry))
                                         .toList();
            return applyWithRetry(commands, 0).onSuccess(_ -> log.debug("Unpublished {} scheduled tasks for {}",
                                                                        entries.size(),
                                                                        artifact))
                                 .onFailure(cause -> log.error("Failed to unpublish scheduled tasks for {}: {}",
                                                               artifact,
                                                               cause.message()));
        }

        private KVCommand<AetherKey> buildScheduledTaskRemoveCommand(Artifact artifact,
                                                                     ScheduledTaskManifestEntry entry) {
            var key = ScheduledTaskKey.scheduledTaskKey(entry.configSection(), artifact, entry.methodName());
            return new KVCommand.Remove<>(key);
        }

        private record ConfigUpdateManifestEntry(String configSection, String factoryClassName){}

        private Promise<SliceNodeKey> registerAndNotifyConfig(SliceNodeKey sliceKey) {
            var artifact = sliceKey.artifact();
            findLoadedSlice(artifact).onPresent(ls -> registerSliceForConfigUpdates(artifact, ls.slice()));
            return Promise.success(sliceKey);
        }

        private void registerSliceForConfigUpdates(Artifact artifact, Slice slice) {
            var entries = readConfigUpdateEntriesFromManifest(slice);
            if (entries.isEmpty()) {return;}
            var classLoader = slice.getClass().getClassLoader();
            var factoryClassName = entries.getFirst().factoryClassName();
            var sliceInstance = extractSliceInstance(slice);
            configNotificationManager.register(artifact, sliceInstance, classLoader, factoryClassName);
            var sections = entries.stream().map(ConfigUpdateManifestEntry::configSection)
                                         .toList();
            configNotificationManager.notifyInitial(artifact, sections, buildConfigFacade());
            log.debug("Registered slice {} for config updates on sections: {}", artifact, sections);
        }

        private void unregisterConfig(SliceNodeKey sliceKey) {
            configNotificationManager.unregister(sliceKey.artifact());
        }

        private Object extractSliceInstance(Slice slice) {
            for (var iface : slice.getClass().getInterfaces()) {if (iface != Slice.class) {return slice;}}
            return slice;
        }

        @SuppressWarnings("JBCT-EX-01") private List<ConfigUpdateManifestEntry> readConfigUpdateEntriesFromManifest(Slice slice) {
            var result = new ArrayList<ConfigUpdateManifestEntry>();
            var classLoader = slice.getClass().getClassLoader();
            for (var iface : slice.getClass().getInterfaces()) {
                if (iface == Slice.class) {continue;}
                var manifestPath = "META-INF/slice/" + iface.getSimpleName() + ".manifest";
                readConfigUpdateEntries(classLoader, manifestPath, result);
            }
            return result;
        }

        @SuppressWarnings("JBCT-EX-01") private void readConfigUpdateEntries(ClassLoader classLoader,
                                                                             String manifestPath,
                                                                             List<ConfigUpdateManifestEntry> result) {
            try (var is = classLoader.getResourceAsStream(manifestPath)) {
                if (is == null) {return;}
                var props = new Properties();
                props.load(is);
                var factoryClassName = props.getProperty("slice.factory", "");
                if (factoryClassName.isEmpty()) {return;}
                var count = Integer.parseInt(props.getProperty("reactive.count", "0"));
                for (int i = 0;i <count;i++) {
                    var prefix = "reactive." + i + ".";
                    var category = props.getProperty(prefix + "category", "");
                    if ("config-update".equals(category)) {
                        var configSection = props.getProperty(prefix + "config");
                        if (configSection != null) {result.add(new ConfigUpdateManifestEntry(configSection,
                                                                                             factoryClassName));}
                    }
                }
            } catch (Exception e) {
                log.debug("Could not read config update manifest {}: {}", manifestPath, e.getMessage());
            }
        }

        private ConfigFacade buildConfigFacade() {
            return ConfigService.instance().map(org.pragmatica.aether.deployment.node.NodeDeploymentManager::configServiceToFacade)
                                         .or(org.pragmatica.aether.deployment.node.NodeDeploymentManager.NO_OP_CONFIG);
        }

        @SuppressWarnings("JBCT-EX-01") private List<ScheduledTaskManifestEntry> readScheduledTasksFromManifest(Slice slice) {
            var reactive = readReactiveBindingsFromManifest(slice);
            var result = new ArrayList<ScheduledTaskManifestEntry>();
            for (var entry : reactive) {if ("scheduled".equals(entry.category())) {MethodName.methodName(entry.method()).map(method -> new ScheduledTaskManifestEntry(entry.config(),
                                                                                                                                                                      method))
                                                                                                        .option()
                                                                                                        .onPresent(result::add);}}
            return result;
        }

        private Option<ScheduleConfig> resolveScheduleConfig(String configSection) {
            return ConfigService.instance().flatMap(svc -> svc.config(configSection, ScheduleConfig.class).option());
        }

        private Promise<SliceNodeKey> publishStreamSubscriptions(SliceNodeKey sliceKey) {
            var artifact = sliceKey.artifact();
            return findLoadedSlice(artifact).map(ls -> doPublishStreamSubscriptions(artifact,
                                                                                    ls.slice()))
                                  .or(Promise.unitPromise())
                                  .map(_ -> sliceKey);
        }

        private Promise<Unit> doPublishStreamSubscriptions(Artifact artifact, Slice slice) {
            var entries = readStreamSubscriptionsFromManifest(slice);
            if (entries.isEmpty()) {return Promise.unitPromise();}
            var commands = entries.stream().<KVCommand<AetherKey>>map(entry -> buildStreamSubscriptionPutCommand(artifact,
                                                                                                                 entry))
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
            var entries = readStreamSubscriptionsFromManifest(slice);
            if (entries.isEmpty()) {return Promise.unitPromise();}
            var commands = entries.stream().<KVCommand<AetherKey>>map(entry -> buildStreamSubscriptionRemoveCommand(artifact,
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
                                                       String eventType){}

        @SuppressWarnings("JBCT-EX-01") private List<StreamSubscriptionManifestEntry> readStreamSubscriptionsFromManifest(Slice slice) {
            var reactive = readReactiveBindingsFromManifest(slice);
            var result = new ArrayList<StreamSubscriptionManifestEntry>();
            for (var entry : reactive) {if ("stream".equals(entry.category())) {
                var batchMode = Boolean.parseBoolean(entry.getProperty("batch"));
                var eventType = entry.getProperty("eventType");
                resolveStreamName(entry.config()).flatMap(streamName -> MethodName.methodName(entry.method())
                                                                                             .map(method -> new StreamSubscriptionManifestEntry(streamName,
                                                                                                                                                entry.config(),
                                                                                                                                                method,
                                                                                                                                                batchMode,
                                                                                                                                                eventType)))
                                 .option()
                                 .onPresent(result::add);
            }}
            return result;
        }

        private Result<String> resolveStreamName(String configSection) {
            return ConfigService.instance().toResult(Causes.cause("ConfigService not available for stream name resolution"))
                                         .flatMap(svc -> svc.config(configSection, StreamNameConfig.class))
                                         .map(StreamNameConfig::streamName);
        }

        private void handleFailed(SliceNodeKey sliceKey) {
            log.warn("Slice {} entered FAILED state", sliceKey.artifact());
        }

        private void handleUnloading(SliceNodeKey sliceKey) {
            transitionTo(sliceKey, SliceState.UNLOADING).flatMap(this::unpublishEndpoints)
                        .flatMap(this::unpublishTopicSubscriptions)
                        .flatMap(this::unpublishStreamSubscriptions)
                        .flatMap(this::unpublishScheduledTasks)
                        .flatMap(this::unpublishHttpRoutes)
                        .withSuccess(this::unregisterSliceFromInvocation)
                        .flatMap(this::unloadSliceWithTimeout)
                        .flatMap(this::deleteSliceNodeKey)
                        .withSuccess(this::removeFromDeployments)
                        .withFailure(cause -> handleUnloadFailure(sliceKey, cause));
        }

        private Promise<SliceNodeKey> unloadSliceWithTimeout(SliceNodeKey sliceKey) {
            return ctx.configuration().timeoutFor(SliceState.UNLOADING)
                                    .async()
                                    .flatMap(timeout -> ctx.sliceStore().unloadSlice(sliceKey.artifact())
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
            if (attempt <MAX_TRANSITION_RETRIES) {
                log.warn("Failed to write FAILED state for {} (attempt {}/{}), retrying in {}ms: {}",
                         sliceKey.artifact(),
                         attempt + 1,
                         MAX_TRANSITION_RETRIES,
                         ctx.transitionRetryDelay().millis(),
                         writeCause.message());
                SharedScheduler.schedule(() -> transitionToFailedWithRetry(sliceKey, originalCause, attempt + 1),
                                         ctx.transitionRetryDelay());
            } else {log.error("CRITICAL: Failed to write FAILED state for {} after {} attempts. Slice stuck in transitional state.",
                              sliceKey.artifact(),
                              MAX_TRANSITION_RETRIES);}
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
            return ctx.cluster().apply(List.of(putArtifact))
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
                return Causes.cause("Consensus operation timed out after " + CONSENSUS_MAX_RETRIES + " retries")
                                   .promise();
            }
            log.debug("Retrying consensus operation for {} -> {} (attempt {})",
                      sliceKey.artifact(),
                      value.state(),
                      attempt + 1);
            return updateSliceStateWithRetry(sliceKey, value, attempt + 1);
        }

        private Promise<Unit> applyWithRetry(List<KVCommand<AetherKey>> commands, int attempt) {
            return ctx.cluster().apply(commands)
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
                } else {log.debug("Slice {} in state {} - not suspending", sliceKey.artifact(), deployment.state());}
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

        @Contract public void reactivateSuspendedSlices(List<SuspendedSlice> suspended) {
            if (suspended.isEmpty()) {
                log.debug("No suspended slices to reactivate");
                return;
            }
            log.info("Reactivating {} suspended slices after quorum restored", suspended.size());
            for (var suspendedSlice : suspended) {reactivateSingleSuspendedSlice(suspendedSlice);}
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

        @Contract private void handleReactivationFailure(SliceNodeKey sliceKey, Cause cause) {
            log.error("Failed to reactivate slice {}: {}", sliceKey.artifact(), cause.message());
            unregisterSliceFromInvocation(sliceKey);
            unpublishEndpoints(sliceKey).flatMap(this::unpublishTopicSubscriptions)
                              .flatMap(this::unpublishScheduledTasks)
                              .flatMap(this::unpublishHttpRoutes);
            deployments.remove(sliceKey);
        }

        @Contract public void deactivateAllSlices() {
            log.warn("Deactivating all {} slices due to quorum loss", deployments.size());
            for (var entry : deployments.entrySet()) {
                var sliceKey = entry.getKey();
                var deployment = entry.getValue();
                if (deployment.state() == SliceState.ACTIVE) {forceCleanupSlice(sliceKey);}
            }
            deployments.clear();
        }
    }

    record Leaving(NodeDeploymentContext ctx, DrainReason reason, List<SuspendedSlice> suspendedAtEntry) implements NodeDeploymentState {
        @Override public void onEntry() {
            LOG.info("Node {} entering Leaving state (reason={}); suspending {} slices",
                     ctx.self().id(),
                     reason,
                     suspendedAtEntry.size());
        }

        @Override public void handle(ClusterFsmEvent event,
                                     TransitionRequest<NodeDeploymentState, ClusterFsmEvent> tx) {
            switch (event){
                case Shutdown _ -> tx.transitionTo(ctx.stopped());
                default -> tx.ignore();
            }
        }
    }
}
