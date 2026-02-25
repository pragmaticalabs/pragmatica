package org.pragmatica.aether.invoke;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.endpoint.EndpointRegistry;
import org.pragmatica.aether.endpoint.EndpointRegistry.Endpoint;
import org.pragmatica.aether.invoke.InvocationMessage.InvokeRequest;
import org.pragmatica.aether.invoke.InvocationMessage.InvokeResponse;
import org.pragmatica.aether.slice.MethodHandle;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.SliceBridge;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.update.RollingUpdate;
import org.pragmatica.aether.update.RollingUpdateManager;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.utility.KSUID;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Client-side component for invoking methods on remote slices.
///
///
/// Supports two invocation patterns:
///
///   - Fire-and-forget: {@link #invoke(Artifact, MethodName, Object)}
///   - Request-response: {@link #invoke(Artifact, MethodName, Object, TypeToken)}
///
///
///
/// Uses the EndpointRegistry to find the target node for a slice,
/// and routes the invocation via the ClusterNetwork.
public interface SliceInvoker extends SliceInvokerFacade {
    /// Implementation of SliceInvokerFacade.methodHandle for creating reusable handles.
    /// Parses artifact and method once, returns a handle for repeated invocations.
    @Override
    default <R, T> Result<MethodHandle<R, T>> methodHandle(String sliceArtifact,
                                                           String methodName,
                                                           TypeToken<T> requestType,
                                                           TypeToken<R> responseType) {
        return Artifact.artifact(sliceArtifact)
                       .flatMap(artifact -> MethodName.methodName(methodName)
                                                      .map(method -> createMethodHandle(artifact,
                                                                                        method,
                                                                                        requestType,
                                                                                        responseType)));
    }

    /// Create a method handle with pre-parsed artifact and method.
    /// Subclasses may override to provide custom implementations.
    default <R, T> MethodHandle<R, T> createMethodHandle(Artifact artifact,
                                                         MethodName method,
                                                         TypeToken<T> requestType,
                                                         TypeToken<R> responseType) {
        return new MethodHandleImpl<>(artifact, method, requestType, responseType, this);
    }

    /// Internal record implementing MethodHandle with pre-parsed artifact/method.
    /// Delegates to typed invoke methods, avoiding repeated parsing.
    record MethodHandleImpl<R, T>(Artifact artifact,
                                  MethodName methodName,
                                  TypeToken<T> requestType,
                                  TypeToken<R> responseType,
                                  SliceInvoker invoker) implements MethodHandle<R, T> {
        @Override
        public Promise<R> invoke(T request) {
            return invoker.invoke(artifact, methodName, request, responseType);
        }

        @Override
        public Promise<Unit> fireAndForget(T request) {
            return invoker.invoke(artifact, methodName, request);
        }

        @Override
        public String artifactCoordinate() {
            return artifact.asString();
        }

        @Override
        public Result<Unit> materialize() {
            return invoker.verifyEndpointExists(artifact, methodName);
        }
    }

    /// Verify that an endpoint exists for the given artifact and method.
    /// Used during slice activation to eagerly validate dependencies.
    ///
    /// @param artifact Target slice artifact
    /// @param method   Method to verify
    /// @return Success if endpoint exists, failure if not found
    Result<Unit> verifyEndpointExists(Artifact artifact, MethodName method);

    /// Fire-and-forget invocation - sends request without waiting for response.
    ///
    /// @param slice  Target slice artifact
    /// @param method Method to invoke
    /// @param request Request parameter
    /// @return Promise that completes when request is sent
    Promise<Unit> invoke(Artifact slice, MethodName method, Object request);

    /// Request-response invocation - sends request and waits for response.
    ///
    /// @param slice        Target slice artifact
    /// @param method       Method to invoke
    /// @param request      Request parameter
    /// @param responseType Expected response type token
    /// @param <R>          Response type
    /// @return Promise resolving to response
    <R> Promise<R> invoke(Artifact slice, MethodName method, Object request, TypeToken<R> responseType);

    /// Request-response invocation with retry for idempotent operations.
    /// Uses exponential backoff with the specified number of retries.
    ///
    /// @param slice        Target slice artifact
    /// @param method       Method to invoke
    /// @param request      Request parameter
    /// @param responseType Expected response type token
    /// @param maxRetries   Maximum number of retry attempts (0 = no retries)
    /// @param <R>          Response type
    /// @return Promise resolving to response
    <R> Promise<R> invokeWithRetry(Artifact slice,
                                   MethodName method,
                                   Object request,
                                   TypeToken<R> responseType,
                                   int maxRetries);

