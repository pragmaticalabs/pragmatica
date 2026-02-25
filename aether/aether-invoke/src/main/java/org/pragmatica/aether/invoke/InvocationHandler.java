package org.pragmatica.aether.invoke;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.http.HttpRoutePublisher;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.invoke.InvocationMessage.InvokeRequest;
import org.pragmatica.aether.invoke.InvocationMessage.InvokeResponse;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.slice.SliceBridge;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Server-side component that handles incoming slice invocation requests.
///
///
/// Receives InvokeRequest messages from remote SliceInvokers,
/// finds the local slice, invokes the method, and optionally sends
/// the response back.
public interface InvocationHandler {
    /// Handle incoming invocation request.
    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01") // MessageReceiver callback — void required by messaging framework
    void onInvokeRequest(InvokeRequest request);

    /// Register a slice bridge for handling invocations.
    /// Called when a slice becomes active.
    @SuppressWarnings("JBCT-RET-01") // Fire-and-forget state mutation
    void registerSlice(Artifact artifact, SliceBridge bridge);

    /// Unregister a slice.
    /// Called when a slice is deactivated.
    @SuppressWarnings("JBCT-RET-01") // Fire-and-forget state mutation
    void unregisterSlice(Artifact artifact);

    /// Get a local slice bridge for direct invocation.
    ///
    /// @param artifact The slice artifact
    /// @return Option containing the SliceBridge if registered
    Option<SliceBridge> localSlice(Artifact artifact);

    /// Find the bridge for a given classloader.
    /// Used by SliceInvoker to find the sender's bridge for serialization.
    ///
    /// @param classLoader The classloader to look up
    /// @return Option containing the SliceBridge if found
    Option<SliceBridge> findBridgeByClassLoader(ClassLoader classLoader);

    /// Get the metrics collector if configured.
    ///
    /// @return Option containing the metrics collector
    Option<InvocationMetricsCollector> metricsCollector();

    /// Default invocation timeout (5 minutes).
    /// Long timeout to allow operations that may trigger rebalance/node launch.
    TimeSpan DEFAULT_INVOCATION_TIMEOUT = timeSpan(5).minutes();

    /// Create a new InvocationHandler without metrics or HTTP routing.
    static InvocationHandler invocationHandler(NodeId self, ClusterNetwork network) {
        return new InvocationHandlerImpl(self,
                                         network,
                                         Option.none(),
                                         DEFAULT_INVOCATION_TIMEOUT,
                                         Option.none(),
                                         Option.none(),
                                         Option.none(),
                                         ObservabilityInterceptor.noOp());
    }

    /// Create a new InvocationHandler with metrics collection.
    ///
    /// @param metricsCollector The metrics collector to use
    static InvocationHandler invocationHandler(NodeId self,
                                               ClusterNetwork network,
                                               InvocationMetricsCollector metricsCollector) {
        return new InvocationHandlerImpl(self,
                                         network,
                                         Option.option(metricsCollector),
                                         DEFAULT_INVOCATION_TIMEOUT,
                                         Option.none(),
                                         Option.none(),
                                         Option.none(),
                                         ObservabilityInterceptor.noOp());
    }

    /// Create a new InvocationHandler with metrics collection, serialization, and HTTP routing.
    ///
    /// @param metricsCollector   The metrics collector to use
    /// @param serializer         Serializer for response serialization
    /// @param deserializer       Deserializer for payload deserialization
    /// @param httpRoutePublisher HTTP route publisher for SliceRouter access
    static InvocationHandler invocationHandler(NodeId self,
                                               ClusterNetwork network,
                                               InvocationMetricsCollector metricsCollector,
                                               Serializer serializer,
                                               Deserializer deserializer,
                                               HttpRoutePublisher httpRoutePublisher) {
        return new InvocationHandlerImpl(self,
                                         network,
                                         Option.option(metricsCollector),
                                         DEFAULT_INVOCATION_TIMEOUT,
                                         Option.option(serializer),
                                         Option.option(deserializer),
                                         Option.option(httpRoutePublisher),
                                         ObservabilityInterceptor.noOp());
    }

    /// Create a new InvocationHandler with metrics collection and custom timeout.
    ///
    /// @param metricsCollector  The metrics collector to use
    /// @param invocationTimeout Timeout for slice invocations
    static InvocationHandler invocationHandler(NodeId self,
                                               ClusterNetwork network,
                                               InvocationMetricsCollector metricsCollector,
                                               TimeSpan invocationTimeout) {
        return new InvocationHandlerImpl(self,
                                         network,
                                         Option.option(metricsCollector),
                                         invocationTimeout,
                                         Option.none(),
                                         Option.none(),
                                         Option.none(),
                                         ObservabilityInterceptor.noOp());
    }

