package org.pragmatica.aether.invoke;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.endpoint.EndpointRegistry;
import org.pragmatica.aether.endpoint.EndpointRegistry.Endpoint;
import org.pragmatica.aether.invoke.InvocationMessage.InvokeRequest;
import org.pragmatica.aether.invoke.InvocationMessage.InvokeResponse;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.SliceBridge;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.update.RollingUpdate;
import org.pragmatica.aether.update.RollingUpdateManager;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.utility.KSUID;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.netty.buffer.Unpooled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/**
 * Client-side component for invoking methods on remote slices.
 *
 * <p>Supports two invocation patterns:
 * <ul>
 *   <li>Fire-and-forget: {@link #invoke(Artifact, MethodName, Object)}</li>
 *   <li>Request-response: {@link #invokeAndWait(Artifact, MethodName, Object, Class)}</li>
 * </ul>
 *
 * <p>Uses the EndpointRegistry to find the target node for a slice,
 * and routes the invocation via the ClusterNetwork.
 */
public interface SliceInvoker extends SliceInvokerFacade {
    /**
     * Implementation of SliceInvokerFacade for use by slices via SliceRuntime.
     * Parses string artifact/method and delegates to typed methods.
     */
    @Override
    default <R> Promise<R> invokeAndWait(String sliceArtifact,
                                         String methodName,
                                         Object request,
                                         Class<R> responseType) {
        return Artifact.artifact(sliceArtifact)
                       .flatMap(artifact -> MethodName.methodName(methodName)
                                                      .map(method -> new ArtifactMethod(artifact, method)))
                       .async()
                       .flatMap(am -> invokeAndWait(am.artifact(),
                                                    am.method(),
                                                    request,
                                                    responseType));
    }

    record ArtifactMethod(Artifact artifact, MethodName method) {}

    /**
     * Fire-and-forget invocation - sends request without waiting for response.
     *
     * @param slice  Target slice artifact
     * @param method Method to invoke
     * @param request Request parameter
     * @return Promise that completes when request is sent
     */
    Promise<Unit> invoke(Artifact slice, MethodName method, Object request);

    /**
     * Request-response invocation - sends request and waits for response.
     *
     * @param slice        Target slice artifact
     * @param method       Method to invoke
     * @param request      Request parameter
     * @param responseType Expected response type
     * @param <R>          Response type
     * @return Promise resolving to response
     */
    <R> Promise<R> invokeAndWait(Artifact slice, MethodName method, Object request, Class<R> responseType);

    /**
     * Request-response invocation with retry for idempotent operations.
     * Uses exponential backoff with the specified number of retries.
     *
     * @param slice        Target slice artifact
     * @param method       Method to invoke
     * @param request      Request parameter
     * @param responseType Expected response type
     * @param maxRetries   Maximum number of retry attempts (0 = no retries)
     * @param <R>          Response type
     * @return Promise resolving to response
     */
    <R> Promise<R> invokeWithRetry(Artifact slice,
                                   MethodName method,
                                   Object request,
                                   Class<R> responseType,
                                   int maxRetries);

    /**
     * Local invocation - invokes a slice on the local node without network round-trip.
     * Used by HTTP router for handling incoming requests.
     *
     * @param slice        Target slice artifact
     * @param method       Method to invoke
     * @param request      Request parameter
     * @param responseType Expected response type
     * @param <R>          Response type
     * @return Promise resolving to response
     */
    <R> Promise<R> invokeLocal(Artifact slice, MethodName method, Object request, Class<R> responseType);

    /**
     * Handle response from remote invocation.
     */
    @MessageReceiver
    void onInvokeResponse(InvokeResponse response);

    /**
     * Stop the invoker and release resources.
     * <p>
     * Shuts down the retry scheduler, cancels pending invocations,
     * and cleans up resources.
     *
     * @return Promise that completes when shutdown is finished
     */
    Promise<Unit> stop();

    /**
     * Get count of pending invocations (for monitoring).
     */
    int pendingCount();

    /**
     * Default timeout for invocations (30 seconds).
     */
    long DEFAULT_TIMEOUT_MS = 30_000;

    /**
     * Default maximum retries.
     */
    int DEFAULT_MAX_RETRIES = 3;