    /// Local invocation - invokes a slice on the local node without network round-trip.
    /// Used by HTTP router for handling incoming requests.
    ///
    /// @param slice        Target slice artifact
    /// @param method       Method to invoke
    /// @param request      Request parameter
    /// @param responseType Expected response type token
    /// @param <R>          Response type
    /// @return Promise resolving to response
    <R> Promise<R> invokeLocal(Artifact slice, MethodName method, Object request, TypeToken<R> responseType);

    /// Handle response from remote invocation.
    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01") // MessageReceiver callback — void required by messaging framework
    void onInvokeResponse(InvokeResponse response);

    /// Handle node removal for immediate retry of pending invocations.
    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01") // MessageReceiver callback — void required by messaging framework
    void onNodeRemoved(TopologyChangeNotification.NodeRemoved event);

    /// Handle node down for immediate retry of pending invocations.
    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01") // MessageReceiver callback — void required by messaging framework
    void onNodeDown(TopologyChangeNotification.NodeDown event);

    /// Stop the invoker and release resources.
    ///
    /// Shuts down the retry scheduler, cancels pending invocations,
    /// and cleans up resources.
    ///
    /// @return Promise that completes when shutdown is finished
    Promise<Unit> stop();

    /// Get count of pending invocations (for monitoring).
    int pendingCount();

    /// Default timeout for invocations (30 seconds).
    long DEFAULT_TIMEOUT_MS = 30_000;

    /// Default maximum retries.
    int DEFAULT_MAX_RETRIES = 3;

    /// Base delay for exponential backoff (100ms).
    long BASE_RETRY_DELAY_MS = 100;

    /// Listener for slice failure events.
    @FunctionalInterface
    interface SliceFailureListener {
        @SuppressWarnings("JBCT-RET-01") // FunctionalInterface callback — void required
        void onSliceFailure(SliceFailureEvent event);
    }

    /// Set the failure listener for slice failure events.
    /// Called when all instances of a slice fail during invocation.
    Unit setFailureListener(SliceFailureListener listener);

    /// Resolves the preferred node for cache-affinity routing.
    /// Given a request, extracts the cache key and determines which node
    /// owns the DHT partition for that key.
    @FunctionalInterface
    interface CacheAffinityResolver {
        /// Resolve the affinity node for the given request.
        /// Returns the NodeId of the node that should handle this request for cache locality,
        /// or empty if affinity cannot be determined.
        Option<NodeId> resolveAffinityNode(Object request);
    }

    /// Register a cache affinity resolver for a specific slice method.
    /// When registered, invocations of this method will prefer routing to the node
    /// owning the DHT partition for the cache key.
    ///
    /// @param artifact the slice artifact
    /// @param method the method name
    /// @param resolver the affinity resolver
    /// @return unit
    Unit registerAffinityResolver(Artifact artifact, MethodName method, CacheAffinityResolver resolver);

    /// Unregister a cache affinity resolver.
    ///
    /// @param artifact the slice artifact
    /// @param method the method name
    /// @return unit
    Unit unregisterAffinityResolver(Artifact artifact, MethodName method);

    /// Create a new SliceInvoker.
    static SliceInvoker sliceInvoker(NodeId self,
                                     ClusterNetwork network,
                                     EndpointRegistry endpointRegistry,
                                     InvocationHandler invocationHandler,
                                     Serializer serializer,
                                     Deserializer deserializer,
                                     RollingUpdateManager rollingUpdateManager,
                                     ObservabilityInterceptor observabilityInterceptor) {
        return new SliceInvokerImpl(self,
                                    network,
                                    endpointRegistry,
                                    invocationHandler,
                                    serializer,
                                    deserializer,
                                    DEFAULT_TIMEOUT_MS,
                                    rollingUpdateManager,
                                    observabilityInterceptor);
    }

    /// Create with custom timeout.
    static SliceInvoker sliceInvoker(NodeId self,
                                     ClusterNetwork network,
                                     EndpointRegistry endpointRegistry,
                                     InvocationHandler invocationHandler,
                                     Serializer serializer,
                                     Deserializer deserializer,
                                     long timeoutMs,
                                     RollingUpdateManager rollingUpdateManager,
                                     ObservabilityInterceptor observabilityInterceptor) {
        return new SliceInvokerImpl(self,
                                    network,
                                    endpointRegistry,
                                    invocationHandler,
                                    serializer,
                                    deserializer,
                                    timeoutMs,
                                    rollingUpdateManager,
                                    observabilityInterceptor);
    }
}

