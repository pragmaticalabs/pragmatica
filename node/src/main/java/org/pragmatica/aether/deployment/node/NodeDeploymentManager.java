package org.pragmatica.aether.deployment.node;

import org.pragmatica.aether.invoke.InvocationHandler;
import org.pragmatica.aether.metrics.deployment.DeploymentEvent.*;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.SliceActionConfig;
import org.pragmatica.aether.slice.SliceBridgeImpl;
import org.pragmatica.aether.slice.serialization.FurySerializerFactoryProvider;
import org.pragmatica.aether.slice.serialization.SerializerFactoryProvider;
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
                                         ConcurrentHashMap<SliceNodeKey, SliceDeployment> deployments) implements NodeDeploymentState {
            private static final Logger log = LoggerFactory.getLogger(ActiveNodeDeploymentState.class);

            private static final Fn1<Cause, Class< ? >> UNEXPECTED_VALUE_TYPE = Causes.forOneValue("Unexpected value type for slice-node key: {}");

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
                executeWithStateTransition(sliceKey,
                                           SliceState.LOADING,
                                           sliceStore.loadSlice(sliceKey.artifact()),
                                           SliceState.LOADED,
                                           SliceState.FAILED);
            }

            private void handleLoaded(SliceNodeKey sliceKey) {
                // Only auto-activate if slice is actually loaded in our store
                // (protects against externally-set states like in tests)
                findLoadedSlice(sliceKey.artifact())
                               .onPresent(_ -> {
                                              log.info("Slice {} loaded, auto-activating",
                                                       sliceKey.artifact());
                                              transitionTo(sliceKey, SliceState.ACTIVATE);
                                          })
                               .onEmpty(() -> log.debug("Slice {} state is LOADED but not found in SliceStore, skipping auto-activation",
                                                        sliceKey.artifact()));
            }

            private void handleActivating(SliceNodeKey sliceKey) {
                // Only activate if slice is actually loaded in our store
                findLoadedSlice(sliceKey.artifact())
                               .onPresent(_ -> executeWithStateTransition(sliceKey,
                                                                          SliceState.ACTIVATING,
                                                                          sliceStore.activateSlice(sliceKey.artifact()),
                                                                          SliceState.ACTIVE,
                                                                          SliceState.FAILED))
                               .onEmpty(() -> log.debug("Slice {} state is ACTIVATE but not found in SliceStore, skipping activation",
                                                        sliceKey.artifact()));
            }

            private void handleActive(SliceNodeKey sliceKey) {
                // Slice is now active and serving requests
                // 1. Create SliceBridge and register with InvocationHandler
                registerSliceForInvocation(sliceKey);
                // 2. Publish endpoints to KV-Store
                publishEndpoints(sliceKey);
                // 3. Emit deployment completed event for metrics via MessageRouter
                router.route(new DeploymentCompleted(sliceKey.artifact(), self, System.currentTimeMillis()));
            }

            private void registerSliceForInvocation(SliceNodeKey sliceKey) {
                var artifact = sliceKey.artifact();
                findLoadedSlice(artifact)
                               .onPresent(ls -> registerSliceBridge(artifact,
                                                                    ls.slice()));
            }

            private void registerSliceBridge(Artifact artifact, org.pragmatica.aether.slice.Slice slice) {
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

            private void publishEndpoints(SliceNodeKey sliceKey) {
                var artifact = sliceKey.artifact();
                findLoadedSlice(artifact)
                               .onPresent(ls -> publishEndpointsForSlice(artifact,
                                                                         ls.slice()));
            }

            private void publishEndpointsForSlice(Artifact artifact, org.pragmatica.aether.slice.Slice slice) {
                var methods = slice.methods();
                int instanceNumber = Math.abs(self.id()
                                                  .hashCode());
                var commands = methods.stream()
                                      .map(method -> createEndpointPutCommand(artifact,
                                                                              method.name(),
                                                                              instanceNumber))
                                      .toList();
                if (commands.isEmpty()) {
                    return;
                }
                cluster.apply(commands)
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
                // Correct order for graceful shutdown:
                // 1. Unpublish endpoints first (stops new traffic from being routed here)
                // 2. Then unregister from invocation handler
                // 3. Then deactivate the slice
                unpublishEndpoints(sliceKey)
                                  .onResultRun(() -> {
                                                   unregisterSliceFromInvocation(sliceKey);
                                                   executeWithStateTransition(sliceKey,
                                                                              SliceState.DEACTIVATING,
                                                                              sliceStore.deactivateSlice(sliceKey.artifact()),
                                                                              SliceState.LOADED,
                                                                              SliceState.FAILED);
                                               });
            }

            private Promise<Unit> unpublishEndpoints(SliceNodeKey sliceKey) {
                var artifact = sliceKey.artifact();
                return findLoadedSlice(artifact)
                                      .map(ls -> unpublishEndpointsForSlice(artifact,
                                                                            ls.slice()))
                                      .or(Promise.success(Unit.unit()));
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
                    return Promise.success(Unit.unit());
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

            private void handleFailed(SliceNodeKey sliceKey) {}

            private void handleUnloading(SliceNodeKey sliceKey) {
                // Design note: Timeouts remain in NodeDeploymentManager (not SliceStore) to keep
                // SliceStore as a clean interface without configuration dependencies. The tradeoff
                // is that timeouts wrap the entire operation chain rather than individual operations.
                configuration.timeoutFor(SliceState.UNLOADING)
                             .onSuccess(timeout -> sliceStore.unloadSlice(sliceKey.artifact())
                                                             .timeout(timeout)
                                                             .onSuccess(_ -> removeFromDeployments(sliceKey))
                                                             .onFailure(cause -> handleUnloadFailure(sliceKey, cause)))
                             .onFailure(cause -> handleUnloadFailure(sliceKey, cause));
            }

            private void executeWithStateTransition(SliceNodeKey sliceKey,
                                                    SliceState currentState,
                                                    Promise< ?> operation,
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

            private void transitionTo(SliceNodeKey sliceKey, SliceState newState) {
                updateSliceState(sliceKey, newState);
            }

            private void removeFromDeployments(SliceNodeKey sliceKey) {
                deployments.remove(sliceKey);
            }

            private void handleUnloadFailure(SliceNodeKey sliceKey, Cause cause) {
                logError(UNLOAD_FAILED, sliceKey, cause);
                deployments.remove(sliceKey);
            }

            private void updateSliceState(SliceNodeKey sliceKey, SliceState newState) {
                log.debug("updateSliceState: {} -> {}",
                          sliceKey,
                          newState);
                var value = new SliceNodeValue(newState);
                KVCommand<AetherKey> command = new KVCommand.Put<>(sliceKey, value);
                // Submit command to cluster for consensus
                cluster.apply(List.of(command))
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
                                     SliceActionConfig.defaultConfiguration());
    }

    static NodeDeploymentManager nodeDeploymentManager(NodeId self,
                                                       MessageRouter router,
                                                       SliceStore sliceStore,
                                                       ClusterNode<KVCommand<AetherKey>> cluster,
                                                       KVStore<AetherKey, AetherValue> kvStore,
                                                       InvocationHandler invocationHandler,
                                                       SliceActionConfig configuration) {
        record deploymentManager(NodeId self,
                                 SliceStore sliceStore,
                                 ClusterNode<KVCommand<AetherKey>> cluster,
                                 KVStore<AetherKey, AetherValue> kvStore,
                                 InvocationHandler invocationHandler,
                                 SliceActionConfig configuration,
                                 MessageRouter router,
                                 AtomicReference<NodeDeploymentState> state) implements NodeDeploymentManager {
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
                log.info("Node {} received QuorumStateNotification: {}", self()
                                                                             .id(), quorumStateNotification);
                switch (quorumStateNotification) {
                    case ESTABLISHED -> {
                        // Only activate if currently dormant (idempotent)
                        if (state()
                                 .get() instanceof NodeDeploymentState.DormantNodeDeploymentState) {
                            state()
                                 .set(new NodeDeploymentState.ActiveNodeDeploymentState(self(),
                                                                                        sliceStore(),
                                                                                        configuration(),
                                                                                        cluster(),
                                                                                        kvStore(),
                                                                                        invocationHandler(),
                                                                                        router(),
                                                                                        new ConcurrentHashMap<>()));
                            log.info("Node {} NodeDeploymentManager activated", self()
                                                                                    .id());
                        }
                    }
                    case DISAPPEARED -> {
                        // Clean up any pending operations before going dormant
                        // Individual Promise timeouts will handle their own cleanup
                        state()
                             .set(new NodeDeploymentState.DormantNodeDeploymentState());
                    }
                }
            }
        }
        return new deploymentManager(self,
                                     sliceStore,
                                     cluster,
                                     kvStore,
                                     invocationHandler,
                                     configuration,
                                     router,
                                     new AtomicReference<>(new NodeDeploymentState.DormantNodeDeploymentState()));
    }
}
