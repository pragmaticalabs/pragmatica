package org.pragmatica.aether.invoke;

import io.netty.buffer.Unpooled;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.endpoint.EndpointRegistry;
import org.pragmatica.aether.endpoint.EndpointRegistry.Endpoint;
import org.pragmatica.aether.invoke.InvocationMessage.InvokeRequest;
import org.pragmatica.aether.invoke.InvocationMessage.InvokeResponse;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.cluster.net.ClusterNetwork;
import org.pragmatica.cluster.net.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.message.MessageReceiver;
import org.pragmatica.net.serialization.Deserializer;
import org.pragmatica.net.serialization.Serializer;
import org.pragmatica.utility.ULID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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
public interface SliceInvoker {

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
    <R> Promise<R> invokeWithRetry(Artifact slice, MethodName method, Object request, Class<R> responseType, int maxRetries);

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
     * Create a new SliceInvoker.
     */
    static SliceInvoker sliceInvoker(NodeId self,
                                      ClusterNetwork network,
                                      EndpointRegistry endpointRegistry,
                                      InvocationHandler invocationHandler,
                                      Serializer serializer,
                                      Deserializer deserializer) {
        return new SliceInvokerImpl(self, network, endpointRegistry, invocationHandler,
                                    serializer, deserializer, DEFAULT_TIMEOUT_MS);
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
                                      long timeoutMs) {
        return new SliceInvokerImpl(self, network, endpointRegistry, invocationHandler,
                                    serializer, deserializer, timeoutMs);
    }
}

class SliceInvokerImpl implements SliceInvoker {

    private static final Logger log = LoggerFactory.getLogger(SliceInvokerImpl.class);
    private static final Cause NO_ENDPOINT_FOUND = Causes.cause("No endpoint found for slice/method");
    private static final Cause SLICE_NOT_FOUND = Causes.cause("Slice not found locally");

    private final NodeId self;
    private final ClusterNetwork network;
    private final EndpointRegistry endpointRegistry;
    private final InvocationHandler invocationHandler;
    private final Serializer serializer;
    private final Deserializer deserializer;
    private final long timeoutMs;
    private final ScheduledExecutorService retryScheduler;

    // Pending request-response invocations awaiting responses
    private final ConcurrentHashMap<String, Promise<Object>> pendingInvocations = new ConcurrentHashMap<>();

    SliceInvokerImpl(NodeId self,
                     ClusterNetwork network,
                     EndpointRegistry endpointRegistry,
                     InvocationHandler invocationHandler,
                     Serializer serializer,
                     Deserializer deserializer,
                     long timeoutMs) {
        this.self = self;
        this.network = network;
        this.endpointRegistry = endpointRegistry;
        this.invocationHandler = invocationHandler;
        this.serializer = serializer;
        this.deserializer = deserializer;
        this.timeoutMs = timeoutMs;
        this.retryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "slice-invoker-retry");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public Promise<Unit> invoke(Artifact slice, MethodName method, Object request) {
        return selectEndpoint(slice, method)
                .flatMap(endpoint -> {
                    var payload = serializeRequest(request);
                    var correlationId = ULID.randomULID().toString();

                    var invokeRequest = new InvokeRequest(
                            self, correlationId, slice, method, payload, false
                    );

                    network.send(endpoint.nodeId(), invokeRequest);
                    log.debug("Sent fire-and-forget invocation to {}: {}.{}",
                              endpoint.nodeId(), slice, method);

                    return Promise.success(unit());
                });
    }

    @Override
    @SuppressWarnings("unchecked")
    public <R> Promise<R> invokeAndWait(Artifact slice, MethodName method, Object request, Class<R> responseType) {
        return selectEndpoint(slice, method)
                .flatMap(endpoint -> {
                    var payload = serializeRequest(request);
                    var correlationId = ULID.randomULID().toString();

                    // Create pending promise using callback pattern
                    return Promise.<R>promise(pendingPromise -> {
                        // Store as Object promise for type erasure handling
                        pendingInvocations.put(correlationId, (Promise<Object>) (Promise<?>) pendingPromise);

                        // Set timeout to clean up if no response
                        pendingPromise.timeout(timeSpan(timeoutMs).millis())
                                      .onResult(_ -> pendingInvocations.remove(correlationId));

                        // Send request
                        var invokeRequest = new InvokeRequest(
                                self, correlationId, slice, method, payload, true
                        );
                        network.send(endpoint.nodeId(), invokeRequest);

                        log.debug("Sent request-response invocation to {}: {}.{} [{}]",
                                  endpoint.nodeId(), slice, method, correlationId);
                    });
                });
    }

