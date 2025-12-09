package org.pragmatica.aether.deployment.node;

import org.pragmatica.aether.slice.SliceActionConfig;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
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
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.message.MessageReceiver;
import org.pragmatica.message.MessageRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

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
                ConcurrentHashMap<SliceNodeKey, SliceDeployment> deployments
        ) implements NodeDeploymentState {

            private static final Logger log = LoggerFactory.getLogger(ActiveNodeDeploymentState.class);
            private static final Fn1<Cause, Class<?>> UNEXPECTED_VALUE_TYPE =
                Causes.forValue("Unexpected value type for slice-node key: {}");
            private static final Fn1<Cause, SliceNodeKey> CLEANUP_FAILED =
                Causes.forValue("Failed to cleanup slice {} during abrupt removal");
            private static final Fn1<Cause, SliceNodeKey> STATE_UPDATE_FAILED =
                Causes.forValue("Failed to update slice state for {}");
            private static final Fn1<Cause, SliceNodeKey> UNLOAD_FAILED =
                Causes.forValue("Failed to unload slice {}");

            public void whenOurKeyMatches(AetherKey key, Consumer<SliceNodeKey> action) {
                switch (key) {
                    case SliceNodeKey sliceKey when sliceKey.isForNode(self) ->
                        action.accept(sliceKey);
                    default -> {}
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
                    case LOADING -> {} // Transitional state, no action
                    case LOADED -> handleLoaded(sliceKey);
                    case ACTIVATE -> handleActivating(sliceKey);
                    case ACTIVATING -> {} // Transitional state, no action
                    case ACTIVE -> handleActive(sliceKey);
                    case DEACTIVATE -> handleDeactivating(sliceKey);
                    case DEACTIVATING -> {} // Transitional state, no action
                    case FAILED -> handleFailed(sliceKey);
                    case UNLOAD -> handleUnloading(sliceKey);
                    case UNLOADING -> {} // Transitional state, no action
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
                // Slice is loaded and ready for activation
                // No automatic action - waiting for ACTIVATE command
                // TODO: we may need to send notification about slice readiness.
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
                // Register endpoints in EndpointRegistry (future implementation)
                // TODO: We may want to generate a notification for slice activation.
            }

            private void handleDeactivating(SliceNodeKey sliceKey) {
                executeWithStateTransition(
                    sliceKey,
                    SliceState.DEACTIVATING,
                    sliceStore.deactivateSlice(sliceKey.artifact()),
                    SliceState.LOADED,
                    SliceState.FAILED
                );
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
                                                       ClusterNode<KVCommand<AetherKey>> cluster) {
        return nodeDeploymentManager(self, router, sliceStore, cluster, SliceActionConfig.defaultConfiguration());
    }

    static NodeDeploymentManager nodeDeploymentManager(NodeId self,
                                                       MessageRouter router,
                                                       SliceStore sliceStore,
                                                       ClusterNode<KVCommand<AetherKey>> cluster,
                                                       SliceActionConfig configuration) {
        record deploymentManager(
                NodeId self,
                SliceStore sliceStore,
                ClusterNode<KVCommand<AetherKey>> cluster,
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
                    case ESTABLISHED ->
                            state().set(new NodeDeploymentState.ActiveNodeDeploymentState(
                                    self(),
                                    sliceStore(),
                                    configuration(),
                                    cluster(),
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
                configuration,
                router,
                new AtomicReference<>(new NodeDeploymentState.DormantNodeDeploymentState())
        );
    }
}
