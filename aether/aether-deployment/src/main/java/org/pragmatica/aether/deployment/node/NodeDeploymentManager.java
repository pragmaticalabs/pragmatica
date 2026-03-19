package org.pragmatica.aether.deployment.node;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.http.HttpRoutePublisher;
import org.pragmatica.aether.invoke.InvocationHandler;
import org.pragmatica.aether.metrics.deployment.DeploymentEvent.*;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceActionConfig;
import org.pragmatica.aether.slice.DefaultSliceBridge;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.EndpointKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.TopicSubscriptionKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.EndpointValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceNodeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopicSubscriptionValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.topology.QuorumStateNotification;
import org.pragmatica.aether.resource.ScheduleConfig;
import org.pragmatica.aether.resource.TopicConfig;
import org.pragmatica.config.ConfigService;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.messaging.MessageRouter;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;

@SuppressWarnings("JBCT-RET-01") // MessageReceiver callbacks — void required by messaging framework
public interface NodeDeploymentManager {
    record SliceDeployment(SliceNodeKey key, SliceState state, long timestamp) {}

    @MessageReceiver
    void onQuorumStateChange(QuorumStateNotification quorumStateNotification);

    @MessageReceiver
    void onNodeLifecyclePut(ValuePut<NodeLifecycleKey, NodeLifecycleValue> valuePut);

    /// Handle NodeArtifactKey put — converts to SliceNodeKey-based handling.
    @MessageReceiver
    void onNodeArtifactPut(ValuePut<NodeArtifactKey, NodeArtifactValue> valuePut);

    /// Handle NodeArtifactKey remove — converts to SliceNodeKey-based handling.
    @MessageReceiver
    void onNodeArtifactRemove(ValueRemove<NodeArtifactKey, NodeArtifactValue> valueRemove);

    /// Handle unexpected removal of local node's lifecycle key by re-registering ON_DUTY.
    /// Defense-in-depth: guards against race where a pending consensus batch removes the key
    /// after activation has already written it.
    @MessageReceiver
    void onNodeLifecycleRemove(ValueRemove<NodeLifecycleKey, NodeLifecycleValue> valueRemove);

    /// Set a shutdown callback to be invoked when SHUTTING_DOWN lifecycle state is received.
    void setShutdownCallback(Runnable callback);

    boolean isActive();

    /// Information about a suspended slice that can be reactivated.
    /// Tracks the slice key and the original deployment state.
    record SuspendedSlice(SliceNodeKey key, SliceDeployment deployment) {}

    sealed interface NodeDeploymentState {
        default void onNodeArtifactPut(ValuePut<NodeArtifactKey, NodeArtifactValue> valuePut) {}

        default void onNodeArtifactRemove(ValueRemove<NodeArtifactKey, NodeArtifactValue> valueRemove) {}

        default void onNodeLifecyclePut(ValuePut<NodeLifecycleKey, NodeLifecycleValue> valuePut) {}

        /// Dormant state with optional suspended slices for reactivation.
        record DormantNodeDeploymentState(List<SuspendedSlice> suspendedSlices) implements NodeDeploymentState {
            public DormantNodeDeploymentState() {
                this(List.of());
            }
        }