    /**
     * Base delay for exponential backoff (100ms).
     */
    long BASE_RETRY_DELAY_MS = 100;

    /**
     * Listener for slice failure events.
     */
    @FunctionalInterface
    interface SliceFailureListener {
        void onSliceFailure(SliceFailureEvent event);
    }

    /**
     * Set the failure listener for slice failure events.
     * Called when all instances of a slice fail during invocation.
     */
    Unit setFailureListener(SliceFailureListener listener);

    /**
     * Create a new SliceInvoker.
     */
    static SliceInvoker sliceInvoker(NodeId self,
                                     ClusterNetwork network,
                                     EndpointRegistry endpointRegistry,
                                     InvocationHandler invocationHandler,
                                     Serializer serializer,
                                     Deserializer deserializer,
                                     RollingUpdateManager rollingUpdateManager) {
        return new SliceInvokerImpl(self,
                                    network,
                                    endpointRegistry,
                                    invocationHandler,
                                    serializer,
                                    deserializer,
                                    DEFAULT_TIMEOUT_MS,
                                    rollingUpdateManager);
    }

    /**
     * Create with custom timeout.
     */
    static SliceInvoker sliceInvoker(NodeId self,
                                     ClusterNetwork network,
                                     EndpointRegistry endpointRegistry,
                                     InvocationHandler invocationHandler,
                                     Serializer serializer,
                                     Deserializer deserializer,
                                     long timeoutMs,
                                     RollingUpdateManager rollingUpdateManager) {
        return new SliceInvokerImpl(self,
                                    network,
                                    endpointRegistry,
                                    invocationHandler,
                                    serializer,
                                    deserializer,
                                    timeoutMs,
                                    rollingUpdateManager);
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

    // Pending request-response invocations awaiting responses
    // Maps correlationId -> (promise, createdAtMs)
    private final ConcurrentHashMap<String, PendingInvocation> pendingInvocations = new ConcurrentHashMap<>();

    private volatile boolean stopped = false;
    private volatile Option<SliceFailureListener> failureListener = Option.empty();

    record PendingInvocation(Promise<Object> promise, long createdAtMs, String requestId) {}

    SliceInvokerImpl(NodeId self,
                     ClusterNetwork network,
                     EndpointRegistry endpointRegistry,
                     InvocationHandler invocationHandler,
                     Serializer serializer,
                     Deserializer deserializer,
                     long timeoutMs,
                     RollingUpdateManager rollingUpdateManager) {
        this.self = self;
        this.network = network;
        this.endpointRegistry = endpointRegistry;
        this.invocationHandler = invocationHandler;
        this.serializer = serializer;
        this.deserializer = deserializer;
        this.timeoutMs = timeoutMs;
        this.rollingUpdateManager = rollingUpdateManager;
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
            log.warn("Cleaning up stale pending invocation: {}", entry.getKey());
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
        return selectEndpoint(slice, method)
                             .flatMap(endpoint -> sendFireAndForget(endpoint, slice, method, request));
    }

    private Promise<Unit> sendFireAndForget(Endpoint endpoint, Artifact slice, MethodName method, Object request) {
        var payload = serializeRequest(request);
        var correlationId = KSUID.ksuid()
                                 .toString();
        var requestId = InvocationContext.getOrGenerateRequestId();
        var invokeRequest = new InvokeRequest(self, correlationId, requestId, slice, method, payload, false);
        network.send(endpoint.nodeId(), invokeRequest);
        log.debug("[requestId={}] Sent fire-and-forget invocation to {}: {}.{}",
                  requestId,
                  endpoint.nodeId(),
                  slice,
                  method);
        return Promise.success(unit());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <R> Promise<R> invokeAndWait(Artifact slice, MethodName method, Object request, Class<R> responseType) {
        if (stopped) {
            return INVOKER_STOPPED.promise();
        }
        return selectEndpoint(slice, method)
                             .flatMap(endpoint -> sendRequestResponse(endpoint, slice, method, request));
    }

    @SuppressWarnings("unchecked")
    private <R> Promise<R> sendRequestResponse(Endpoint endpoint, Artifact slice, MethodName method, Object request) {
        var payload = serializeRequest(request);
        var correlationId = KSUID.ksuid()
                                 .toString();
        return Promise.promise(pendingPromise -> setupPendingInvocation((Promise<Object>)(Promise< ? >) pendingPromise,
                                                                        correlationId,
                                                                        endpoint,
                                                                        slice,
                                                                        method,
                                                                        payload));
    }

    private void setupPendingInvocation(Promise<Object> pendingPromise,
                                        String correlationId,
                                        Endpoint endpoint,
                                        Artifact slice,
                                        MethodName method,
                                        byte[] payload) {
        var requestId = InvocationContext.getOrGenerateRequestId();
        var pending = new PendingInvocation(pendingPromise, System.currentTimeMillis(), requestId);
        pendingInvocations.put(correlationId, pending);
        pendingPromise.timeout(timeSpan(timeoutMs)
                                       .millis())
                      .onResult(_ -> pendingInvocations.remove(correlationId));
        var invokeRequest = new InvokeRequest(self, correlationId, requestId, slice, method, payload, true);
        network.send(endpoint.nodeId(), invokeRequest);
        log.debug("[requestId={}] Sent request-response invocation to {}: {}.{} [{}]",
                  requestId,
                  endpoint.nodeId(),
                  slice,
                  method,
                  correlationId);
    }

    @Override
    public <R> Promise<R> invokeWithRetry(Artifact slice,
                                          MethodName method,
                                          Object request,
                                          Class<R> responseType,
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
                                        null);
        return Promise.promise(promise -> executeWithFailover(promise, ctx));
    }

    /**
     * Context for multi-instance failover retry logic.
     * Tracks which endpoints have been tried and failed.
     */
    private record FailoverContext<R>(Artifact slice,
                                      MethodName method,
                                      Object request,
                                      Class<R> responseType,
                                      int maxRetries,
                                      String requestId,
                                      java.util.Set<NodeId> failedNodes,
                                      java.util.List<NodeId> attemptedNodes,
                                      Cause lastError) {
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
                                         error);
        }