    /// Create a new InvocationHandler with metrics, serialization, HTTP routing, and observability interceptor.
    /// Use this when the same interceptor should be shared with SliceInvoker.
    static InvocationHandler invocationHandler(NodeId self,
                                               ClusterNetwork network,
                                               InvocationMetricsCollector metricsCollector,
                                               Serializer serializer,
                                               Deserializer deserializer,
                                               HttpRoutePublisher httpRoutePublisher,
                                               ObservabilityInterceptor observabilityInterceptor) {
        return new InvocationHandlerImpl(self,
                                         network,
                                         Option.option(metricsCollector),
                                         DEFAULT_INVOCATION_TIMEOUT,
                                         Option.option(serializer),
                                         Option.option(deserializer),
                                         Option.option(httpRoutePublisher),
                                         observabilityInterceptor);
    }
}

class InvocationHandlerImpl implements InvocationHandler {
    private static final Logger log = LoggerFactory.getLogger(InvocationHandlerImpl.class);

    private final NodeId self;
    private final ClusterNetwork network;
    private final Option<InvocationMetricsCollector> metricsCollector;
    private final TimeSpan invocationTimeout;
    private final Option<Serializer> serializer;
    private final Option<Deserializer> deserializer;
    private final Option<HttpRoutePublisher> httpRoutePublisher;
    private final ObservabilityInterceptor observabilityInterceptor;

    // Local slice bridges available for invocation
    private final Map<Artifact, SliceBridge> localSlices = new ConcurrentHashMap<>();
    private final Map<ClassLoader, SliceBridge> classLoaderBridges = new ConcurrentHashMap<>();

    InvocationHandlerImpl(NodeId self,
                          ClusterNetwork network,
                          Option<InvocationMetricsCollector> metricsCollector,
                          TimeSpan invocationTimeout,
                          Option<Serializer> serializer,
                          Option<Deserializer> deserializer,
                          Option<HttpRoutePublisher> httpRoutePublisher,
                          ObservabilityInterceptor observabilityInterceptor) {
        this.self = self;
        this.network = network;
        this.metricsCollector = metricsCollector;
        this.invocationTimeout = invocationTimeout;
        this.serializer = serializer;
        this.deserializer = deserializer;
        this.httpRoutePublisher = httpRoutePublisher;
        this.observabilityInterceptor = observabilityInterceptor;
    }

    @Override
    @SuppressWarnings("JBCT-RET-01")
    public void registerSlice(Artifact artifact, SliceBridge bridge) {
        localSlices.put(artifact, bridge);
        classLoaderBridges.put(bridge.classLoader(), bridge);
        log.debug("Registered slice for invocation: {}", artifact);
    }

    @Override
    @SuppressWarnings("JBCT-RET-01")
    public void unregisterSlice(Artifact artifact) {
        var bridge = localSlices.remove(artifact);
        if (bridge != null) {
            classLoaderBridges.remove(bridge.classLoader());
        }
        log.debug("Unregistered slice from invocation: {}", artifact);
    }

    @Override
    public Option<SliceBridge> localSlice(Artifact artifact) {
        return Option.option(localSlices.get(artifact));
    }

    @Override
    public Option<SliceBridge> findBridgeByClassLoader(ClassLoader classLoader) {
        return Option.option(classLoaderBridges.get(classLoader));
    }

    @Override
    public Option<InvocationMetricsCollector> metricsCollector() {
        return metricsCollector;
    }

    @Override
    @SuppressWarnings("JBCT-RET-01")
    public void onInvokeRequest(InvokeRequest request) {
        if (log.isDebugEnabled()) {
            log.debug("[requestId={}] Received InvokeRequest [{}]: {}.{}",
                      request.requestId(),
                      request.correlationId(),
                      request.targetSlice(),
                      request.method());
        }
        // Run within full invocation context scope for chain propagation
        InvocationContext.runWithContext(request.requestId(),
                                         null,
                                         null,
                                         request.depth(),
                                         request.sampled(),
                                         () -> processInvokeRequest(request));
    }

    private void processInvokeRequest(InvokeRequest request) {
        Option.option(localSlices.get(request.targetSlice()))
              .onEmpty(() -> handleSliceNotFound(request))
              .onPresent(bridge -> invokeSliceMethod(request, bridge));
    }

    private void handleSliceNotFound(InvokeRequest request) {
        log.warn("[requestId={}] Slice not found for invocation: {}", request.requestId(), request.targetSlice());
        if (request.expectResponse()) {
            sendErrorResponse(request, "Slice not found: " + request.targetSlice());
        }
    }

