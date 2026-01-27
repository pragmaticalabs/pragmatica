package org.pragmatica.aether.invoke;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.invoke.InvocationMessage.InvokeRequest;
import org.pragmatica.aether.invoke.InvocationMessage.InvokeResponse;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.slice.SliceBridge;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.lang.io.TimeSpan;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/**
 * Server-side component that handles incoming slice invocation requests.
 *
 * <p>Receives InvokeRequest messages from remote SliceInvokers,
 * finds the local slice, invokes the method, and optionally sends
 * the response back.
 */
public interface InvocationHandler {
    /**
     * Handle incoming invocation request.
     */
    @MessageReceiver
    void onInvokeRequest(InvokeRequest request);

    /**
     * Register a slice bridge for handling invocations.
     * Called when a slice becomes active.
     */
    void registerSlice(Artifact artifact, SliceBridge bridge);

    /**
     * Unregister a slice.
     * Called when a slice is deactivated.
     */
    void unregisterSlice(Artifact artifact);

    /**
     * Get a local slice bridge for direct invocation.
     *
     * @param artifact The slice artifact
     * @return Option containing the SliceBridge if registered
     */
    Option<SliceBridge> getLocalSlice(Artifact artifact);

    /**
     * Get the metrics collector if configured.
     *
     * @return Option containing the metrics collector
     */
    Option<InvocationMetricsCollector> metricsCollector();

    /**
     * Default invocation timeout (5 minutes).
     * Long timeout to allow operations that may trigger rebalance/node launch.
     */
    TimeSpan DEFAULT_INVOCATION_TIMEOUT = timeSpan(5).minutes();

    /**
     * Create a new InvocationHandler without metrics.
     */
    static InvocationHandler invocationHandler(NodeId self, ClusterNetwork network) {
        return new InvocationHandlerImpl(self, network, Option.empty(), DEFAULT_INVOCATION_TIMEOUT);
    }

    /**
     * Create a new InvocationHandler with metrics collection.
     *
     * @param metricsCollector The metrics collector to use
     */
    static InvocationHandler invocationHandler(NodeId self,
                                               ClusterNetwork network,
                                               InvocationMetricsCollector metricsCollector) {
        return new InvocationHandlerImpl(self, network, Option.option(metricsCollector), DEFAULT_INVOCATION_TIMEOUT);
    }

    /**
     * Create a new InvocationHandler with metrics collection and custom timeout.
     *
     * @param metricsCollector The metrics collector to use
     * @param invocationTimeout Timeout for slice invocations
     */
    static InvocationHandler invocationHandler(NodeId self,
                                               ClusterNetwork network,
                                               InvocationMetricsCollector metricsCollector,
                                               TimeSpan invocationTimeout) {
        return new InvocationHandlerImpl(self, network, Option.option(metricsCollector), invocationTimeout);
    }
}

class InvocationHandlerImpl implements InvocationHandler {
    private static final Logger log = LoggerFactory.getLogger(InvocationHandlerImpl.class);

    private final NodeId self;
    private final ClusterNetwork network;
    private final Option<InvocationMetricsCollector> metricsCollector;
    private final TimeSpan invocationTimeout;

    // Local slice bridges available for invocation
    private final Map<Artifact, SliceBridge> localSlices = new ConcurrentHashMap<>();

    InvocationHandlerImpl(NodeId self,
                          ClusterNetwork network,
                          Option<InvocationMetricsCollector> metricsCollector,
                          TimeSpan invocationTimeout) {
        this.self = self;
        this.network = network;
        this.metricsCollector = metricsCollector;
        this.invocationTimeout = invocationTimeout;
    }

    @Override
    public void registerSlice(Artifact artifact, SliceBridge bridge) {
        localSlices.put(artifact, bridge);
        log.debug("Registered slice for invocation: {}", artifact);
    }

    @Override
    public void unregisterSlice(Artifact artifact) {
        localSlices.remove(artifact);
        log.debug("Unregistered slice from invocation: {}", artifact);
    }

    @Override
    public Option<SliceBridge> getLocalSlice(Artifact artifact) {
        return Option.option(localSlices.get(artifact));
    }

    @Override
    public Option<InvocationMetricsCollector> metricsCollector() {
        return metricsCollector;
    }

    @Override
    public void onInvokeRequest(InvokeRequest request) {
        log.debug("[requestId={}] Received invocation request [{}]: {}.{}",
                  request.requestId(),
                  request.correlationId(),
                  request.targetSlice(),
                  request.method());
        // Set the request ID in context for chain propagation
        InvocationContext.setRequestId(request.requestId());
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
        // SliceBridge uses byte[] directly - no ByteBuf conversion needed
        bridge.invoke(request.method()
                             .name(),
                      request.payload())
              .timeout(invocationTimeout)
              .onSuccess(responseData -> handleInvocationSuccess(request, responseData, startTime, requestBytes))
              .onFailure(cause -> handleInvocationFailure(request, cause, startTime, requestBytes));
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
        metricsCollector.onPresent(mc -> mc.recordSuccess(request.targetSlice(),
                                                          request.method(),
                                                          durationNs,
                                                          requestBytes,
                                                          responseBytes));
        InvocationContext.clear();
    }

    private void handleInvocationFailure(InvokeRequest request,
                                         org.pragmatica.lang.Cause cause,
                                         long startTime,
                                         int requestBytes) {
        var durationNs = System.nanoTime() - startTime;
        log.error("[requestId={}] Invocation failed [{}]: {}",
                  request.requestId(),
                  request.correlationId(),
                  cause.message());
        if (request.expectResponse()) {
            sendErrorResponse(request, cause.message());
        }
        metricsCollector.onPresent(mc -> mc.recordFailure(request.targetSlice(),
                                                          request.method(),
                                                          durationNs,
                                                          requestBytes,
                                                          cause.getClass()
                                                               .getSimpleName()));
        InvocationContext.clear();
    }

    private void sendSuccessResponse(InvokeRequest request, byte[] payload) {
        var response = new InvokeResponse(self, request.correlationId(), request.requestId(), true, payload);
        network.send(request.sender(), response);
        log.debug("[requestId={}] Sent success response [{}]", request.requestId(), request.correlationId());
    }

    private void sendErrorResponse(InvokeRequest request, String errorMessage) {
        var response = new InvokeResponse(self,
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