class SliceInvokerImpl implements SliceInvoker {
    private static final Logger log = LoggerFactory.getLogger(SliceInvokerImpl.class);
    private static final Cause NO_ENDPOINT_FOUND = Causes.cause("No endpoint found for slice/method");
    private static final Cause SLICE_NOT_FOUND = Causes.cause("Slice not found locally");
    private static final Cause INVOKER_STOPPED = Causes.cause("SliceInvoker has been stopped");
    private static final long CLEANUP_INTERVAL_MS = 60_000;

    // Clean up stale entries every minute
    private final NodeId self;
    private final ClusterNetwork network;
    private final EndpointRegistry endpointRegistry;
    private final InvocationHandler invocationHandler;
    private final Serializer serializer;
    private final Deserializer deserializer;
    private final long timeoutMs;
    private final ScheduledExecutorService scheduler;
    private final RollingUpdateManager rollingUpdateManager;
    private final ObservabilityInterceptor observabilityInterceptor;

    // Pending request-response invocations awaiting responses
    // Maps correlationId -> (promise, createdAtMs)
    private final ConcurrentHashMap<String, PendingInvocation> pendingInvocations = new ConcurrentHashMap<>();

    // Secondary index: NodeId -> Set of correlationIds for that node (for fast lookup on node departure)
    private final Map<NodeId, Set<String>> pendingInvocationsByNode = new ConcurrentHashMap<>();

    private final Map<String, CacheAffinityResolver> affinityResolvers = new ConcurrentHashMap<>();

    private volatile boolean stopped = false;
    private volatile Option<SliceFailureListener> failureListener = Option.none();

    record PendingInvocation(Promise<Object> promise,
                             long createdAtMs,
                             String requestId,
                             NodeId targetNode,
                             SliceBridge senderBridge) {}

    SliceInvokerImpl(NodeId self,
                     ClusterNetwork network,
                     EndpointRegistry endpointRegistry,
                     InvocationHandler invocationHandler,
                     Serializer serializer,
                     Deserializer deserializer,
                     long timeoutMs,
                     RollingUpdateManager rollingUpdateManager,
                     ObservabilityInterceptor observabilityInterceptor) {
        this.self = self;
        this.network = network;
        this.endpointRegistry = endpointRegistry;
        this.invocationHandler = invocationHandler;
        this.serializer = serializer;
        this.deserializer = deserializer;
        this.timeoutMs = timeoutMs;
        this.rollingUpdateManager = rollingUpdateManager;
        this.observabilityInterceptor = observabilityInterceptor;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(this::createSchedulerThread);
        // Schedule periodic cleanup of stale pending invocations
        scheduler.scheduleAtFixedRate(this::cleanupStaleInvocations,
                                      CLEANUP_INTERVAL_MS,
                                      CLEANUP_INTERVAL_MS,
                                      TimeUnit.MILLISECONDS);
    }

    private Thread createSchedulerThread(Runnable r) {
        var t = new Thread(r, "slice-invoker-scheduler");
        t.setDaemon(true);
        return t;
    }

    private void cleanupStaleInvocations() {
        var staleThreshold = System.currentTimeMillis() - (timeoutMs * 2);
        pendingInvocations.entrySet()
                          .removeIf(entry -> isStaleAndCleanup(entry, staleThreshold));
    }

    private boolean isStaleAndCleanup(Map.Entry<String, PendingInvocation> entry, long staleThreshold) {
        var pending = entry.getValue();
        if (pending.createdAtMs() < staleThreshold) {
            log.warn("[requestId={}] Cleaning up stale pending invocation: {}", pending.requestId(), entry.getKey());
            removeFromNodeIndex(entry.getKey(), pending.targetNode());
            pending.promise()
                   .resolve(Causes.cause("Invocation timed out (cleanup)")
                                  .result());
            return true;
        }
        return false;
    }

    private void cancelPendingInvocation(String id, PendingInvocation pending) {
        pending.promise()
               .resolve(INVOKER_STOPPED.result());
    }

    @Override
    public Promise<Unit> stop() {
        if (stopped) {
            return Promise.success(unit());
        }
        stopped = true;
        log.info("Stopping SliceInvoker with {} pending invocations", pendingInvocations.size());
        // Cancel all pending invocations
        pendingInvocations.forEach(this::cancelPendingInvocation);
        pendingInvocations.clear();
        pendingInvocationsByNode.clear();
        affinityResolvers.clear();
        // Shutdown scheduler
        scheduler.shutdown();
        try{
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread()
                  .interrupt();
        }
        log.info("SliceInvoker stopped");
        return Promise.success(unit());
    }

    @Override
    public int pendingCount() {
        return pendingInvocations.size();
    }

    @Override
    public Promise<Unit> invoke(Artifact slice, MethodName method, Object request) {
        return selectEndpointWithAffinity(slice, method, request)
        .flatMap(endpoint -> endpoint.nodeId()
                                     .equals(self)
                             ? invokeLocalFireAndForget(slice, method, request)
                             : sendFireAndForget(endpoint, slice, method, request));
    }