        record ActiveNodeDeploymentState(NodeId self,
                                         SliceStore sliceStore,
                                         SliceActionConfig configuration,
                                         SliceCodec nodeCodec,
                                         ClusterNode<KVCommand<AetherKey>> cluster,
                                         KVStore<AetherKey, AetherValue> kvStore,
                                         InvocationHandler invocationHandler,
                                         MessageRouter router,
                                         ConcurrentHashMap<SliceNodeKey, SliceDeployment> deployments,
                                         Option<HttpRoutePublisher> httpRoutePublisher,
                                         Option<SliceInvokerFacade> sliceInvokerFacade,
                                         TimeSpan activationChainTimeout,
                                         TimeSpan transitionRetryDelay) implements NodeDeploymentState {
            private static final Logger log = LoggerFactory.getLogger(ActiveNodeDeploymentState.class);

            private static final Fn1<Cause, SliceNodeKey> CLEANUP_FAILED = Causes.forOneValue("Failed to cleanup slice %s during abrupt removal");

            private static final Fn1<Cause, SliceNodeKey> STATE_UPDATE_FAILED = Causes.forOneValue("Failed to update slice state for %s");

            private static final Fn1<Cause, SliceNodeKey> UNLOAD_FAILED = Causes.forOneValue("Failed to unload slice %s");

            private Option<SliceStore.LoadedSlice> findLoadedSlice(Artifact artifact) {
                return Option.from(sliceStore.loaded()
                                             .stream()
                                             .filter(ls -> ls.artifact()
                                                             .equals(artifact))
                                             .findFirst());
            }

            @Override
            public void onNodeArtifactPut(ValuePut<NodeArtifactKey, NodeArtifactValue> valuePut) {
                var key = valuePut.cause()
                                  .key();
                if (key.isForNode(self)) {
                    var value = valuePut.cause()
                                        .value();
                    var sliceKey = new SliceNodeKey(key.artifact(), key.nodeId());
                    var sliceNodeValue = SliceNodeValue.sliceNodeValue(value.state());
                    log.debug("NodeArtifactKey put received for key: {}, state: {}", key, value.state());
                    recordDeployment(sliceKey, sliceNodeValue);
                    processStateTransition(sliceKey, value.state());
                }
            }

            @Override
            public void onNodeArtifactRemove(ValueRemove<NodeArtifactKey, NodeArtifactValue> valueRemove) {
                var key = valueRemove.cause()
                                     .key();
                if (key.isForNode(self)) {
                    var sliceKey = new SliceNodeKey(key.artifact(), key.nodeId());
                    handleSliceValueRemove(sliceKey);
                }
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

            private void recordDeployment(SliceNodeKey sliceKey, SliceNodeValue sliceNodeValue) {
                var state = sliceNodeValue.state();
                var timestamp = System.currentTimeMillis();
                var previousDeployment = Option.option(deployments.get(sliceKey));
                var previousState = previousDeployment.map(SliceDeployment::state);
                var deployment = new SliceDeployment(sliceKey, state, timestamp);
                deployments.put(sliceKey, deployment);
                // Emit state transition event for metrics via MessageRouter
                // For initial LOAD, use LOAD as both from and to (captures loadTime)
                var effectiveFromState = previousState.or(state);
                router.route(StateTransition.stateTransition(sliceKey.artifact(),
                                                             self,
                                                             effectiveFromState,
                                                             state,
                                                             timestamp));
                // Emit deployment failed event if transitioning to FAILED
                // We do this here because we have access to previousState
                if (state == SliceState.FAILED) {
                    var errorMessage = sliceNodeValue.failureReason()
                                                     .or("Unknown failure");
                    previousState.onPresent(prevState -> router.route(DeploymentFailed.deploymentFailed(sliceKey.artifact(),
                                                                                                        self,
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
                unpublishEndpoints(sliceKey).flatMap(_ -> unpublishTopicSubscriptions(sliceKey))
                                  .flatMap(_ -> unpublishScheduledTasks(sliceKey))
                                  .flatMap(_ -> unpublishHttpRoutes(sliceKey))
                                  .onSuccessRun(() -> unregisterSliceFromInvocation(sliceKey))
                                  .flatMap(_ -> sliceStore.deactivateSlice(sliceKey.artifact()))
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
                            .withFailure(cause -> handleLoadingFailure(sliceKey, cause));
            }

            private Promise<SliceStore.LoadedSlice> loadSliceWithTimeout(SliceNodeKey sliceKey) {
                return configuration.timeoutFor(SliceState.LOADING)
                                    .async()
                                    .flatMap(timeout -> sliceStore.loadSlice(sliceKey.artifact())
                                                                  .timeout(timeout));
            }

            private void handleLoadingFailure(SliceNodeKey sliceKey, Cause cause) {
                log.error("Failed to load slice {}: {}", sliceKey.artifact(), cause.message());
                transitionToFailed(sliceKey, cause);
            }

            private void handleLoaded(SliceNodeKey sliceKey) {
                // LOADED is a stable state - do nothing
                // ACTIVATE must be explicitly requested by ClusterDeploymentManager
                log.debug("Slice {} loaded, awaiting activation", sliceKey.artifact());
            }

            private void handleActivating(SliceNodeKey sliceKey) {
                // Only activate if slice is actually loaded in our store
                findLoadedSlice(sliceKey.artifact()).onEmpty(() -> handleSliceNotFoundForActivation(sliceKey))
                               .onPresent(_ -> performActivation(sliceKey));
            }

            private static final Fn1<Cause, String> SLICE_NOT_FOUND_FOR_ACTIVATION = Causes.forOneValue("Slice %s state is ACTIVATE but not found in SliceStore");
            private static final int MAX_TRANSITION_RETRIES = 5;

            private void handleSliceNotFoundForActivation(SliceNodeKey sliceKey) {
                var cause = SLICE_NOT_FOUND_FOR_ACTIVATION.apply(sliceKey.artifact()
                                                                         .asString());
                log.error("Slice {} state is ACTIVATE but not found in SliceStore", sliceKey.artifact());
                transitionToFailed(sliceKey, cause);
            }

            private void performActivation(SliceNodeKey sliceKey) {
                // 1. Write ACTIVATING first - wait for consensus before starting activation
                // 2. Routes are published AFTER ACTIVE transition to avoid forwarding traffic
                //    to a node that is still activating (cold start thundering herd)
                // 3. Overall chain timeout prevents slices stuck in ACTIVATING if any step hangs
                transitionTo(sliceKey, SliceState.ACTIVATING).flatMap(_ -> activateSliceWithTimeout(sliceKey))
                            .flatMap(_ -> registerSliceForInvocation(sliceKey))
                            .flatMap(_ -> publishTopicSubscriptions(sliceKey))
                            .flatMap(_ -> publishScheduledTasks(sliceKey))
                            .flatMap(_ -> transitionTo(sliceKey, SliceState.ACTIVE))
                            .flatMap(_ -> publishEndpointsAndRoutes(sliceKey))
                            .timeout(activationChainTimeout)
                            .withFailure(cause -> handleActivationFailure(sliceKey, cause));
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
                unpublishTopicSubscriptions(sliceKey);
                unpublishScheduledTasks(sliceKey);
                transitionToFailed(sliceKey, cause);
            }

            private void handleActive(SliceNodeKey sliceKey) {
                // All registration and publishing is done in handleActivating BEFORE transitioning to ACTIVE
                // Here we only emit the deployment completed event for metrics
                router.route(DeploymentCompleted.deploymentCompleted(sliceKey.artifact(),
                                                                     self,
                                                                     System.currentTimeMillis()));
            }

            private Promise<Unit> publishHttpRoutes(SliceNodeKey sliceKey) {
                var artifact = sliceKey.artifact();
                log.debug("publishHttpRoutes called for {} - httpRoutePublisher.isPresent={}, sliceInvokerFacade.isPresent={}",
                          artifact,
                          httpRoutePublisher.isPresent(),
                          sliceInvokerFacade.isPresent());
                return httpRoutePublisher.flatMap(publisher -> sliceInvokerFacade.flatMap(facade -> findLoadedSlice(artifact)
                .map(ls -> doPublishHttpRoutes(artifact, publisher, facade, ls))))
                                         .or(Promise.unitPromise());
            }

            private Promise<Unit> doPublishHttpRoutes(Artifact artifact,
                                                      HttpRoutePublisher publisher,
                                                      SliceInvokerFacade facade,
                                                      SliceStore.LoadedSlice ls) {
                var classLoader = ls.slice()
                                    .getClass()
                                    .getClassLoader();
                log.debug("Publishing HTTP routes for {} using classLoader={}",
                          artifact,
                          classLoader.getClass()
                                     .getName());
                return publisher.publishRoutes(artifact,
                                               classLoader,
                                               ls.slice(),
                                               facade)
                                .onFailure(cause -> log.warn("Failed to publish HTTP routes for {}: {}",
                                                             artifact,
                                                             cause.message()));
            }

            private Promise<Unit> registerSliceForInvocation(SliceNodeKey sliceKey) {
                var artifact = sliceKey.artifact();
                return findLoadedSlice(artifact).toResult(SLICE_NOT_LOADED_FOR_REGISTRATION.apply(artifact.asString()))
                                      .flatMap(ls -> registerSliceBridge(artifact,
                                                                         ls.slice()))
                                      .async();
            }

            private static final Fn1<Cause, String> SLICE_NOT_LOADED_FOR_REGISTRATION = Causes.forOneValue("Slice not loaded for registration: %s");

            private Result<Unit> registerSliceBridge(Artifact artifact, Slice slice) {
                var sliceCodec = slice.codec(nodeCodec);
                var sliceBridge = DefaultSliceBridge.defaultSliceBridge(artifact, slice, sliceCodec);
                invocationHandler.registerSlice(artifact, sliceBridge);
                log.debug("Registered slice {} for invocation", artifact);
                return Result.unitResult();
            }

            private void unregisterSliceFromInvocation(SliceNodeKey sliceKey) {
                var artifact = sliceKey.artifact();
                invocationHandler.unregisterSlice(artifact);
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
                int instanceNumber = Math.abs(self.id()
                                                  .hashCode());
                var methodNames = methods.stream()
                                         .map(m -> m.name()
                                                    .name())
                                         .toList();
                var nodeArtifactKey = NodeArtifactKey.nodeArtifactKey(self, artifact);
                var nodeArtifactValue = NodeArtifactValue.activeNodeArtifactValue(instanceNumber, methodNames);
                KVCommand<AetherKey> command = new KVCommand.Put<>(nodeArtifactKey, nodeArtifactValue);
                return cluster.apply(List.of(command))
                              .mapToUnit()
                              .onSuccess(_ -> log.debug("Published {} endpoints for slice {}",
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
                // Slice not loaded, just transition to LOADED
                log.warn("Slice {} not found in store during deactivation, transitioning to LOADED", sliceKey.artifact());
                transitionTo(sliceKey, SliceState.LOADED);
            }

            private void performDeactivation(SliceNodeKey sliceKey) {
                // 1. Write DEACTIVATING first - wait for consensus before starting deactivation
                transitionTo(sliceKey, SliceState.DEACTIVATING).flatMap(_ -> unpublishEndpoints(sliceKey))
                            .flatMap(_ -> unpublishTopicSubscriptions(sliceKey))
                            .flatMap(_ -> unpublishScheduledTasks(sliceKey))
                            .flatMap(_ -> unpublishHttpRoutes(sliceKey))
                            .withSuccess(_ -> unregisterSliceFromInvocation(sliceKey))
                            .flatMap(_ -> deactivateSliceWithTimeout(sliceKey))
                            .flatMap(_ -> transitionTo(sliceKey, SliceState.LOADED))
                            .withFailure(cause -> handleDeactivationFailure(sliceKey, cause));
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
                transitionToFailed(sliceKey, cause);
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

            private Promise<Unit> unpublishEndpointsForSlice(Artifact artifact, Slice slice) {
                var methods = slice.methods();
                if (methods.isEmpty()) {
                    return Promise.unitPromise();
                }
                // Write NodeArtifactKey with empty methods to clear endpoint info
                var nodeArtifactKey = NodeArtifactKey.nodeArtifactKey(self, artifact);
                var nodeArtifactValue = NodeArtifactValue.nodeArtifactValue(SliceState.ACTIVE);
                KVCommand<AetherKey> command = new KVCommand.Put<>(nodeArtifactKey, nodeArtifactValue);
                return cluster.apply(List.of(command))
                              .mapToUnit()
                              .onSuccess(_ -> log.debug("Unpublished {} endpoints for slice {}",
                                                        methods.size(),
                                                        artifact))
                              .onFailure(cause -> log.error("Failed to unpublish endpoints for {}: {}",
                                                            artifact,
                                                            cause.message()));
            }

            // --- Topic subscription publish/unpublish ---
            private Promise<Unit> publishTopicSubscriptions(SliceNodeKey sliceKey) {
                var artifact = sliceKey.artifact();
                return findLoadedSlice(artifact).map(ls -> doPublishTopicSubscriptions(artifact,
                                                                                       ls.slice()))
                                      .or(Promise.unitPromise());
            }

            private Promise<Unit> doPublishTopicSubscriptions(Artifact artifact, Slice slice) {
                var entries = readSubscriptionsFromManifest(slice);
                if (entries.isEmpty()) {
                    return Promise.unitPromise();
                }
                var commands = entries.stream()
                                      .<KVCommand<AetherKey>> map(entry -> createTopicSubscriptionPutCommand(artifact,
                                                                                                             entry))
                                      .toList();
                return cluster.apply(commands)
                              .mapToUnit()
                              .onSuccess(_ -> log.debug("Published {} topic subscriptions for {}",
                                                        entries.size(),
                                                        artifact))
                              .onFailure(cause -> log.error("Failed to publish topic subscriptions for {}: {}",
                                                            artifact,
                                                            cause.message()));
            }

            private KVCommand<AetherKey> createTopicSubscriptionPutCommand(Artifact artifact,
                                                                           SubscriptionManifestEntry entry) {
                var key = TopicSubscriptionKey.topicSubscriptionKey(entry.topicName(), artifact, entry.methodName());
                var value = TopicSubscriptionValue.topicSubscriptionValue(self);
                return new KVCommand.Put<>(key, value);
            }

            private Promise<Unit> unpublishTopicSubscriptions(SliceNodeKey sliceKey) {
                var artifact = sliceKey.artifact();
                return findLoadedSlice(artifact).map(ls -> doUnpublishTopicSubscriptions(artifact,
                                                                                         ls.slice()))
                                      .or(Promise.unitPromise());
            }

            private Promise<Unit> doUnpublishTopicSubscriptions(Artifact artifact, Slice slice) {
                var entries = readSubscriptionsFromManifest(slice);
                if (entries.isEmpty()) {
                    return Promise.unitPromise();
                }
                var commands = entries.stream()
                                      .<KVCommand<AetherKey>> map(entry -> createTopicSubscriptionRemoveCommand(artifact,
                                                                                                                entry))
                                      .toList();
                return cluster.apply(commands)
                              .mapToUnit()
                              .onSuccess(_ -> log.debug("Unpublished {} topic subscriptions for {}",
                                                        entries.size(),
                                                        artifact))
                              .onFailure(cause -> log.error("Failed to unpublish topic subscriptions for {}: {}",
                                                            artifact,
                                                            cause.message()));
            }

            private KVCommand<AetherKey> createTopicSubscriptionRemoveCommand(Artifact artifact,
                                                                              SubscriptionManifestEntry entry) {
                var key = TopicSubscriptionKey.topicSubscriptionKey(entry.topicName(), artifact, entry.methodName());
                return new KVCommand.Remove<>(key);
            }

            private record SubscriptionManifestEntry(String topicName, MethodName methodName) {}

            private record ScheduledTaskManifestEntry(String configSection, MethodName methodName) {}

            @SuppressWarnings("JBCT-EX-01")
            private List<SubscriptionManifestEntry> readSubscriptionsFromManifest(Slice slice) {
                var result = new ArrayList<SubscriptionManifestEntry>();
                var classLoader = slice.getClass()
                                       .getClassLoader();
                for (var iface : slice.getClass()
                                      .getInterfaces()) {
                    if (iface == Slice.class) {
                        continue;
                    }
                    var manifestPath = "META-INF/slice/" + iface.getSimpleName() + ".manifest";
                    readSubscriptionEntriesFromManifest(classLoader, manifestPath, result);
                }
                return result;
            }

            @SuppressWarnings("JBCT-EX-01")
            private void readSubscriptionEntriesFromManifest(ClassLoader classLoader,
                                                             String manifestPath,
                                                             List<SubscriptionManifestEntry> result) {
                try (var is = classLoader.getResourceAsStream(manifestPath)) {
                    if (is == null) {
                        return;
                    }
                    var props = new Properties();
                    props.load(is);
                    var count = Integer.parseInt(props.getProperty("topic.subscriptions.count", "0"));
                    for (int i = 0; i < count; i++) {
                        readSubscriptionEntry(props, i).onPresent(result::add);
                    }
                } catch (Exception e) {
                    log.debug("Could not read subscription manifest {}: {}", manifestPath, e.getMessage());
                }
            }

            private Option<SubscriptionManifestEntry> readSubscriptionEntry(Properties props, int index) {
                var configSection = props.getProperty("topic.subscription." + index + ".config");
                var methodNameStr = props.getProperty("topic.subscription." + index + ".method");
                if (configSection == null || methodNameStr == null) {
                    return Option.none();
                }
                return resolveTopicName(configSection).flatMap(topicName -> MethodName.methodName(methodNameStr)
                                                                                      .map(method -> new SubscriptionManifestEntry(topicName,
                                                                                                                                   method)))
                                       .option();
            }

            private Result<String> resolveTopicName(String configSection) {
                return ConfigService.instance()
                                    .toResult(Causes.cause("ConfigService not available for topic resolution"))
                                    .flatMap(svc -> svc.config(configSection, TopicConfig.class))
                                    .map(TopicConfig::topicName);
            }

            // --- Scheduled task publish/unpublish ---
            private Promise<Unit> publishScheduledTasks(SliceNodeKey sliceKey) {
                var artifact = sliceKey.artifact();
                return findLoadedSlice(artifact).map(ls -> doPublishScheduledTasks(artifact,
                                                                                   ls.slice()))
                                      .or(Promise.unitPromise());
            }

            private Promise<Unit> doPublishScheduledTasks(Artifact artifact, Slice slice) {
                var entries = readScheduledTasksFromManifest(slice);
                if (entries.isEmpty()) {
                    return Promise.unitPromise();
                }
                var commands = new ArrayList<KVCommand<AetherKey>>();
                for (var entry : entries) {
                    resolveScheduleConfig(entry.configSection())
                    .onPresent(config -> commands.add(createScheduledTaskPutCommand(artifact, entry, config)));
                }
                if (commands.isEmpty()) {
                    return Promise.unitPromise();
                }
                return cluster.apply(commands)
                              .mapToUnit()
                              .onSuccess(_ -> log.debug("Published {} scheduled tasks for {}",
                                                        commands.size(),
                                                        artifact))
                              .onFailure(cause -> log.error("Failed to publish scheduled tasks for {}: {}",
                                                            artifact,
                                                            cause.message()));
            }

            private KVCommand<AetherKey> createScheduledTaskPutCommand(Artifact artifact,
                                                                       ScheduledTaskManifestEntry entry,
                                                                       ScheduleConfig config) {
                var key = ScheduledTaskKey.scheduledTaskKey(entry.configSection(), artifact, entry.methodName());
                var value = config.interval()
                                  .isEmpty()
                            ? ScheduledTaskValue.cronTask(self, config.cron(), config.executionMode())
                            : ScheduledTaskValue.intervalTask(self, config.interval(), config.executionMode());
                return new KVCommand.Put<>(key, value);
            }

            private Promise<Unit> unpublishScheduledTasks(SliceNodeKey sliceKey) {
                var artifact = sliceKey.artifact();
                return findLoadedSlice(artifact).map(ls -> doUnpublishScheduledTasks(artifact,
                                                                                     ls.slice()))
                                      .or(Promise.unitPromise());
            }

            private Promise<Unit> doUnpublishScheduledTasks(Artifact artifact, Slice slice) {
                var entries = readScheduledTasksFromManifest(slice);
                if (entries.isEmpty()) {
                    return Promise.unitPromise();
                }
                var commands = entries.stream()
                                      .<KVCommand<AetherKey>> map(entry -> createScheduledTaskRemoveCommand(artifact,
                                                                                                            entry))
                                      .toList();
                return cluster.apply(commands)
                              .mapToUnit()
                              .onSuccess(_ -> log.debug("Unpublished {} scheduled tasks for {}",
                                                        entries.size(),
                                                        artifact))
                              .onFailure(cause -> log.error("Failed to unpublish scheduled tasks for {}: {}",
                                                            artifact,
                                                            cause.message()));
            }

            private KVCommand<AetherKey> createScheduledTaskRemoveCommand(Artifact artifact,
                                                                          ScheduledTaskManifestEntry entry) {
                var key = ScheduledTaskKey.scheduledTaskKey(entry.configSection(), artifact, entry.methodName());
                return new KVCommand.Remove<>(key);
            }

            @SuppressWarnings("JBCT-EX-01")
            private List<ScheduledTaskManifestEntry> readScheduledTasksFromManifest(Slice slice) {
                var result = new ArrayList<ScheduledTaskManifestEntry>();
                var classLoader = slice.getClass()
                                       .getClassLoader();
                for (var iface : slice.getClass()
                                      .getInterfaces()) {
                    if (iface == Slice.class) {
                        continue;
                    }
                    var manifestPath = "META-INF/slice/" + iface.getSimpleName() + ".manifest";
                    readScheduledTaskEntriesFromManifest(classLoader, manifestPath, result);
                }
                return result;
            }

            @SuppressWarnings("JBCT-EX-01")
            private void readScheduledTaskEntriesFromManifest(ClassLoader classLoader,
                                                              String manifestPath,
                                                              List<ScheduledTaskManifestEntry> result) {
                try (var is = classLoader.getResourceAsStream(manifestPath)) {
                    if (is == null) {
                        return;
                    }
                    var props = new Properties();
                    props.load(is);
                    var count = Integer.parseInt(props.getProperty("scheduled.tasks.count", "0"));
                    for (int i = 0; i < count; i++) {
                        readScheduledTaskEntry(props, i).onPresent(result::add);
                    }
                } catch (Exception e) {
                    log.debug("Could not read scheduled task manifest {}: {}", manifestPath, e.getMessage());
                }
            }

            private Option<ScheduledTaskManifestEntry> readScheduledTaskEntry(Properties props, int index) {
                var configSection = props.getProperty("scheduled.task." + index + ".config");
                var methodNameStr = props.getProperty("scheduled.task." + index + ".method");
                if (configSection == null || methodNameStr == null) {
                    return Option.none();
                }
                return MethodName.methodName(methodNameStr)
                                 .map(method -> new ScheduledTaskManifestEntry(configSection, method))
                                 .option();
            }

            private Option<ScheduleConfig> resolveScheduleConfig(String configSection) {
                return ConfigService.instance()
                                    .flatMap(svc -> svc.config(configSection, ScheduleConfig.class)
                                                       .option());
            }

            private void handleFailed(SliceNodeKey sliceKey) {
                // Log the failure for observability
                log.warn("Slice {} entered FAILED state", sliceKey.artifact());
            }

            private void handleUnloading(SliceNodeKey sliceKey) {
                // 1. Write UNLOADING to KV first - wait for consensus before starting unload
                // 2. Clean up published resources (routes, endpoints, topics, tasks) before unloading —
                //    UNLOAD may skip DEACTIVATE, so cleanup must happen here too
                transitionTo(sliceKey, SliceState.UNLOADING).flatMap(_ -> unpublishEndpoints(sliceKey))
                            .flatMap(_ -> unpublishTopicSubscriptions(sliceKey))
                            .flatMap(_ -> unpublishScheduledTasks(sliceKey))
                            .flatMap(_ -> unpublishHttpRoutes(sliceKey))
                            .withSuccess(_ -> unregisterSliceFromInvocation(sliceKey))
                            .flatMap(_ -> unloadSliceWithTimeout(sliceKey))
                            .flatMap(_ -> deleteSliceNodeKey(sliceKey))
                            .withSuccess(_ -> removeFromDeployments(sliceKey))
                            .withFailure(cause -> handleUnloadFailure(sliceKey, cause));
            }

            private Promise<Unit> unloadSliceWithTimeout(SliceNodeKey sliceKey) {
                return configuration.timeoutFor(SliceState.UNLOADING)
                                    .async()
                                    .flatMap(timeout -> sliceStore.unloadSlice(sliceKey.artifact())
                                                                  .timeout(timeout));
            }

            private void handleUnloadFailure(SliceNodeKey sliceKey, Cause cause) {
                log.error("Failed to unload {}: {}", sliceKey.artifact(), cause.message());
                // Delete KV key even on failure to prevent stuck UNLOADING state
                deleteSliceNodeKey(sliceKey).onSuccess(_ -> removeFromDeployments(sliceKey))
                                  .onFailure(deleteCause -> log.error("Failed to delete slice-node-key {} after unload failure: {}",
                                                                      sliceKey,
                                                                      deleteCause.message()));
            }

            private Promise<Unit> deleteSliceNodeKey(SliceNodeKey sliceKey) {
                var nodeArtifactKey = NodeArtifactKey.nodeArtifactKey(self, sliceKey.artifact());
                KVCommand<AetherKey> removeArtifact = new KVCommand.Remove<>(nodeArtifactKey);
                return cluster.apply(List.of(removeArtifact))
                              .mapToUnit()
                              .onSuccess(_ -> log.debug("Deleted node-artifact-key {} from KV-Store", nodeArtifactKey));
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
                             .async()
                             .flatMap(timeout -> applyTimeout(sliceKey, operation, timeout))
                             .onSuccess(_ -> handleTransitionSuccess(sliceKey, successState))
                             .onFailure(cause -> handleTransitionFailure(sliceKey, failureState, cause));
            }

            private Promise<?> applyTimeout(SliceNodeKey sliceKey,
                                            Promise<?> operation,
                                            org.pragmatica.lang.io.TimeSpan timeout) {
                log.debug("Got timeout {} for {}, setting up callbacks", timeout, sliceKey.artifact());
                return operation.timeout(timeout);
            }

            private void handleTransitionSuccess(SliceNodeKey sliceKey, SliceState successState) {
                log.debug("Operation succeeded for {}, transitioning to {}", sliceKey.artifact(), successState);
                transitionTo(sliceKey, successState);
            }

            private void handleTransitionFailure(SliceNodeKey sliceKey, SliceState failureState, Cause cause) {
                log.warn("Operation failed for {}: {}, transitioning to {}",
                         sliceKey.artifact(),
                         cause.message(),
                         failureState);
                transitionTo(sliceKey, failureState);
            }

            private Promise<Unit> transitionTo(SliceNodeKey sliceKey, SliceState newState) {
                return updateSliceState(sliceKey, SliceNodeValue.sliceNodeValue(newState));
            }

            private Promise<Unit> transitionToFailed(SliceNodeKey sliceKey, Cause cause) {
                return transitionToFailedWithRetry(sliceKey, cause, 0);
            }

            private Promise<Unit> transitionToFailedWithRetry(SliceNodeKey sliceKey, Cause originalCause, int attempt) {
                return updateSliceState(sliceKey, SliceNodeValue.failedSliceNodeValue(originalCause))
                .onFailure(writeCause -> handleFailedTransitionRetry(sliceKey, originalCause, writeCause, attempt));
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
                             transitionRetryDelay.millis(),
                             writeCause.message());
                    SharedScheduler.schedule(() -> transitionToFailedWithRetry(sliceKey, originalCause, attempt + 1),
                                             transitionRetryDelay);
                } else {
                    log.error("CRITICAL: Failed to write FAILED state for {} after {} attempts. Slice stuck in transitional state.",
                              sliceKey.artifact(),
                              MAX_TRANSITION_RETRIES);
                }
            }

            private void removeFromDeployments(SliceNodeKey sliceKey) {
                deployments.remove(sliceKey);
            }

            private Promise<Unit> updateSliceState(SliceNodeKey sliceKey, SliceNodeValue value) {
                log.debug("updateSliceState: {} -> {}",
                          sliceKey,
                          value.state());
                var nodeArtifactKey = NodeArtifactKey.nodeArtifactKey(self, sliceKey.artifact());
                var nodeArtifactValue = value.state() == SliceState.FAILED
                                        ? NodeArtifactValue.failedNodeArtifactValue(Causes.cause(value.failureReason()
                                                                                                      .or("Unknown failure")))
                                        : NodeArtifactValue.nodeArtifactValue(value.state());
                KVCommand<AetherKey> putArtifact = new KVCommand.Put<>(nodeArtifactKey, nodeArtifactValue);
                return cluster.apply(List.of(putArtifact))
                              .mapToUnit()
                              .onSuccess(_ -> log.debug("State update succeeded: {} -> {}",
                                                        sliceKey.artifact(),
                                                        value.state()))
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

            /// Suspend all active slices on quorum loss without unloading them.
            /// Returns the list of suspended slices for potential reactivation.
            ///
            ///
            /// This method:
            ///
            ///   - Unpublishes HTTP routes (removes from local handlers map)
            ///   - Unpublishes endpoints (note: KV commands won't commit without quorum)
            ///   - Unregisters slices from invocation handler
            ///   - Does NOT unload slices from SliceStore
            ///   - Does NOT clear deployments - saves them for reactivation
            ///
            ///
            /// @return List of suspended slices that can be reactivated when quorum returns
            List<SuspendedSlice> suspendSlices() {
                log.warn("Suspending {} slices due to quorum loss (keeping loaded in memory)", deployments.size());
                var suspended = new java.util.ArrayList<SuspendedSlice>();
                for (var entry : deployments.entrySet()) {
                    var sliceKey = entry.getKey();
                    var deployment = entry.getValue();
                    if (deployment.state() == SliceState.ACTIVE) {
                        log.debug("Suspending active slice {}", sliceKey.artifact());
                        // Unpublish routes and endpoints (local state only - KV won't commit without quorum)
                        suspendSlice(sliceKey);
                        suspended.add(new SuspendedSlice(sliceKey, deployment));
                    } else {
                        log.debug("Slice {} in state {} - not suspending", sliceKey.artifact(), deployment.state());
                    }
                }
                log.debug("Suspended {} active slices, ready for reactivation", suspended.size());
                return suspended;
            }

            /// Suspend a single slice: unpublish routes/endpoints and unregister from invocation.
            /// Does NOT unload the slice from SliceStore.
            private void suspendSlice(SliceNodeKey sliceKey) {
                // Unpublish HTTP routes (local handler map only)
                httpRoutePublisher.onPresent(publisher -> unpublishRoutesForSuspension(publisher, sliceKey));
                // Unregister from invocation handler
                unregisterSliceFromInvocation(sliceKey);
            }

            private void unpublishRoutesForSuspension(HttpRoutePublisher publisher, SliceNodeKey sliceKey) {
                publisher.unpublishRoutes(sliceKey.artifact());
                log.debug("Unpublished HTTP routes for suspended slice {}", sliceKey.artifact());
            }

            /// Reactivate previously suspended slices after quorum is restored.
            ///
            ///
            /// For each suspended slice:
            ///
            ///   - Check if slice is still loaded in SliceStore
            ///   - Re-register with invocation handler
            ///   - Re-publish HTTP routes
            ///   - Re-publish endpoints to KV store
            ///
            ///
            /// @param suspended List of suspended slices to reactivate
            void reactivateSuspendedSlices(List<SuspendedSlice> suspended) {
                if (suspended.isEmpty()) {
                    log.debug("No suspended slices to reactivate");
                    return;
                }
                log.info("Reactivating {} suspended slices after quorum restored", suspended.size());
                for (var suspendedSlice : suspended) {
                    var sliceKey = suspendedSlice.key();
                    var deployment = suspendedSlice.deployment();
                    // Restore deployment record
                    deployments.put(sliceKey, deployment);
                    // Check if slice is still in SliceStore
                    var loadedSlice = findLoadedSlice(sliceKey.artifact());
                    if (loadedSlice.isEmpty()) {
                        log.warn("Suspended slice {} no longer in SliceStore, skipping reactivation",
                                 sliceKey.artifact());
                        deployments.remove(sliceKey);
                        continue;
                    }
                    log.debug("Reactivating suspended slice {}", sliceKey.artifact());
                    // Re-register for invocation
                    registerSliceForInvocation(sliceKey).flatMap(_ -> publishEndpointsAndRoutes(sliceKey))
                                              .flatMap(_ -> publishTopicSubscriptions(sliceKey))
                                              .flatMap(_ -> publishScheduledTasks(sliceKey))
                                              .onSuccess(_ -> log.debug("Reactivated slice {}",
                                                                        sliceKey.artifact()))
                                              .onFailure(cause -> handleReactivationFailure(sliceKey, cause));
                }
            }

            private void handleReactivationFailure(SliceNodeKey sliceKey, Cause cause) {
                log.error("Failed to reactivate slice {}: {}", sliceKey.artifact(), cause.message());
                unregisterSliceFromInvocation(sliceKey);
                unpublishEndpoints(sliceKey).flatMap(_ -> unpublishTopicSubscriptions(sliceKey))
                                  .flatMap(_ -> unpublishScheduledTasks(sliceKey))
                                  .flatMap(_ -> unpublishHttpRoutes(sliceKey));
                deployments.remove(sliceKey);
            }

            /// Deactivate all slices on quorum loss.
            /// Called before transitioning to dormant state.
            ///
            /// @deprecated Use {@link #suspendSlices()} instead to preserve slices in memory.
            ///             This method is kept for actual slice removal (ValueRemove).
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

    /// Default activation chain timeout (2 minutes).
    TimeSpan DEFAULT_ACTIVATION_CHAIN_TIMEOUT = TimeSpan.timeSpan(120_000)
                                                       .millis();

    /// Default transition retry delay (2 seconds).
    TimeSpan DEFAULT_TRANSITION_RETRY_DELAY = TimeSpan.timeSpan(2000)
                                                     .millis();

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
                                     SliceActionConfig.sliceActionConfig(),
                                     SliceCodec.sliceCodec(List.of()),
                                     Option.none(),
                                     Option.none(),
                                     DEFAULT_ACTIVATION_CHAIN_TIMEOUT,
                                     DEFAULT_TRANSITION_RETRY_DELAY);
    }

    static NodeDeploymentManager nodeDeploymentManager(NodeId self,
                                                       MessageRouter router,
                                                       SliceStore sliceStore,
                                                       ClusterNode<KVCommand<AetherKey>> cluster,
                                                       KVStore<AetherKey, AetherValue> kvStore,
                                                       InvocationHandler invocationHandler,
                                                       SliceActionConfig configuration,
                                                       SliceCodec nodeCodec,
                                                       Option<HttpRoutePublisher> httpRoutePublisher,
                                                       Option<SliceInvokerFacade> sliceInvokerFacade) {
        return nodeDeploymentManager(self,
                                     router,
                                     sliceStore,
                                     cluster,
                                     kvStore,
                                     invocationHandler,
                                     configuration,
                                     nodeCodec,
                                     httpRoutePublisher,
                                     sliceInvokerFacade,
                                     DEFAULT_ACTIVATION_CHAIN_TIMEOUT,
                                     DEFAULT_TRANSITION_RETRY_DELAY);
    }

    static NodeDeploymentManager nodeDeploymentManager(NodeId self,
                                                       MessageRouter router,
                                                       SliceStore sliceStore,
                                                       ClusterNode<KVCommand<AetherKey>> cluster,
                                                       KVStore<AetherKey, AetherValue> kvStore,
                                                       InvocationHandler invocationHandler,
                                                       SliceActionConfig configuration,
                                                       SliceCodec nodeCodec,
                                                       Option<HttpRoutePublisher> httpRoutePublisher,
                                                       Option<SliceInvokerFacade> sliceInvokerFacade,
                                                       TimeSpan activationChainTimeout,
                                                       TimeSpan transitionRetryDelay) {
        record deploymentManager(NodeId self,
                                 SliceStore sliceStore,
                                 ClusterNode<KVCommand<AetherKey>> cluster,
                                 KVStore<AetherKey, AetherValue> kvStore,
                                 InvocationHandler invocationHandler,
                                 SliceActionConfig configuration,
                                 SliceCodec nodeCodec,
                                 MessageRouter router,
                                 AtomicReference<NodeDeploymentState> state,
                                 AtomicLong quorumSequence,
                                 Option<HttpRoutePublisher> httpRoutePublisher,
                                 Option<SliceInvokerFacade> sliceInvokerFacade,
                                 TimeSpan activationChainTimeout,
                                 TimeSpan transitionRetryDelay,
                                 AtomicReference<Runnable> shutdownCallback) implements NodeDeploymentManager {
            private static final Logger log = LoggerFactory.getLogger(NodeDeploymentManager.class);

            @Override
            public void onNodeArtifactPut(ValuePut<NodeArtifactKey, NodeArtifactValue> valuePut) {
                state.get()
                     .onNodeArtifactPut(valuePut);
            }

            @Override
            public void onNodeArtifactRemove(ValueRemove<NodeArtifactKey, NodeArtifactValue> valueRemove) {
                state.get()
                     .onNodeArtifactRemove(valueRemove);
            }

            @Override
            public void onQuorumStateChange(QuorumStateNotification quorumStateNotification) {
                if (!quorumStateNotification.advanceSequence(quorumSequence)) {
                    log.info("Node {} ignoring stale QuorumStateNotification: {}", self().id(), quorumStateNotification);
                    return;
                }
                log.info("Node {} received QuorumStateNotification: {}", self().id(), quorumStateNotification);
                switch (quorumStateNotification.state()) {
                    case ESTABLISHED -> {
                        // Check if we have suspended slices to reactivate
                        if (state().get() instanceof NodeDeploymentState.DormantNodeDeploymentState dormant) {
                            var suspended = dormant.suspendedSlices();
                            // Create new active state with fresh deployments map
                            var activeState = new NodeDeploymentState.ActiveNodeDeploymentState(self(),
                                                                                                sliceStore(),
                                                                                                configuration(),
                                                                                                nodeCodec(),
                                                                                                cluster(),
                                                                                                kvStore(),
                                                                                                invocationHandler(),
                                                                                                router(),
                                                                                                new ConcurrentHashMap<>(),
                                                                                                httpRoutePublisher(),
                                                                                                sliceInvokerFacade(),
                                                                                                activationChainTimeout(),
                                                                                                transitionRetryDelay());
                            state().set(activeState);
                            log.info("Node {} NodeDeploymentManager activated", self().id());
                            // Register ON_DUTY lifecycle state (always writes unless DECOMMISSIONED)
                            registerLifecycleOnDuty();
                            // Process any pending LOAD commands that arrived before NDM activated.
                            // CDM may issue LOAD via consensus before NDM transitions from Dormant,
                            // causing the KV notification to be silently dropped.
                            processPendingLoadCommands(activeState);
                            // Reactivate suspended slices if any
                            if (!suspended.isEmpty()) {
                                log.info("Node {} has {} suspended slices to reactivate", self().id(), suspended.size());
                                activeState.reactivateSuspendedSlices(suspended);
                            }
                        }
                    }
                    case DISAPPEARED -> {
                        // Suspend slices (keep in memory) before going dormant
                        var suspended = List.<SuspendedSlice>of();
                        if (state().get() instanceof NodeDeploymentState.ActiveNodeDeploymentState activeState) {
                            suspended = activeState.suspendSlices();
                        }
                        // Only go dormant if no newer notification arrived during cleanup
                        if (quorumSequence.get() == quorumStateNotification.sequence()) {
                            state().set(new NodeDeploymentState.DormantNodeDeploymentState(suspended));
                            log.info("Node {} NodeDeploymentManager deactivated with {} suspended slices",
                                     self().id(),
                                     suspended.size());
                        } else {
                            log.info("Node {} ignoring stale DISAPPEARED (newer notification arrived during cleanup)",
                                     self().id());
                        }
                    }
                }
            }

            private void processPendingLoadCommands(NodeDeploymentState.ActiveNodeDeploymentState activeState) {
                kvStore().forEach(NodeArtifactKey.class,
                                  NodeArtifactValue.class,
                                  (key, value) -> {
                                      if (key.isForNode(self()) && value.state() == SliceState.LOAD) {
                                          log.info("Node {} found pending LOAD command for {}, processing",
                                                   self().id(),
                                                   key.artifact());
                                          var sliceKey = new SliceNodeKey(key.artifact(), key.nodeId());
                                          activeState.recordDeployment(sliceKey,
                                                                       SliceNodeValue.sliceNodeValue(value.state()));
                                          activeState.processStateTransition(sliceKey, value.state());
                                      }
                                  });
            }

            private static final int MAX_LIFECYCLE_RETRIES = 60;

            private void registerLifecycleOnDuty() {
                var lifecycleKey = AetherKey.NodeLifecycleKey.nodeLifecycleKey(self());
                // Unconditionally write ON_DUTY unless node is DECOMMISSIONED.
                // Must always write because consensus snapshot restore may contain a stale
                // ON_DUTY entry that a pending removal batch will delete after activation.
                kvStore().get(lifecycleKey)
                       .flatMap(v -> v instanceof NodeLifecycleValue lv
                                     ? Option.some(lv)
                                     : Option.empty())
                       .filter(v -> v.state() == NodeLifecycleState.DECOMMISSIONED)
                       .onEmpty(() -> writeLifecycleOnDuty(lifecycleKey, 1));
            }

            private void writeLifecycleOnDuty(AetherKey.NodeLifecycleKey lifecycleKey, int attempt) {
                var value = AetherValue.NodeLifecycleValue.nodeLifecycleValue(AetherValue.NodeLifecycleState.ON_DUTY);
                cluster().apply(List.of(new KVCommand.Put<>(lifecycleKey, value)))
                       .onSuccess(_ -> log.info("Node {} registered lifecycle state: ON_DUTY",
                                                self().id()))
                       .onFailure(cause -> retryLifecycleOnDuty(lifecycleKey, attempt, cause));
            }

            private void retryLifecycleOnDuty(AetherKey.NodeLifecycleKey lifecycleKey, int attempt, Cause cause) {
                if (attempt >= MAX_LIFECYCLE_RETRIES) {
                    log.error("Node {} failed to register lifecycle ON_DUTY after {} attempts: {}",
                              self().id(),
                              attempt,
                              cause.message());
                    return;
                }
                if (!isActive()) {
                    log.debug("Node {} skipping ON_DUTY retry — no longer active", self().id());
                    return;
                }
                log.warn("Node {} failed to register lifecycle ON_DUTY (attempt {}/{}): {} — retrying in 1s",
                         self().id(),
                         attempt,
                         MAX_LIFECYCLE_RETRIES,
                         cause.message());
                SharedScheduler.schedule(() -> writeLifecycleOnDuty(lifecycleKey, attempt + 1), timeSpan(2).seconds());
            }

            @Override
            public boolean isActive() {
                return state().get() instanceof NodeDeploymentState.ActiveNodeDeploymentState;
            }

            @Override
            public void onNodeLifecyclePut(ValuePut<AetherKey.NodeLifecycleKey, AetherValue.NodeLifecycleValue> valuePut) {
                var key = valuePut.cause()
                                  .key();
                var value = valuePut.cause()
                                    .value();
                // Only watch our own lifecycle key
                if (key.nodeId()
                       .equals(self()) && value.state() == AetherValue.NodeLifecycleState.SHUTTING_DOWN) {
                    log.warn("Node {} received SHUTTING_DOWN lifecycle state — initiating shutdown", self().id());
                    Option.option(shutdownCallback().get())
                          .onPresent(Runnable::run);
                }
            }

            @Override
            public void onNodeLifecycleRemove(ValueRemove<NodeLifecycleKey, NodeLifecycleValue> valueRemove) {
                var key = valueRemove.cause()
                                     .key();
                if (key.nodeId()
                       .equals(self()) && isActive()) {
                    log.warn("Node {} lifecycle key removed unexpectedly — re-registering ON_DUTY", self().id());
                    registerLifecycleOnDuty();
                }
            }

            @Override
            public void setShutdownCallback(Runnable callback) {
                shutdownCallback().set(callback);
            }
        }
        return new deploymentManager(self,
                                     sliceStore,
                                     cluster,
                                     kvStore,
                                     invocationHandler,
                                     configuration,
                                     nodeCodec,
                                     router,
                                     new AtomicReference<>(new NodeDeploymentState.DormantNodeDeploymentState()),
                                     new AtomicLong(0),
                                     httpRoutePublisher,
                                     sliceInvokerFacade,
                                     activationChainTimeout,
                                     transitionRetryDelay,
                                     new AtomicReference<>());
    }
}