    private void invokeSliceMethod(InvokeRequest request, SliceBridge bridge) {
        var startTime = System.nanoTime();
        var requestBytes = request.payload().length;
        // Record invocation start for active invocation tracking
        metricsCollector.onPresent(mc -> mc.recordStart(request.targetSlice(), request.method()));
        // Delegate observability to interceptor, then handle response/metrics
        observabilityInterceptor.intercept(request.targetSlice(),
                                           request.method(),
                                           request.requestId(),
                                           request.depth(),
                                           true,
                                           // local=true for server-side handler
        () -> invokeWithHttpRouting(request, bridge))
                                .timeout(invocationTimeout)
                                .onSuccess(data -> handleInvocationSuccess(request, data, startTime, requestBytes))
                                .onFailure(cause -> handleInvocationFailure(request, cause, startTime, requestBytes));
    }

    /// Attempt to route HTTP requests through SliceRouter if available.
    /// Falls back to direct SliceBridge invocation for non-HTTP requests or when SliceRouter is unavailable.
    private Promise<byte[]> invokeWithHttpRouting(InvokeRequest request, SliceBridge bridge) {
        // Check if we have the necessary components for HTTP routing
        if (deserializer.isEmpty() || serializer.isEmpty() || httpRoutePublisher.isEmpty()) {
            return bridge.invoke(request.method()
                                        .name(),
                                 request.payload());
        }
        // Try to deserialize and check if it's an HttpRequestContext
        var des = deserializer.unwrap();
        var ser = serializer.unwrap();
        var routePublisher = httpRoutePublisher.unwrap();
        try{
            Object payload = des.decode(request.payload());
            if (payload instanceof HttpRequestContext httpContext) {
                // Check if we have a SliceRouter for this artifact
                var sliceRouterOpt = routePublisher.getSliceRouter(request.targetSlice());
                if (sliceRouterOpt.isPresent()) {
                    log.debug("[requestId={}] Routing HTTP request through SliceRouter for {}",
                              request.requestId(),
                              request.targetSlice());
                    return sliceRouterOpt.unwrap()
                                         .handle(httpContext)
                                         .map(ser::encode);
                }
            }
        } catch (Exception e) {
            log.debug("[requestId={}] Payload is not HttpRequestContext, using direct invocation: {}",
                      request.requestId(),
                      e.getMessage());
        }
        // Fall back to direct SliceBridge invocation
        return bridge.invoke(request.method()
                                    .name(),
                             request.payload());
    }

    private void handleInvocationSuccess(InvokeRequest request, byte[] responseData, long startTime, int requestBytes) {
        var durationNs = System.nanoTime() - startTime;
        var responseBytes = responseData.length;
        if (request.expectResponse()) {
            sendSuccessResponse(request, responseData);
        }
        log.debug("[requestId={}] Invocation completed in {}ms: {}.{}",
                  request.requestId(),
                  durationNs / 1_000_000,
                  request.targetSlice(),
                  request.method());
        metricsCollector.onPresent(mc -> recordSuccessMetrics(mc, request, durationNs, requestBytes, responseBytes));
    }

    private void recordSuccessMetrics(InvocationMetricsCollector mc,
                                      InvokeRequest request,
                                      long durationNs,
                                      int requestBytes,
                                      int responseBytes) {
        mc.recordComplete(request.targetSlice(), request.method());
        mc.recordSuccess(request.targetSlice(), request.method(), durationNs, requestBytes, responseBytes);
    }

    private void handleInvocationFailure(InvokeRequest request,
                                         Cause cause,
                                         long startTime,
                                         int requestBytes) {
        var durationNs = System.nanoTime() - startTime;
        var errorType = cause.getClass()
                             .getSimpleName();
        log.error("[requestId={}] Failed to complete invocation [{}]: {}",
                  request.requestId(),
                  request.correlationId(),
                  cause.message());
        if (request.expectResponse()) {
            sendErrorResponse(request, cause.message());
        }
        metricsCollector.onPresent(mc -> recordFailureMetrics(mc, request, durationNs, requestBytes, errorType));
    }

    private void recordFailureMetrics(InvocationMetricsCollector mc,
                                      InvokeRequest request,
                                      long durationNs,
                                      int requestBytes,
                                      String errorType) {
        mc.recordComplete(request.targetSlice(), request.method());
        mc.recordFailure(request.targetSlice(), request.method(), durationNs, requestBytes, errorType);
    }

    private void sendSuccessResponse(InvokeRequest request, byte[] payload) {
        var response = InvokeResponse.invokeResponse(self, request.correlationId(), request.requestId(), true, payload);
        network.send(request.sender(), response);
        log.debug("[requestId={}] Sent success response [{}]", request.requestId(), request.correlationId());
    }

    private void sendErrorResponse(InvokeRequest request, String errorMessage) {
        var response = InvokeResponse.invokeResponse(self,
                                                     request.correlationId(),
                                                     request.requestId(),
                                                     false,
                                                     errorMessage.getBytes(StandardCharsets.UTF_8));
        network.send(request.sender(), response);
        log.debug("[requestId={}] Sent error response [{}]: {}",
                  request.requestId(),
                  request.correlationId(),
                  errorMessage);
    }
}