    private Promise<Unit> invokeLocalFireAndForget(Artifact slice, MethodName method, Object request) {
        return invocationHandler.localSlice(slice)
                                .async(SLICE_NOT_FOUND)
                                .flatMap(bridge -> observabilityInterceptor.intercept(slice,
                                                                                      method,
                                                                                      InvocationContext.getOrGenerateRequestId(),
                                                                                      InvocationContext.currentDepth() + 1,
                                                                                      true,
                                                                                      // local
        () -> invokeViaBridge(bridge, method, request)))
                                .mapToUnit();
    }

    private Promise<Unit> sendFireAndForget(Endpoint endpoint, Artifact slice, MethodName method, Object request) {
        var senderBridge = findSenderBridge(request);
        return senderBridge.encode(request)
                           .flatMap(payload -> sendFireAndForgetPayload(endpoint, slice, method, payload));
    }

    private Promise<Unit> sendFireAndForgetPayload(Endpoint endpoint, Artifact slice, MethodName method, byte[] payload) {
        var correlationId = KSUID.ksuid().toString();
        var requestId = InvocationContext.getOrGenerateRequestId();
        var invokeRequest = InvokeRequest.invokeRequest(self,
                                                        correlationId,
                                                        requestId,
                                                        slice,
                                                        method,
                                                        payload,
                                                        false,
                                                        InvocationContext.currentDepth() + 1,
                                                        1,
                                                        InvocationContext.isSampled());
        network.send(endpoint.nodeId(), invokeRequest);
        if (log.isDebugEnabled()) {
            log.debug("[requestId={}] Sent fire-and-forget invocation to {}: {}.{}",
                      requestId,
                      endpoint.nodeId(),
                      slice,
                      method);
        }
        return Promise.success(unit());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <R> Promise<R> invoke(Artifact slice, MethodName method, Object request, TypeToken<R> responseType) {
        if (stopped) {
            return INVOKER_STOPPED.promise();
        }
        return selectEndpointWithAffinity(slice, method, request)
        .flatMap(endpoint -> endpoint.nodeId()
                                     .equals(self)
                             ? invokeLocal(slice, method, request, responseType)
                             : sendRequestResponse(endpoint, slice, method, request));
    }

    @SuppressWarnings("unchecked")
    private <R> Promise<R> sendRequestResponse(Endpoint endpoint, Artifact slice, MethodName method, Object request) {
        var senderBridge = findSenderBridge(request);
        return senderBridge.encode(request)
                           .flatMap(payload -> sendAndAwaitResponse(endpoint, slice, method, payload, senderBridge));
    }

    @SuppressWarnings("unchecked")
    private <R> Promise<R> sendAndAwaitResponse(Endpoint endpoint,
                                                 Artifact slice,
                                                 MethodName method,
                                                 byte[] payload,
                                                 SliceBridge senderBridge) {
        var correlationId = KSUID.ksuid().toString();
        return Promise.promise(pendingPromise -> setupPendingInvocation((Promise<Object>)(Promise<?>) pendingPromise,
                                                                        correlationId,
                                                                        endpoint,
                                                                        slice,
                                                                        method,
                                                                        payload,
                                                                        senderBridge));
    }

    private void setupPendingInvocation(Promise<Object> pendingPromise,
                                        String correlationId,
                                        Endpoint endpoint,
                                        Artifact slice,
                                        MethodName method,
                                        byte[] payload,
                                        SliceBridge senderBridge) {
        var requestId = InvocationContext.getOrGenerateRequestId();
        var targetNode = endpoint.nodeId();
        var pending = new PendingInvocation(pendingPromise, System.currentTimeMillis(), requestId, targetNode, senderBridge);
        pendingInvocations.put(correlationId, pending);
        pendingInvocationsByNode.computeIfAbsent(targetNode,
                                                 _ -> ConcurrentHashMap.newKeySet())
                                .add(correlationId);
        pendingPromise.timeout(timeSpan(timeoutMs).millis())
                      .onResult(_ -> removePendingInvocation(correlationId, targetNode));
        var invokeRequest = InvokeRequest.invokeRequest(self,
                                                        correlationId,
                                                        requestId,
                                                        slice,
                                                        method,
                                                        payload,
                                                        true,
                                                        InvocationContext.currentDepth() + 1,
                                                        1,
                                                        // hops=1 for remote
        InvocationContext.isSampled());
        network.send(targetNode, invokeRequest);
        if (log.isDebugEnabled()) {
            log.debug("[requestId={}] Sent InvokeRequest to {}: {}.{} [{}]",
                      requestId,
                      targetNode,
                      slice,
                      method,
                      correlationId);
        }
    }

    @Override
    public <R> Promise<R> invokeWithRetry(Artifact slice,
                                          MethodName method,
                                          Object request,
                                          TypeToken<R> responseType,
                                          int maxRetries) {
        if (stopped) {
            return INVOKER_STOPPED.promise();
        }
        var requestId = InvocationContext.getOrGenerateRequestId();
        var ctx = new FailoverContext<>(slice,
                                        method,
                                        request,
                                        responseType,
                                        maxRetries,
                                        requestId,
                                        new java.util.HashSet<>(),
                                        new java.util.ArrayList<>(),
                                        Option.none());
        return Promise.promise(promise -> executeWithFailover(promise, ctx));
    }

    /// Context for multi-instance failover retry logic.
    /// Tracks which endpoints have been tried and failed.
    private record FailoverContext<R>(Artifact slice,
                                      MethodName method,
                                      Object request,
                                      TypeToken<R> responseType,
                                      int maxRetries,
                                      String requestId,
                                      java.util.Set<NodeId> failedNodes,
                                      java.util.List<NodeId> attemptedNodes,
                                      Option<Cause> lastError) {
        FailoverContext<R> withFailure(NodeId failedNode, Cause error) {
            var newFailed = new java.util.HashSet<>(failedNodes);
            newFailed.add(failedNode);
            var newAttempted = new java.util.ArrayList<>(attemptedNodes);
            newAttempted.add(failedNode);
            return new FailoverContext<>(slice,
                                         method,
                                         request,
                                         responseType,
                                         maxRetries,
                                         requestId,
                                         newFailed,
                                         newAttempted,
                                         Option.some(error));
        }

        int attemptCount() {
            return attemptedNodes.size();
        }
    }

    private <R> void executeWithFailover(Promise<R> promise, FailoverContext<R> ctx) {
        // Select endpoint excluding failed ones
        selectEndpointWithFailover(ctx.slice, ctx.method, ctx.failedNodes).onEmpty(() -> handleAllEndpointsFailed(promise,
                                                                                                                  ctx))
                                  .onPresent(endpoint -> invokeEndpointWithFailover(promise, ctx, endpoint));
    }

    private Option<Endpoint> selectEndpointWithFailover(Artifact slice,
                                                        MethodName method,
                                                        java.util.Set<NodeId> exclude) {
        if (exclude.isEmpty()) {
            // First attempt - check for rolling update routing
            var artifactBase = ArtifactBase.artifactBase(slice.groupId(), slice.artifactId());
            var updateEndpoint = rollingUpdateManager.getActiveUpdate(artifactBase)
                                                     .flatMap(update -> endpointRegistry.selectEndpointWithRouting(artifactBase,
                                                                                                                   method,
                                                                                                                   update.routing(),
                                                                                                                   update.oldVersion(),
                                                                                                                   update.newVersion()));
            // Fall back to regular selection if no rolling update or no routing endpoint
            return updateEndpoint.isPresent()
                   ? updateEndpoint
                   : endpointRegistry.selectEndpoint(slice, method);
        }
        // Failover - exclude failed nodes
        return endpointRegistry.selectEndpointExcluding(slice, method, exclude);
    }

    @SuppressWarnings("unchecked")
    private <R> void invokeEndpointWithFailover(Promise<R> promise, FailoverContext<R> ctx, Endpoint endpoint) {
        var targetNode = endpoint.nodeId();
        if (targetNode.equals(self)) {
            invokeLocalForFailover(promise, ctx);
            return;
        }
        invokeRemoteForFailover(promise, ctx, targetNode);
    }

    @SuppressWarnings("unchecked")
    private <R> void invokeLocalForFailover(Promise<R> promise, FailoverContext<R> ctx) {
        invocationHandler.localSlice(ctx.slice)
                         .async(SLICE_NOT_FOUND)
                         .flatMap(bridge -> observabilityInterceptor.intercept(ctx.slice,
                                                                               ctx.method,
                                                                               ctx.requestId,
                                                                               InvocationContext.currentDepth() + 1,
                                                                               true,
                                                                               // local
        () -> invokeViaBridge(bridge, ctx.method, ctx.request)))
                         .onSuccess(result -> promise.succeed((R) result))
                         .onFailure(cause -> handleFailoverFailure(promise, ctx, self, cause));
    }

    @SuppressWarnings("unchecked")
    private <R> void invokeRemoteForFailover(Promise<R> promise, FailoverContext<R> ctx, NodeId targetNode) {
        var senderBridge = findSenderBridge(ctx.request);
        senderBridge.encode(ctx.request)
                    .onSuccess(payload -> sendFailoverPayload(promise, ctx, targetNode, payload, senderBridge))
                    .onFailure(cause -> handleFailoverFailure(promise, ctx, targetNode, cause));
    }

    @SuppressWarnings("unchecked")
    private <R> void sendFailoverPayload(Promise<R> promise,
                                          FailoverContext<R> ctx,
                                          NodeId targetNode,
                                          byte[] payload,
                                          SliceBridge senderBridge) {
        var correlationId = KSUID.ksuid().toString();
        var pendingPromise = Promise.<Object>promise();
        var pending = new PendingInvocation(pendingPromise, System.currentTimeMillis(), ctx.requestId, targetNode, senderBridge);
        pendingInvocations.put(correlationId, pending);
        pendingInvocationsByNode.computeIfAbsent(targetNode,
                                                 _ -> ConcurrentHashMap.newKeySet())
                                .add(correlationId);
        pendingPromise.timeout(timeSpan(timeoutMs).millis())
                      .onResult(_ -> removePendingInvocation(correlationId, targetNode));
        var invokeRequest = InvokeRequest.invokeRequest(self,
                                                        correlationId,
                                                        ctx.requestId,
                                                        ctx.slice,
                                                        ctx.method,
                                                        payload,
                                                        true,
                                                        InvocationContext.currentDepth() + 1,
                                                        1,
                                                        InvocationContext.isSampled());
        network.send(targetNode, invokeRequest);
        if (log.isDebugEnabled()) {
            log.debug("[requestId={}] Sent failover invocation to {}: {}.{} [{}] (attempt {})",
                      ctx.requestId,
                      targetNode,
                      ctx.slice,
                      ctx.method,
                      correlationId,
                      ctx.attemptCount() + 1);
        }
        pendingPromise.onSuccess(result -> promise.succeed((R) result))
                      .onFailure(cause -> handleFailoverFailure(promise, ctx, targetNode, cause));
    }

    private <R> void handleFailoverFailure(Promise<R> promise, FailoverContext<R> ctx, NodeId failedNode, Cause cause) {
        if (stopped) {
            promise.fail(INVOKER_STOPPED);
            return;
        }
        var newCtx = ctx.withFailure(failedNode, cause);
        // Check if we've exceeded max retries
        if (newCtx.attemptCount() > ctx.maxRetries) {
            handleMaxRetriesExceeded(promise, newCtx);
            return;
        }
        // Schedule retry with different endpoint
        var delayMs = BASE_RETRY_DELAY_MS * (1L<< (newCtx.attemptCount() - 1));
        if (log.isDebugEnabled()) {
            log.debug("[requestId={}] Endpoint {} failed, scheduling failover retry in {}ms: {}.{} - {}",
                      ctx.requestId,
                      failedNode,
                      delayMs,
                      ctx.slice,
                      ctx.method,
                      cause.message());
        }
        scheduler.schedule(() -> executeWithFailover(promise, newCtx), delayMs, TimeUnit.MILLISECONDS);
    }

    private <R> void handleAllEndpointsFailed(Promise<R> promise, FailoverContext<R> ctx) {
        log.error("[requestId={}] All instances failed for {}.{}: {} nodes attempted",
                  ctx.requestId,
                  ctx.slice,
                  ctx.method,
                  ctx.attemptCount());
        // Emit failure event for alerting/controller
        var event = SliceFailureEvent.AllInstancesFailed.allInstancesFailed(ctx.requestId,
                                                                            ctx.slice,
                                                                            ctx.method,
                                                                            ctx.lastError,
                                                                            ctx.attemptedNodes);
        publishFailureEvent(event);
        promise.fail(new SliceInvokerError.AllInstancesFailedError(ctx.slice,
                                                                   ctx.method,
                                                                   ctx.attemptedNodes.size() + " nodes attempted"));
    }

    private <R> void handleMaxRetriesExceeded(Promise<R> promise, FailoverContext<R> ctx) {
        log.warn("[requestId={}] Max retries ({}) exceeded for {}.{}: {} nodes attempted",
                 ctx.requestId,
                 ctx.maxRetries,
                 ctx.slice,
                 ctx.method,
                 ctx.attemptCount());
        // Also emit event since max retries exhausted indicates serious problem
        if (ctx.attemptCount() >= endpointRegistry.findEndpoints(ctx.slice, ctx.method)
                                                  .size()) {
            var event = SliceFailureEvent.AllInstancesFailed.allInstancesFailed(ctx.requestId,
                                                                                ctx.slice,
                                                                                ctx.method,
                                                                                ctx.lastError,
                                                                                ctx.attemptedNodes);
            publishFailureEvent(event);
        }
        promise.fail(ctx.lastError.or(Causes.cause("Max retries exceeded with no error recorded")));
    }

    @Override
    public Unit setFailureListener(SliceFailureListener listener) {
        this.failureListener = Option.some(listener);
        return unit();
    }

    @Override
    public Unit registerAffinityResolver(Artifact artifact, MethodName method, CacheAffinityResolver resolver) {
        affinityResolvers.put(affinityLookupKey(artifact, method), resolver);
        return unit();
    }

    @Override
    public Unit unregisterAffinityResolver(Artifact artifact, MethodName method) {
        affinityResolvers.remove(affinityLookupKey(artifact, method));
        return unit();
    }

    private static String affinityLookupKey(Artifact artifact, MethodName method) {
        return artifact.asString() + "/" + method.name();
    }

    private void publishFailureEvent(SliceFailureEvent event) {
        var requestId = extractRequestId(event);
        log.warn("[requestId={}] SliceFailureEvent: {}", requestId, event);
        failureListener.onPresent(listener -> safeNotifyFailureListener(listener, event, requestId));
    }

    private String extractRequestId(SliceFailureEvent event) {
        return switch (event) {
            case SliceFailureEvent.AllInstancesFailed failed -> failed.requestId();
        };
    }

    private void safeNotifyFailureListener(SliceFailureListener listener, SliceFailureEvent event, String requestId) {
        try{
            listener.onSliceFailure(event);
        } catch (Exception e) {
            log.error("[requestId={}] Error notifying failure listener: {}", requestId, e.getMessage());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <R> Promise<R> invokeLocal(Artifact slice, MethodName method, Object request, TypeToken<R> responseType) {
        return invocationHandler.localSlice(slice)
                                .async(SLICE_NOT_FOUND)
                                .flatMap(bridge -> observabilityInterceptor.intercept(slice,
                                                                                      method,
                                                                                      InvocationContext.getOrGenerateRequestId(),
                                                                                      InvocationContext.currentDepth() + 1,
                                                                                      true,
                                                                                      // local
        () -> invokeViaBridge(bridge, method, request)));
    }

    @SuppressWarnings("unchecked")
    private <R> Promise<R> invokeViaBridge(SliceBridge targetBridge, MethodName method, Object request) {
        var senderBridge = findSenderBridge(request);
        return senderBridge.encode(request)
                           .flatMap(inputBytes -> targetBridge.invoke(method.name(), inputBytes))
                           .flatMap(responseBytes -> senderBridge.decode(responseBytes))
                           .map(result -> (R) result);
    }

    private SliceBridge findSenderBridge(Object request) {
        return invocationHandler.findBridgeByClassLoader(request.getClass().getClassLoader())
                                .unwrap();
    }

    @Override
    @SuppressWarnings({"unchecked", "JBCT-RET-01"})
    public void onInvokeResponse(InvokeResponse response) {
        Option.option(pendingInvocations.remove(response.correlationId()))
              .onEmpty(() -> log.warn("[requestId={}] Received response for unknown correlationId: {}",
                                      response.requestId(),
                                      response.correlationId()))
              .onPresent(pending -> processReceivedResponse(pending, response));
    }

    private void processReceivedResponse(PendingInvocation pending, InvokeResponse response) {
        removeFromNodeIndex(response.correlationId(), pending.targetNode());
        handlePendingResponse(pending, response);
    }

    @Override
    @SuppressWarnings("JBCT-RET-01")
    public void onNodeRemoved(TopologyChangeNotification.NodeRemoved event) {
        handleNodeDeparture(event.nodeId());
    }

    @Override
    @SuppressWarnings("JBCT-RET-01")
    public void onNodeDown(TopologyChangeNotification.NodeDown event) {
        handleNodeDeparture(event.nodeId());
    }

    private void handleNodeDeparture(NodeId departedNode) {
        Option.option(pendingInvocationsByNode.remove(departedNode))
              .filter(ids -> !ids.isEmpty())
              .onPresent(correlationIds -> retryPendingForDepartedNode(departedNode, correlationIds));
    }

    private void retryPendingForDepartedNode(NodeId departedNode, Set<String> correlationIds) {
        var affectedRequestIds = correlationIds.stream()
                                               .map(pendingInvocations::get)
                                               .flatMap(p -> Option.option(p)
                                                                   .stream())
                                               .map(PendingInvocation::requestId)
                                               .limit(5)
                                               .toList();
        log.debug("Node {} departed, triggering immediate retry for {} pending invocations, requestIds={}",
                  departedNode,
                  correlationIds.size(),
                  affectedRequestIds);
        for (var correlationId : correlationIds) {
            Option.option(pendingInvocations.remove(correlationId))
                  .onPresent(pending -> retryDepartedInvocation(pending, departedNode));
        }
    }

    private void retryDepartedInvocation(PendingInvocation pending, NodeId departedNode) {
        log.debug("Triggering retry for request [{}] due to node {} departure", pending.requestId(), departedNode);
        // Fail the promise to trigger onFailure callback which handles retry
        pending.promise()
               .fail(Causes.cause("Target node " + departedNode + " departed"));
    }

    private void removePendingInvocation(String correlationId, NodeId targetNode) {
        pendingInvocations.remove(correlationId);
        removeFromNodeIndex(correlationId, targetNode);
    }

    private void removeFromNodeIndex(String correlationId, NodeId targetNode) {
        Option.option(pendingInvocationsByNode.get(targetNode))
              .onPresent(nodeCorrelations -> {
                             nodeCorrelations.remove(correlationId);
                             // Clean up empty sets
        if (nodeCorrelations.isEmpty()) {
                                 pendingInvocationsByNode.remove(targetNode, nodeCorrelations);
                             }
                         });
    }

    private void handlePendingResponse(PendingInvocation pending, InvokeResponse response) {
        var promise = pending.promise();
        var requestId = pending.requestId();
        if (response.success()) {
            pending.senderBridge().decode(response.payload())
                   .onSuccess(result -> handleDecodeSuccess(promise, result, requestId, response.correlationId()))
                   .onFailure(cause -> handleDecodeFailure(promise, cause, requestId, response.correlationId()));
        } else {
            var errorMessage = new String(response.payload());
            promise.resolve(new SliceInvokerError.RemoteInvocationError(errorMessage).result());
            if (log.isDebugEnabled()) {
                log.debug("[requestId={}] Failed to complete invocation [{}]: {}",
                          requestId,
                          response.correlationId(),
                          errorMessage);
            }
        }
    }

    private void handleDecodeSuccess(Promise<Object> promise, Object result, String requestId, String correlationId) {
        promise.resolve(Result.success(result));
        if (log.isDebugEnabled()) {
            log.debug("[requestId={}] Completed invocation [{}]", requestId, correlationId);
        }
    }

    private void handleDecodeFailure(Promise<Object> promise, Cause cause, String requestId, String correlationId) {
        promise.resolve(cause.result());
        log.error("[requestId={}] Failed to deserialize response [{}]: {}",
                  requestId, correlationId, cause.message());
    }

    private Promise<Endpoint> selectEndpoint(Artifact slice, MethodName method) {
        // Check if there's an active rolling update for this artifact
        var artifactBase = ArtifactBase.artifactBase(slice.groupId(), slice.artifactId());
        return rollingUpdateManager.getActiveUpdate(artifactBase)
                                   .map(update -> selectEndpointWithWeightedRouting(slice, artifactBase, method, update))
                                   .or(() -> endpointRegistry.selectEndpoint(slice, method)
                                                             .async(NO_ENDPOINT_FOUND));
    }

    private Promise<Endpoint> selectEndpointWithAffinity(Artifact slice, MethodName method, Object request) {
        var resolver = Option.option(affinityResolvers.get(affinityLookupKey(slice, method)));
        var affinityEndpoint = resolver.flatMap(r -> r.resolveAffinityNode(request))
                                       .flatMap(node -> endpointRegistry.selectEndpointByAffinity(slice, method, node));
        if (affinityEndpoint.isPresent()) {
            return affinityEndpoint.async(NO_ENDPOINT_FOUND);
        }
        return selectEndpoint(slice, method);
    }

    private Promise<Endpoint> selectEndpointWithWeightedRouting(Artifact slice,
                                                                ArtifactBase artifactBase,
                                                                MethodName method,
                                                                RollingUpdate update) {
        if (log.isDebugEnabled()) {
            log.debug("[requestId={}] Using weighted routing for {} during rolling update {}",
                      InvocationContext.getOrGenerateRequestId(),
                      slice,
                      update.updateId());
        }
        return endpointRegistry.selectEndpointWithRouting(artifactBase,
                                                          method,
                                                          update.routing(),
                                                          update.oldVersion(),
                                                          update.newVersion())
                               .async(NO_ENDPOINT_FOUND);
    }

    @Override
    public Result<Unit> verifyEndpointExists(Artifact artifact, MethodName method) {
        var endpoints = endpointRegistry.findEndpoints(artifact, method);
        if (endpoints.isEmpty()) {
            return Causes.cause("No endpoint found for " + artifact.asString() + "/" + method.name())
                         .result();
        }
        return Result.unitResult();
    }
}