        int attemptCount() {
            return attemptedNodes.size();
        }
    }

    private <R> void executeWithFailover(Promise<R> promise, FailoverContext<R> ctx) {
        // Select endpoint excluding failed ones
        selectEndpointWithFailover(ctx.slice, ctx.method, ctx.failedNodes)
                                  .onEmpty(() -> handleAllEndpointsFailed(promise, ctx))
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
        var payload = serializeRequest(ctx.request);
        var correlationId = KSUID.ksuid()
                                 .toString();
        var pendingPromise = Promise.<Object>promise();
        var pending = new PendingInvocation(pendingPromise, System.currentTimeMillis(), ctx.requestId);
        pendingInvocations.put(correlationId, pending);
        pendingPromise.timeout(timeSpan(timeoutMs)
                                       .millis())
                      .onResult(_ -> pendingInvocations.remove(correlationId));
        var invokeRequest = new InvokeRequest(self, correlationId, ctx.requestId, ctx.slice, ctx.method, payload, true);
        network.send(endpoint.nodeId(), invokeRequest);
        log.debug("[requestId={}] Sent failover invocation to {}: {}.{} [{}] (attempt {})",
                  ctx.requestId,
                  endpoint.nodeId(),
                  ctx.slice,
                  ctx.method,
                  correlationId,
                  ctx.attemptCount() + 1);
        pendingPromise.onSuccess(result -> promise.succeed((R) result))
                      .onFailure(cause -> handleFailoverFailure(promise,
                                                                ctx,
                                                                endpoint.nodeId(),
                                                                cause));
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
        log.debug("[requestId={}] Endpoint {} failed, scheduling failover retry in {}ms: {}.{} - {}",
                  ctx.requestId,
                  failedNode,
                  delayMs,
                  ctx.slice,
                  ctx.method,
                  cause.message());
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
        promise.fail(new SliceInvokerError.AllInstancesFailedError(ctx.slice, ctx.attemptedNodes));
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
        promise.fail(ctx.lastError);
    }

    @Override
    public Unit setFailureListener(SliceFailureListener listener) {
        this.failureListener = Option.some(listener);
        return unit();
    }

    private void publishFailureEvent(SliceFailureEvent event) {
        log.warn("SliceFailureEvent: {}", event);
        failureListener.onPresent(listener -> {
                                      try{
                                          listener.onSliceFailure(event);
                                      } catch (Exception e) {
                                          log.error("Error notifying failure listener: {}", e.getMessage());
                                      }
                                  });
    }

    /**
     * Error hierarchy for SliceInvoker failures.
     */
    sealed interface SliceInvokerError extends Cause {
        /**
         * Error indicating all instances of a slice have failed.
         */
        record AllInstancesFailedError(Artifact slice, java.util.List<NodeId> attemptedNodes) implements SliceInvokerError {
            @Override
            public String message() {
                return "All instances failed for " + slice + " after trying " + attemptedNodes.size() + " nodes";
            }
        }

        /**
         * Error from remote invocation.
         */
        record InvocationError(String errorMessage) implements SliceInvokerError {
            @Override
            public String message() {
                return errorMessage;
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <R> Promise<R> invokeLocal(Artifact slice, MethodName method, Object request, Class<R> responseType) {
        return invocationHandler.getLocalSlice(slice)
                                .async(SLICE_NOT_FOUND)
                                .flatMap(bridge -> invokeViaBridge(bridge, method, request));
    }

    @SuppressWarnings("unchecked")
    private <R> Promise<R> invokeViaBridge(SliceBridge bridge, MethodName method, Object request) {
        var inputBytes = serializer.encode(request);
        return bridge.invoke(method.name(),
                             inputBytes)
                     .map(outputBytes -> (R) deserializer.decode(outputBytes));
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onInvokeResponse(InvokeResponse response) {
        Option.option(pendingInvocations.remove(response.correlationId()))
              .onEmpty(() -> log.warn("Received response for unknown correlationId: {}",
                                      response.correlationId()))
              .onPresent(pending -> handlePendingResponse(pending, response));
    }

    private void handlePendingResponse(PendingInvocation pending, InvokeResponse response) {
        var promise = pending.promise();
        var requestId = pending.requestId();
        if (response.success()) {
            var buf = Unpooled.wrappedBuffer(response.payload());
            try{
                var result = deserializer.read(buf);
                promise.resolve(Result.success(result));
                log.debug("[requestId={}] Completed invocation [{}]", requestId, response.correlationId());
            } catch (Exception e) {
                promise.resolve(Causes.fromThrowable(e)
                                      .result());
                log.error("[requestId={}] Failed to deserialize response [{}]: {}",
                          requestId,
                          response.correlationId(),
                          e.getMessage());
            } finally{
                buf.release();
            }
        } else {
            var errorMessage = new String(response.payload());
            promise.resolve(new SliceInvokerError.InvocationError(errorMessage).result());
            log.debug("[requestId={}] Invocation failed [{}]: {}", requestId, response.correlationId(), errorMessage);
        }
    }

    private Promise<Endpoint> selectEndpoint(Artifact slice, MethodName method) {
        // Check if there's an active rolling update for this artifact
        var artifactBase = ArtifactBase.artifactBase(slice.groupId(), slice.artifactId());
        return rollingUpdateManager.getActiveUpdate(artifactBase)
                                   .map(update -> selectEndpointWithWeightedRouting(slice, artifactBase, method, update))
                                   .or(() -> endpointRegistry.selectEndpoint(slice, method)
                                                             .async(NO_ENDPOINT_FOUND));
    }

    private Promise<Endpoint> selectEndpointWithWeightedRouting(Artifact slice,
                                                                ArtifactBase artifactBase,
                                                                MethodName method,
                                                                RollingUpdate update) {
        log.debug("Using weighted routing for {} during rolling update {}", slice, update.updateId());
        return endpointRegistry.selectEndpointWithRouting(artifactBase,
                                                          method,
                                                          update.routing(),
                                                          update.oldVersion(),
                                                          update.newVersion())
                               .async(NO_ENDPOINT_FOUND);
    }

    private byte[] serializeRequest(Object request) {
        var buf = Unpooled.buffer();
        serializer.write(buf, request);
        var bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        buf.release();
        return bytes;
    }
}
