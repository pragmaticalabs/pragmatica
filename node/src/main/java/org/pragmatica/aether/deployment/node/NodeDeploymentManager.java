package org.pragmatica.aether.deployment.node;

import org.pragmatica.aether.http.HttpRoutePublisher;
import org.pragmatica.aether.invoke.InvocationHandler;
import org.pragmatica.aether.metrics.deployment.DeploymentEvent.*;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.SliceActionConfig;
import org.pragmatica.aether.slice.SliceBridgeImpl;
import org.pragmatica.aether.slice.serialization.FurySerializerFactoryProvider;
import org.pragmatica.aether.slice.serialization.SerializerFactoryProvider;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.EndpointKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.EndpointValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceNodeValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.topology.QuorumStateNotification;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.messaging.MessageRouter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public interface NodeDeploymentManager {
    record SliceDeployment(SliceNodeKey key, SliceState state, long timestamp) {}

    @MessageReceiver
    void onValuePut(ValuePut<AetherKey, AetherValue> valuePut);

    @MessageReceiver
    void onValueRemove(ValueRemove<AetherKey, AetherValue> valueRemove);

    @MessageReceiver
    void onQuorumStateChange(QuorumStateNotification quorumStateNotification);

    boolean isActive();

    sealed interface NodeDeploymentState {
        default void onValuePut(ValuePut<AetherKey, AetherValue> valuePut) {}

        default void onValueRemove(ValueRemove<AetherKey, AetherValue> valueRemove) {}

        record DormantNodeDeploymentState() implements NodeDeploymentState {}

        record ActiveNodeDeploymentState(NodeId self,
                                         SliceStore sliceStore,
                                         SliceActionConfig configuration,
                                         ClusterNode<KVCommand<AetherKey>> cluster,
                                         KVStore<AetherKey, AetherValue> kvStore,
                                         InvocationHandler invocationHandler,
                                         MessageRouter router,
                                         ConcurrentHashMap<SliceNodeKey, SliceDeployment> deployments,
                                         Option<HttpRoutePublisher> httpRoutePublisher,
                                         Option<SliceInvokerFacade> sliceInvokerFacade) implements NodeDeploymentState {
            private static final Logger log = LoggerFactory.getLogger(ActiveNodeDeploymentState.class);

            private static final Fn1<Cause, Class<?>> UNEXPECTED_VALUE_TYPE = Causes.forOneValue("Unexpected value type for slice-node key: {}");

            private static final Fn1<Cause, SliceNodeKey> CLEANUP_FAILED = Causes.forOneValue("Failed to cleanup slice {} during abrupt removal");

            private static final Fn1<Cause, SliceNodeKey> STATE_UPDATE_FAILED = Causes.forOneValue("Failed to update slice state for {}");

            private static final Fn1<Cause, SliceNodeKey> UNLOAD_FAILED = Causes.forOneValue("Failed to unload slice {}");

            private Option<SliceStore.LoadedSlice> findLoadedSlice(Artifact artifact) {
                return Option.option(sliceStore.loaded()
                                               .stream()
                                               .filter(ls -> ls.artifact()
                                                               .equals(artifact))
                                               .findFirst()
                                               .orElse(null));
            }

            public void whenOurKeyMatches(AetherKey key, Consumer<SliceNodeKey> action) {
                switch (key) {
                    case SliceNodeKey sliceKey when sliceKey.isForNode(self) -> action.accept(sliceKey);
                    default -> {}
                }
            }

            @Override
            public void onValuePut(ValuePut<AetherKey, AetherValue> valuePut) {
                whenOurKeyMatches(valuePut.cause()
                                          .key(),
                                  sliceKey -> handleSliceValuePut(sliceKey,
                                                                  valuePut.cause()
                                                                          .value()));
            }

            private void handleSliceValuePut(SliceNodeKey sliceKey, AetherValue value) {
                switch (value) {
                    case SliceNodeValue(SliceState state) -> {
                        log.info("ValuePut received for key: {}, state: {}", sliceKey, state);
                        recordDeployment(sliceKey, state);
                        processStateTransition(sliceKey, state);
                    }
                    default -> log.warn(UNEXPECTED_VALUE_TYPE.apply(value.getClass())
                                                             .message());
                }
            }

            @Override
            public void onValueRemove(ValueRemove<AetherKey, AetherValue> valueRemove) {
                whenOurKeyMatches(valueRemove.cause()
                                             .key(),
                                  this::handleSliceValueRemove);
            }

            private void handleSliceValueRemove(SliceNodeKey sliceKey) {
                log.debug("ValueRemove received for key: {}", sliceKey);
                // WARNING: Removal may happen during abrupt stop due to lack of consensus.
                // In this case slice might be active and we should immediately stop it,
                // unload and remove, ignoring errors.
                var deployment = Option.option(deployments.remove(sliceKey));
                if (shouldForceCleanup(deployment)) {
                    forceCleanupSlice(sliceKey);
                }
            }

            private void recordDeployment(SliceNodeKey sliceKey, SliceState state) {
                var timestamp = System.currentTimeMillis();
                var previousDeployment = Option.option(deployments.get(sliceKey));
                var previousState = previousDeployment.map(SliceDeployment::state);
                var deployment = new SliceDeployment(sliceKey, state, timestamp);
                deployments.put(sliceKey, deployment);
                // Emit state transition event for metrics via MessageRouter
                // For initial LOAD, use LOAD as both from and to (captures loadTime)
                var effectiveFromState = previousState.or(state);
                router.route(new StateTransition(sliceKey.artifact(), self, effectiveFromState, state, timestamp));
                // Emit deployment failed event if transitioning to FAILED
                // We do this here because we have access to previousState
                if (state == SliceState.FAILED) {
                    previousState.onPresent(prevState -> router.route(new DeploymentFailed(sliceKey.artifact(),
                                                                                           self,
                                                                                           prevState,
                                                                                           timestamp)));
                }
            }

            private boolean shouldForceCleanup(Option<SliceDeployment> deployment) {
                return deployment.map(d -> d.state() == SliceState.ACTIVE)
                                 .or(false);
            }

            private void forceCleanupSlice(SliceNodeKey sliceKey) {
                sliceStore.deactivateSlice(sliceKey.artifact())
                          .flatMap(_ -> sliceStore.unloadSlice(sliceKey.artifact()))
                          .onFailure(cause -> logCleanupFailure(sliceKey, cause));
            }

            private void logCleanupFailure(SliceNodeKey sliceKey, Cause cause) {
                logError(CLEANUP_FAILED, sliceKey, cause);
            }

            private void processStateTransition(SliceNodeKey sliceKey, SliceState state) {
                switch (state) {
                    case LOAD -> handleLoading(sliceKey);
                    case LOADING -> {}
                    // Transitional state, no action
                    case LOADED -> handleLoaded(sliceKey);
                    case ACTIVATE -> handleActivating(sliceKey);
                    case ACTIVATING -> {}
                    // Transitional state, no action
                    case ACTIVE -> handleActive(sliceKey);
                    case DEACTIVATE -> handleDeactivating(sliceKey);
                    case DEACTIVATING -> {}
                    // Transitional state, no action
                    case FAILED -> handleFailed(sliceKey);
                    case UNLOAD -> handleUnloading(sliceKey);
                    case UNLOADING -> {}
                }
            }

            private void handleLoading(SliceNodeKey sliceKey) {
                // 1. Write LOADING to KV first - wait for consensus before starting load
                transitionTo(sliceKey, SliceState.LOADING).flatMap(_ -> loadSliceWithTimeout(sliceKey))
                            .flatMap(_ -> transitionTo(sliceKey, SliceState.LOADED))
                            .onFailure(cause -> handleLoadingFailure(sliceKey, cause));
            }

            private Promise<SliceStore.LoadedSlice> loadSliceWithTimeout(SliceNodeKey sliceKey) {
                return configuration.timeoutFor(SliceState.LOADING)
                                    .async()
                                    .flatMap(timeout -> sliceStore.loadSlice(sliceKey.artifact())
                                                                  .timeout(timeout));
            }

            private void handleLoadingFailure(SliceNodeKey sliceKey, Cause cause) {
                log.error("Failed to load slice {}: {}", sliceKey.artifact(), cause.message());
                transitionTo(sliceKey, SliceState.FAILED);
            }

            private void handleLoaded(SliceNodeKey sliceKey) {
                // LOADED is a stable state - do nothing
                // ACTIVATE must be explicitly requested by ClusterDeploymentManager
                log.info("Slice {} loaded successfully, awaiting activation", sliceKey.artifact());
            }

            private void handleActivating(SliceNodeKey sliceKey) {
                // Only activate if slice is actually loaded in our store
                findLoadedSlice(sliceKey.artifact()).onEmpty(() -> handleSliceNotFoundForActivation(sliceKey))
                               .onPresent(_ -> performActivation(sliceKey));
            }

            private void handleSliceNotFoundForActivation(SliceNodeKey sliceKey) {
                log.error("Slice {} state is ACTIVATE but not found in SliceStore", sliceKey.artifact());
                transitionTo(sliceKey, SliceState.FAILED);
            }

            private void performActivation(SliceNodeKey sliceKey) {
                // 1. Write ACTIVATING first - wait for consensus before starting activation
                transitionTo(sliceKey, SliceState.ACTIVATING).flatMap(_ -> activateSliceWithTimeout(sliceKey))
                            .flatMap(_ -> registerSliceForInvocation(sliceKey))
                            .flatMap(_ -> publishEndpointsAndRoutes(sliceKey))
                            .flatMap(_ -> transitionTo(sliceKey, SliceState.ACTIVE))
                            .onFailure(cause -> handleActivationFailure(sliceKey, cause));
            }

            private Promise<Unit> activateSliceWithTimeout(SliceNodeKey sliceKey) {
                return configuration.timeoutFor(SliceState.ACTIVATING)
                                    .async()
                                    .flatMap(timeout -> sliceStore.activateSlice(sliceKey.artifact())
                                                                  .timeout(timeout)
                                                                  .mapToUnit());
            }

            private Promise<Unit> publishEndpointsAndRoutes(SliceNodeKey sliceKey) {
                return Promise.all(publishEndpoints(sliceKey),
                                   publishHttpRoutes(sliceKey))
                              .map((_, _) -> Unit.unit());
            }

            private void handleActivationFailure(SliceNodeKey sliceKey, Cause cause) {
                log.error("Activation failed for {}: {}", sliceKey.artifact(), cause.message());
                // Cleanup: unregister if registered, unpublish if published
                unregisterSliceFromInvocation(sliceKey);
                unpublishEndpoints(sliceKey);
                unpublishHttpRoutes(sliceKey);
                transitionTo(sliceKey, SliceState.FAILED);
            }

            private void handleActive(SliceNodeKey sliceKey) {
                // All registration and publishing is done in handleActivating BEFORE transitioning to ACTIVE
                // Here we only emit the deployment completed event for metrics
                router.route(new DeploymentCompleted(sliceKey.artifact(), self, System.currentTimeMillis()));
            }

            private Promise<Unit> publishHttpRoutes(SliceNodeKey sliceKey) {
                var artifact = sliceKey.artifact();
                return httpRoutePublisher.flatMap(publisher -> sliceInvokerFacade.flatMap(invoker -> findLoadedSlice(artifact)
                .map(ls -> {
                         var classLoader = ls.slice()
                                             .getClass()
                                             .getClassLoader();
                         return publisher.publishRoutes(artifact,
                                                        classLoader,
                                                        ls.slice(),
                                                        invoker)
                                         .onFailure(cause -> log.warn("Failed to publish HTTP routes for {}: {}",
                                                                      artifact,
                                                                      cause.message()));
                     })))
                                         .or(Promise.unitPromise());
            }

            private Promise<Unit> registerSliceForInvocation(SliceNodeKey sliceKey) {
                var artifact = sliceKey.artifact();
                return findLoadedSlice(artifact).toResult(SLICE_NOT_LOADED_FOR_REGISTRATION.apply(artifact.asString()))
                                      .map(ls -> registerSliceBridge(artifact,
                                                                     ls.slice()))
                                      .async();
            }

            private static final Fn1<Cause, String> SLICE_NOT_LOADED_FOR_REGISTRATION = Causes.forOneValue("Slice not loaded for registration: {}");

            private Unit registerSliceBridge(Artifact artifact, org.pragmatica.aether.slice.Slice slice) {
                var serializerProvider = resolveSerializerProvider();
                var typeTokens = slice.methods()
                                      .stream()
                                      .flatMap(m -> java.util.stream.Stream.of(m.parameterType(),
                                                                               m.returnType()))
                                      .collect(Collectors.toList());
                var serializerFactory = serializerProvider.createFactory(typeTokens);
                var sliceBridge = SliceBridgeImpl.sliceBridge(artifact, slice, serializerFactory);
                invocationHandler.registerSlice(artifact, sliceBridge);
                log.info("Registered slice {} for invocation", artifact);
                return Unit.unit();
            }

            private SerializerFactoryProvider resolveSerializerProvider() {
                var provider = configuration.serializerProvider();
                return provider != null
                       ? provider
                       : FurySerializerFactoryProvider.furySerializerFactoryProvider();
            }

            private void unregisterSliceFromInvocation(SliceNodeKey sliceKey) {
                var artifact = sliceKey.artifact();
                invocationHandler.unregisterSlice(artifact);
                log.info("Unregistered slice {} from invocation", artifact);
            }

            private Promise<Unit> publishEndpoints(SliceNodeKey sliceKey) {
                var artifact = sliceKey.artifact();
                return findLoadedSlice(artifact).map(ls -> publishEndpointsForSlice(artifact,
                                                                                    ls.slice()))
                                      .or(Promise.unitPromise());
            }

            private Promise<Unit> publishEndpointsForSlice(Artifact artifact, org.pragmatica.aether.slice.Slice slice) {
                var methods = slice.methods();
                int instanceNumber = Math.abs(self.id()
                                                  .hashCode());
                var commands = methods.stream()
                                      .map(method -> createEndpointPutCommand(artifact,
                                                                              method.name(),
                                                                              instanceNumber))
                                      .toList();
                if (commands.isEmpty()) {
                    return Promise.unitPromise();
                }
                return cluster.apply(commands)
                              .mapToUnit()
                              .onSuccess(_ -> log.info("Published {} endpoints for slice {}",
                                                       methods.size(),
                                                       artifact))
                              .onFailure(cause -> log.error("Failed to publish endpoints for {}: {}",
                                                            artifact,
                                                            cause.message()));
            }

            private KVCommand<AetherKey> createEndpointPutCommand(org.pragmatica.aether.artifact.Artifact artifact,
                                                                  MethodName methodName,
                                                                  int instanceNumber) {
                var key = new EndpointKey(artifact, methodName, instanceNumber);
                var value = new EndpointValue(self);
                return new KVCommand.Put<>(key, value);
            }

            private void handleDeactivating(SliceNodeKey sliceKey) {
                findLoadedSlice(sliceKey.artifact()).onEmpty(() -> handleSliceNotFoundForDeactivation(sliceKey))
                               .onPresent(_ -> performDeactivation(sliceKey));
            }

            private void handleSliceNotFoundForDeactivation(SliceNodeKey sliceKey) {
                // Slice not loaded, just transition to LOADED
                log.warn("Slice {} not found in store during deactivation, transitioning to LOADED", sliceKey.artifact());
                transitionTo(sliceKey, SliceState.LOADED);
            }

            private void performDeactivation(SliceNodeKey sliceKey) {
                // 1. Write DEACTIVATING first - wait for consensus before starting deactivation
                transitionTo(sliceKey, SliceState.DEACTIVATING).flatMap(_ -> unpublishEndpoints(sliceKey))
                            .flatMap(_ -> unpublishHttpRoutes(sliceKey))
                            .onSuccessRun(() -> unregisterSliceFromInvocation(sliceKey))
                            .flatMap(_ -> deactivateSliceWithTimeout(sliceKey))
                            .flatMap(_ -> transitionTo(sliceKey, SliceState.LOADED))
                            .onFailure(cause -> handleDeactivationFailure(sliceKey, cause));
            }

            private Promise<Unit> deactivateSliceWithTimeout(SliceNodeKey sliceKey) {
                return configuration.timeoutFor(SliceState.DEACTIVATING)
                                    .async()
                                    .flatMap(timeout -> sliceStore.deactivateSlice(sliceKey.artifact())
                                                                  .timeout(timeout)
                                                                  .mapToUnit());
            }

            private void handleDeactivationFailure(SliceNodeKey sliceKey, Cause cause) {
                log.error("Deactivation failed for {}: {}", sliceKey.artifact(), cause.message());
                transitionTo(sliceKey, SliceState.FAILED);
            }

            private Promise<Unit> unpublishHttpRoutes(SliceNodeKey sliceKey) {
                return httpRoutePublisher.map(publisher -> publisher.unpublishRoutes(sliceKey.artifact()))
                                         .or(Promise.unitPromise());
            }

            private Promise<Unit> unpublishEndpoints(SliceNodeKey sliceKey) {
                var artifact = sliceKey.artifact();
                return findLoadedSlice(artifact).map(ls -> unpublishEndpointsForSlice(artifact,
                                                                                      ls.slice()))
                                      .or(Promise.unitPromise());
            }

            private Promise<Unit> unpublishEndpointsForSlice(Artifact artifact,
                                                             org.pragmatica.aether.slice.Slice slice) {
                var methods = slice.methods();
                int instanceNumber = Math.abs(self.id()
                                                  .hashCode());
                var commands = methods.stream()
                                      .map(method -> createEndpointRemoveCommand(artifact,
                                                                                 method.name(),
                                                                                 instanceNumber))
                                      .toList();
                if (commands.isEmpty()) {
                    return Promise.unitPromise();
                }
                return cluster.apply(commands)
                              .mapToUnit()
                              .onSuccess(_ -> log.info("Unpublished {} endpoints for slice {}",
                                                       methods.size(),
                                                       artifact))
                              .onFailure(cause -> log.error("Failed to unpublish endpoints for {}: {}",
                                                            artifact,
                                                            cause.message()));
            }

            private KVCommand<AetherKey> createEndpointRemoveCommand(org.pragmatica.aether.artifact.Artifact artifact,
                                                                     MethodName methodName,
                                                                     int instanceNumber) {
                var key = new EndpointKey(artifact, methodName, instanceNumber);
                return new KVCommand.Remove<>(key);
            }

            private void handleFailed(SliceNodeKey sliceKey) {
                // Log the failure for observability
                log.warn("Slice {} entered FAILED state", sliceKey.artifact());
            }

            private void handleUnloading(SliceNodeKey sliceKey) {
                // 1. Write UNLOADING to KV first - wait for consensus before starting unload
                transitionTo(sliceKey, SliceState.UNLOADING).flatMap(_ -> unloadSliceWithTimeout(sliceKey))
                            .flatMap(_ -> deleteSliceNodeKey(sliceKey))
                            .onSuccess(_ -> removeFromDeployments(sliceKey))
                            .onFailure(cause -> handleUnloadFailure(sliceKey, cause));
            }

            private Promise<Unit> unloadSliceWithTimeout(SliceNodeKey sliceKey) {
                return configuration.timeoutFor(SliceState.UNLOADING)
                                    .async()
                                    .flatMap(timeout -> sliceStore.unloadSlice(sliceKey.artifact())
                                                                  .timeout(timeout));
            }

            private void handleUnloadFailure(SliceNodeKey sliceKey, Cause cause) {
                log.error("Failed to unload {}: {}", sliceKey.artifact(), cause.message());
                removeFromDeployments(sliceKey);
            }

            private Promise<Unit> deleteSliceNodeKey(SliceNodeKey sliceKey) {
                return cluster.apply(List.of(new KVCommand.Remove<>(sliceKey)))
                              .mapToUnit()
                              .onSuccess(_ -> log.info("Deleted slice-node-key {} from KV store", sliceKey));
            }

            private void executeWithStateTransition(SliceNodeKey sliceKey,
                                                    SliceState currentState,
                                                    Promise<?> operation,
                                                    SliceState successState,
                                                    SliceState failureState) {
                log.debug("executeWithStateTransition: {} current={} success={} failure={}, operation.isResolved={}",
                          sliceKey.artifact(),
                          currentState,
                          successState,
                          failureState,
                          operation.isResolved());
                configuration.timeoutFor(currentState)
                             .onSuccess(timeout -> {
                                            log.debug("Got timeout {} for {}, setting up callbacks",
                                                      timeout,
                                                      sliceKey.artifact());
                                            operation.timeout(timeout)
                                                     .onSuccess(_ -> {
                                                                    log.info("Operation succeeded for {}, transitioning to {}",
                                                                             sliceKey.artifact(),
                                                                             successState);
                                                                    transitionTo(sliceKey, successState);
                                                                })
                                                     .onFailure(cause -> {
                                                                    log.warn("Operation failed for {}: {}, transitioning to {}",
                                                                             sliceKey.artifact(),
                                                                             cause.message(),
                                                                             failureState);
                                                                    transitionTo(sliceKey, failureState);
                                                                });
                                        })
                             .onFailure(cause -> logStateUpdateFailure(sliceKey, cause));
            }

            private Promise<Unit> transitionTo(SliceNodeKey sliceKey, SliceState newState) {
                return updateSliceState(sliceKey, newState);
            }

            private void removeFromDeployments(SliceNodeKey sliceKey) {
                deployments.remove(sliceKey);
            }

            private Promise<Unit> updateSliceState(SliceNodeKey sliceKey, SliceState newState) {
                log.debug("updateSliceState: {} -> {}",
                          sliceKey,
                          newState);
                var value = new SliceNodeValue(newState);
                KVCommand<AetherKey> command = new KVCommand.Put<>(sliceKey, value);
                // Submit command to cluster for consensus
                return cluster.apply(List.of(command))
                              .mapToUnit()
                              .onSuccess(_ -> log.debug("State update succeeded: {} -> {}",
                                                        sliceKey.artifact(),
                                                        newState))
                              .onFailure(cause -> logStateUpdateFailure(sliceKey, cause));
            }

            private void logStateUpdateFailure(SliceNodeKey sliceKey, Cause cause) {
                logError(STATE_UPDATE_FAILED, sliceKey, cause);
            }

            private void logError(Fn1<Cause, SliceNodeKey> errorTemplate, SliceNodeKey sliceKey, Cause cause) {
                log.error(errorTemplate.apply(sliceKey)
                                       .message() + ": {}",
                          cause.message());
            }

            /**
             * Deactivate all slices on quorum loss.
             * Called before transitioning to dormant state.
             */
            void deactivateAllSlices() {
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
    }

    static NodeDeploymentManager nodeDeploymentManager(NodeId self,
                                                       MessageRouter router,
                                                       SliceStore sliceStore,
                                                       ClusterNode<KVCommand<AetherKey>> cluster,
                                                       KVStore<AetherKey, AetherValue> kvStore,
                                                       InvocationHandler invocationHandler) {
        return nodeDeploymentManager(self,
                                     router,
                                     sliceStore,
                                     cluster,
                                     kvStore,
                                     invocationHandler,
                                     SliceActionConfig.defaultConfiguration(),
                                     Option.none(),
                                     Option.none());
    }

    static NodeDeploymentManager nodeDeploymentManager(NodeId self,
                                                       MessageRouter router,
                                                       SliceStore sliceStore,
                                                       ClusterNode<KVCommand<AetherKey>> cluster,
                                                       KVStore<AetherKey, AetherValue> kvStore,
                                                       InvocationHandler invocationHandler,
                                                       SliceActionConfig configuration) {
        return nodeDeploymentManager(self,
                                     router,
                                     sliceStore,
                                     cluster,
                                     kvStore,
                                     invocationHandler,
                                     configuration,
                                     Option.none(),
                                     Option.none());
    }

    static NodeDeploymentManager nodeDeploymentManager(NodeId self,
                                                       MessageRouter router,
                                                       SliceStore sliceStore,
                                                       ClusterNode<KVCommand<AetherKey>> cluster,
                                                       KVStore<AetherKey, AetherValue> kvStore,
                                                       InvocationHandler invocationHandler,
                                                       SliceActionConfig configuration,
                                                       Option<HttpRoutePublisher> httpRoutePublisher,
                                                       Option<SliceInvokerFacade> sliceInvokerFacade) {
        record deploymentManager(NodeId self,
                                 SliceStore sliceStore,
                                 ClusterNode<KVCommand<AetherKey>> cluster,
                                 KVStore<AetherKey, AetherValue> kvStore,
                                 InvocationHandler invocationHandler,
                                 SliceActionConfig configuration,
                                 MessageRouter router,
                                 AtomicReference<NodeDeploymentState> state,
                                 Option<HttpRoutePublisher> httpRoutePublisher,
                                 Option<SliceInvokerFacade> sliceInvokerFacade) implements NodeDeploymentManager {
            private static final Logger log = LoggerFactory.getLogger(NodeDeploymentManager.class);

            @Override
            public void onValuePut(ValuePut<AetherKey, AetherValue> valuePut) {
                state.get()
                     .onValuePut(valuePut);
            }

            @Override
            public void onValueRemove(ValueRemove<AetherKey, AetherValue> valueRemove) {
                state.get()
                     .onValueRemove(valueRemove);
            }

            @Override
            public void onQuorumStateChange(QuorumStateNotification quorumStateNotification) {
                log.info("Node {} received QuorumStateNotification: {}", self().id(), quorumStateNotification);
                switch (quorumStateNotification) {
                    case ESTABLISHED -> {
                        // Only activate if currently dormant (idempotent)
                        if (state().get() instanceof NodeDeploymentState.DormantNodeDeploymentState) {
                            state()
                            .set(new NodeDeploymentState.ActiveNodeDeploymentState(self(),
                                                                                   sliceStore(),
                                                                                   configuration(),
                                                                                   cluster(),
                                                                                   kvStore(),
                                                                                   invocationHandler(),
                                                                                   router(),
                                                                                   new ConcurrentHashMap<>(),
                                                                                   httpRoutePublisher(),
                                                                                   sliceInvokerFacade()));
                            log.info("Node {} NodeDeploymentManager activated", self().id());
                        }
                    }
                    case DISAPPEARED -> {
                        // Deactivate all slices before going dormant
                        if (state().get() instanceof NodeDeploymentState.ActiveNodeDeploymentState activeState) {
                            activeState.deactivateAllSlices();
                        }
                        state().set(new NodeDeploymentState.DormantNodeDeploymentState());
                    }
                }
            }

            @Override
            public boolean isActive() {
                return state().get() instanceof NodeDeploymentState.ActiveNodeDeploymentState;
            }
        }
        return new deploymentManager(self,
                                     sliceStore,
                                     cluster,
                                     kvStore,
                                     invocationHandler,
                                     configuration,
                                     router,
                                     new AtomicReference<>(new NodeDeploymentState.DormantNodeDeploymentState()),
                                     httpRoutePublisher,
                                     sliceInvokerFacade);
    }
}