    @Override
    public <R> Promise<R> invokeWithRetry(Artifact slice, MethodName method, Object request, Class<R> responseType, int maxRetries) {
        return invokeWithRetryInternal(slice, method, request, responseType, 0, maxRetries);
    }

    private <R> Promise<R> invokeWithRetryInternal(Artifact slice, MethodName method, Object request,
                                                    Class<R> responseType, int attempt, int maxRetries) {
        return Promise.promise(promise -> {
            invokeAndWait(slice, method, request, responseType)
                    .onSuccess(promise::succeed)
                    .onFailure(cause -> {
                        if (attempt < maxRetries) {
                            var nextAttempt = attempt + 1;
                            var delayMs = BASE_RETRY_DELAY_MS * (1L << attempt); // Exponential backoff: 100, 200, 400, 800...

                            log.debug("Invocation failed, scheduling retry {}/{} in {}ms: {}.{} - {}",
                                      nextAttempt, maxRetries, delayMs, slice, method, cause.message());

                            retryScheduler.schedule(() -> {
                                invokeWithRetryInternal(slice, method, request, responseType, nextAttempt, maxRetries)
                                        .onSuccess(promise::succeed)
                                        .onFailure(promise::fail);
                            }, delayMs, TimeUnit.MILLISECONDS);
                        } else {
                            log.warn("Invocation failed after {} retries: {}.{} - {}",
                                     maxRetries, slice, method, cause.message());
                            promise.fail(cause);
                        }
                    });
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public <R> Promise<R> invokeLocal(Artifact slice, MethodName method, Object request, Class<R> responseType) {
        return invocationHandler.getLocalSlice(slice)
                .fold(
                        () -> SLICE_NOT_FOUND.<R>promise(),
                        internalSlice -> {
                            var inputBuf = Unpooled.buffer();
                            try {
                                serializer.write(inputBuf, request);
                            } catch (Exception e) {
                                inputBuf.release();
                                throw e;
                            }

                            return internalSlice.call(method, inputBuf)
                                    .onResultRun(inputBuf::release)
                                    .map(outputBuf -> {
                                        try {
                                            return (R) deserializer.read(outputBuf);
                                        } finally {
                                            outputBuf.release();
                                        }
                                    });
                        }
                );
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onInvokeResponse(InvokeResponse response) {
        var pending = pendingInvocations.remove(response.correlationId());

        if (pending == null) {
            log.warn("Received response for unknown correlationId: {}", response.correlationId());
            return;
        }

        if (response.success()) {
            try {
                var buf = Unpooled.wrappedBuffer(response.payload());
                var result = deserializer.read(buf);
                pending.resolve(Result.success(result));
                log.debug("Completed invocation [{}]", response.correlationId());
            } catch (Exception e) {
                pending.resolve(Result.failure(Causes.fromThrowable(e)));
                log.error("Failed to deserialize response [{}]: {}",
                          response.correlationId(), e.getMessage());
            }
        } else {
            var errorMessage = new String(response.payload());
            pending.resolve(Result.failure(new InvocationError(errorMessage)));
            log.debug("Invocation failed [{}]: {}", response.correlationId(), errorMessage);
        }
    }

    private Promise<Endpoint> selectEndpoint(Artifact slice, MethodName method) {
        return endpointRegistry.selectEndpoint(slice, method)
                               .fold(
                                       () -> NO_ENDPOINT_FOUND.promise(),
                                       Promise::success
                               );
    }

    private byte[] serializeRequest(Object request) {
        var buf = Unpooled.buffer();
        serializer.write(buf, request);
        var bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        buf.release();
        return bytes;
    }

    /**
     * Error from remote invocation.
     */
    record InvocationError(String errorMessage) implements Cause {
        @Override
        public String message() {
            return errorMessage;
        }
    }
}
