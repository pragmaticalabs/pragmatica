package org.pragmatica.aether.deployment.node;

import org.pragmatica.aether.invoke.InvocationHandler;
import org.pragmatica.aether.slice.InternalSlice;
import org.pragmatica.aether.slice.InternalSlice.InternalMethod;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.SliceActionConfig;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.EndpointKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.EndpointValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceNodeValue;
import org.pragmatica.cluster.net.NodeId;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.cluster.topology.QuorumStateNotification;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.message.MessageReceiver;
import org.pragmatica.message.MessageRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public interface NodeDeploymentManager {
    record SliceDeployment(SliceNodeKey key, SliceState state, long timestamp) {}

    @MessageReceiver
    void onValuePut(ValuePut<AetherKey, AetherValue> valuePut);

    @MessageReceiver
    void onValueRemove(ValueRemove<AetherKey, AetherValue> valueRemove);

    @MessageReceiver
    void onQuorumStateChange(QuorumStateNotification quorumStateNotification);

    sealed interface NodeDeploymentState {
        default void onValuePut(ValuePut<AetherKey, AetherValue> valuePut) {
        }

        default void onValueRemove(ValueRemove<AetherKey, AetherValue> valueRemove) {
        }

        record DormantNodeDeploymentState() implements NodeDeploymentState {}

        record ActiveNodeDeploymentState(
                NodeId self,
                SliceStore sliceStore,
                SliceActionConfig configuration,
                ClusterNode<KVCommand<AetherKey>> cluster,
                InvocationHandler invocationHandler,
                ConcurrentHashMap<SliceNodeKey, SliceDeployment> deployments
        ) implements NodeDeploymentState {

            private static final Logger log = LoggerFactory.getLogger(ActiveNodeDeploymentState.class);
            private static final Fn1<Cause, Class<?>> UNEXPECTED_VALUE_TYPE =
                    Causes.forOneValue("Unexpected value type for slice-node key: {}");
            private static final Fn1<Cause, SliceNodeKey> CLEANUP_FAILED =
                    Causes.forOneValue("Failed to cleanup slice {} during abrupt removal");
            private static final Fn1<Cause, SliceNodeKey> STATE_UPDATE_FAILED =
                    Causes.forOneValue("Failed to update slice state for {}");
            private static final Fn1<Cause, SliceNodeKey> UNLOAD_FAILED =
                    Causes.forOneValue("Failed to unload slice {}");

            public void whenOurKeyMatches(AetherKey key, Consumer<SliceNodeKey> action) {
                switch (key) {
                    case SliceNodeKey sliceKey when sliceKey.isForNode(self) -> action.accept(sliceKey);
                    default -> {
                    }
                }
            }

            @Override
            public void onValuePut(ValuePut<AetherKey, AetherValue> valuePut) {
                whenOurKeyMatches(valuePut.cause().key(), sliceKey -> {
                    var value = valuePut.cause().value();

                    switch (value) {
                        case SliceNodeValue(SliceState state) -> {
                            log.debug("ValuePut received for key: {}, state: {}", sliceKey, state);
                            recordDeployment(sliceKey, state);
                            processStateTransition(sliceKey, state);
                        }
                        default -> log.warn(UNEXPECTED_VALUE_TYPE.apply(value.getClass()).message());
                    }
                });
            }

            @Override
            public void onValueRemove(ValueRemove<AetherKey, AetherValue> valueRemove) {
                whenOurKeyMatches(valueRemove.cause().key(), sliceKey -> {
                    log.debug("ValueRemove received for key: {}", sliceKey);

                    // WARNING: Removal may happen during abrupt stop due to lack of consensus.
                    // In this case slice might be active and we should immediately stop it,
                    // unload and remove, ignoring errors.
                    var deployment = deployments.remove(sliceKey);

                    if (shouldForceCleanup(deployment)) {
                        forceCleanupSlice(sliceKey);
                    }
                });
            }

            private void recordDeployment(SliceNodeKey sliceKey, SliceState state) {
                var timestamp = System.currentTimeMillis();
                var deployment = new SliceDeployment(sliceKey, state, timestamp);
                deployments.put(sliceKey, deployment);
            }

            private boolean shouldForceCleanup(SliceDeployment deployment) {
                return deployment != null && deployment.state() == SliceState.ACTIVE;
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
                    case LOADING -> {
                    } // Transitional state, no action
                    case LOADED -> handleLoaded(sliceKey);
                    case ACTIVATE -> handleActivating(sliceKey);
                    case ACTIVATING -> {
                    } // Transitional state, no action
                    case ACTIVE -> handleActive(sliceKey);
                    case DEACTIVATE -> handleDeactivating(sliceKey);
                    case DEACTIVATING -> {
                    } // Transitional state, no action
                    case FAILED -> handleFailed(sliceKey);
                    case UNLOAD -> handleUnloading(sliceKey);
                    case UNLOADING -> {
                    } // Transitional state, no action
                }
            }

            private void handleLoading(SliceNodeKey sliceKey) {
                executeWithStateTransition(
                        sliceKey,
                        SliceState.LOADING,
                        sliceStore.loadSlice(sliceKey.artifact()),
                        SliceState.LOADED,
                        SliceState.FAILED
                                          );
            }

            private void handleLoaded(SliceNodeKey sliceKey) {
                // Slice is loaded - auto-activate it
                // In future, this could include health checks or dependency validation
                log.info("Slice {} loaded, auto-activating", sliceKey.artifact());
                transitionTo(sliceKey, SliceState.ACTIVATE);
            }

            private void handleActivating(SliceNodeKey sliceKey) {
                executeWithStateTransition(
                        sliceKey,
                        SliceState.ACTIVATING,
                        sliceStore.activateSlice(sliceKey.artifact()),
                        SliceState.ACTIVE,
                        SliceState.FAILED
                                          );
            }

            private void handleActive(SliceNodeKey sliceKey) {
                // Slice is now active and serving requests
                // 1. Create InternalSlice and register with InvocationHandler
                registerSliceForInvocation(sliceKey);
                // 2. Publish endpoints to KV-Store
                publishEndpoints(sliceKey);
            }

            private void registerSliceForInvocation(SliceNodeKey sliceKey) {
                var artifact = sliceKey.artifact();
                var loadedSlice = sliceStore.loaded().stream()
                        .filter(ls -> ls.artifact().equals(artifact))
                        .findFirst();

                loadedSlice.ifPresent(ls -> {
                    var slice = ls.slice();
                    var serializerProvider = configuration.serializerProvider();

                    if (serializerProvider == null) {
                        log.warn("No SerializerFactoryProvider configured, skipping invocation registration for {}", artifact);
                        return;
                    }

                    // Build method map for InternalSlice
                    Map<MethodName, InternalMethod> methodMap = slice.methods().stream()
                            .collect(Collectors.toMap(
                                    method -> method.name(),
                                    method -> new InternalMethod(method, method.parameterType(), method.returnType())
                            ));

                    // Create SerializerFactory from provider using all type tokens
                    var typeTokens = slice.methods().stream()
                            .flatMap(m -> java.util.stream.Stream.of(m.parameterType(), m.returnType()))
                            .collect(Collectors.toList());
                    var serializerFactory = serializerProvider.createFactory(typeTokens);

                    // Create and register InternalSlice
                    var internalSlice = new InternalSlice(artifact, slice, methodMap, serializerFactory);
                    invocationHandler.registerSlice(artifact, internalSlice);
                    log.info("Registered slice {} for invocation", artifact);
                });
            }

            private void unregisterSliceFromInvocation(SliceNodeKey sliceKey) {
                var artifact = sliceKey.artifact();
                invocationHandler.unregisterSlice(artifact);
                log.info("Unregistered slice {} from invocation", artifact);
            }

            private void publishEndpoints(SliceNodeKey sliceKey) {
                var artifact = sliceKey.artifact();
                var loadedSlice = sliceStore.loaded().stream()
                        .filter(ls -> ls.artifact().equals(artifact))
                        .findFirst();

                loadedSlice.ifPresent(ls -> {
                    var slice = ls.slice();
                    var methods = slice.methods();
                    // Use nodeId hash as instance number to ensure uniqueness across cluster
                    int instanceNumber = Math.abs(self.id().hashCode());

                    var commands = methods.stream()
                            .map(method -> createEndpointPutCommand(artifact, method.name(), instanceNumber))
                            .toList();

                    if (!commands.isEmpty()) {
                        cluster.apply(commands)
                               .onSuccess(_ -> log.info("Published {} endpoints for slice {}", methods.size(), artifact))
                               .onFailure(cause -> log.error("Failed to publish endpoints for {}: {}", artifact, cause.message()));
                    }
                });
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
                        executeWithStateTransition(
                                sliceKey,
                                SliceState.DEACTIVATING,
                                sliceStore.deactivateSlice(sliceKey.artifact()),
                                SliceState.LOADED,
                                SliceState.FAILED
                        );
                    });
            }

            private Promise<Unit> unpublishEndpoints(SliceNodeKey sliceKey) {
                var artifact = sliceKey.artifact();
                var loadedSlice = sliceStore.loaded().stream()
                        .filter(ls -> ls.artifact().equals(artifact))
                        .findFirst();

                if (loadedSlice.isEmpty()) {
                    return Promise.success(Unit.unit());
                }

                var ls = loadedSlice.get();
                var slice = ls.slice();
                var methods = slice.methods();
                int instanceNumber = Math.abs(self.id().hashCode());

                var commands = methods.stream()
                        .map(method -> createEndpointRemoveCommand(artifact, method.name(), instanceNumber))
                        .toList();

                if (commands.isEmpty()) {
                    return Promise.success(Unit.unit());
                }

                return cluster.apply(commands)
                       .map(_ -> {
                           log.info("Unpublished {} endpoints for slice {}", methods.size(), artifact);
                           return Unit.unit();
                       })
                       .mapError(cause -> {
                           log.error("Failed to unpublish endpoints for {}: {}", artifact, cause.message());
                           return cause;
                       });
            }

            private KVCommand<AetherKey> createEndpointRemoveCommand(org.pragmatica.aether.artifact.Artifact artifact,
                                                                      MethodName methodName,
                                                                      int instanceNumber) {
                var key = new EndpointKey(artifact, methodName, instanceNumber);
                return new KVCommand.Remove<>(key);
            }

            private void handleFailed(SliceNodeKey sliceKey) {
                // Slice has failed - cleanup and prepare for unloading
                // Log the failure and await UNLOAD command
            }

            private void handleUnloading(SliceNodeKey sliceKey) {
                // TODO: move timeouts to SliceStore.
                //  Timeouts should be inserted as close to actual operations as possible.
                //  Otherwise they don't cancel the operation itself, but subsequent transformations.
                //  This may result in incorrect handling of subsequent operations as they will
                //  be executed only when original operation is completed.
                configuration.timeoutFor(SliceState.UNLOADING)
                             .onSuccess(timeout ->
                                                sliceStore.unloadSlice(sliceKey.artifact())
                                                          .timeout(timeout)
                                                          .onSuccess(_ -> removeFromDeployments(sliceKey))
                                                          .onFailure(cause -> handleUnloadFailure(sliceKey, cause))
                                       )
                             .onFailure(cause -> handleUnloadFailure(sliceKey, cause));
            }

            private void executeWithStateTransition(
                    SliceNodeKey sliceKey,
                    SliceState currentState,
                    Promise<?> operation,
                    SliceState successState,
                    SliceState failureState
                                                   ) {
                configuration.timeoutFor(currentState)
                             .onSuccess(timeout ->
                                                operation.timeout(timeout)
                                                         .onSuccess(_ -> transitionTo(sliceKey, successState))
                                                         .onFailure(_ -> transitionTo(sliceKey, failureState))
                                       )
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
                var value = new SliceNodeValue(newState);
                KVCommand<AetherKey> command = new KVCommand.Put<>(sliceKey, value);

                // Submit command to cluster for consensus
                cluster.apply(List.of(command))
                       .onFailure(cause -> logStateUpdateFailure(sliceKey, cause));
            }

            private void logStateUpdateFailure(SliceNodeKey sliceKey, Cause cause) {
                logError(STATE_UPDATE_FAILED, sliceKey, cause);
            }

            private void logError(Fn1<Cause, SliceNodeKey> errorTemplate, SliceNodeKey sliceKey, Cause cause) {
                log.error(errorTemplate.apply(sliceKey).message() + ": {}", cause.message());
            }
        }
    }

    static NodeDeploymentManager nodeDeploymentManager(NodeId self,
                                                       MessageRouter router,
                                                       SliceStore sliceStore,
                                                       ClusterNode<KVCommand<AetherKey>> cluster,
                                                       InvocationHandler invocationHandler) {
        return nodeDeploymentManager(self, router, sliceStore, cluster, invocationHandler, SliceActionConfig.defaultConfiguration());
    }

    static NodeDeploymentManager nodeDeploymentManager(NodeId self,
                                                       MessageRouter router,
                                                       SliceStore sliceStore,
                                                       ClusterNode<KVCommand<AetherKey>> cluster,
                                                       InvocationHandler invocationHandler,
                                                       SliceActionConfig configuration) {
        record deploymentManager(
                NodeId self,
                SliceStore sliceStore,
                ClusterNode<KVCommand<AetherKey>> cluster,
                InvocationHandler invocationHandler,
                SliceActionConfig configuration,
                MessageRouter router,
                AtomicReference<NodeDeploymentState> state
        ) implements NodeDeploymentManager {

            @Override
            public void onValuePut(ValuePut<AetherKey, AetherValue> valuePut) {
                state.get().onValuePut(valuePut);
            }

            @Override
            public void onValueRemove(ValueRemove<AetherKey, AetherValue> valueRemove) {
                state.get().onValueRemove(valueRemove);
            }

            @Override
            public void onQuorumStateChange(QuorumStateNotification quorumStateNotification) {
                switch (quorumStateNotification) {
                    case ESTABLISHED -> state().set(new NodeDeploymentState.ActiveNodeDeploymentState(
                            self(),
                            sliceStore(),
                            configuration(),
                            cluster(),
                            invocationHandler(),
                            new ConcurrentHashMap<>()
                    ));
                    case DISAPPEARED -> {
                        // Clean up any pending operations before going dormant
                        // Individual Promise timeouts will handle their own cleanup
                        state().set(new NodeDeploymentState.DormantNodeDeploymentState());
                    }
                }
            }
        }

        return new deploymentManager(
                self,
                sliceStore,
                cluster,
                invocationHandler,
                configuration,
                router,
                new AtomicReference<>(new NodeDeploymentState.DormantNodeDeploymentState())
        );
    }
}
