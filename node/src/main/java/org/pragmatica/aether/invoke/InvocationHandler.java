package org.pragmatica.aether.invoke;

import io.netty.buffer.Unpooled;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.invoke.InvocationMessage.InvokeRequest;
import org.pragmatica.aether.invoke.InvocationMessage.InvokeResponse;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.slice.InternalSlice;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.cluster.net.ClusterNetwork;
import org.pragmatica.cluster.net.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.message.MessageReceiver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
     * Register an internal slice for handling invocations.
     * Called when a slice becomes active.
     */
    void registerSlice(Artifact artifact, InternalSlice internalSlice);

    /**
     * Unregister a slice.
     * Called when a slice is deactivated.
     */
    void unregisterSlice(Artifact artifact);

    /**
     * Get a local slice for direct invocation.
     *
     * @param artifact The slice artifact
     * @return Option containing the InternalSlice if registered
     */
    Option<InternalSlice> getLocalSlice(Artifact artifact);

    /**
     * Get the metrics collector if configured.
     *
     * @return Option containing the metrics collector
     */
    Option<InvocationMetricsCollector> metricsCollector();

    /**
     * Create a new InvocationHandler without metrics.
     */
    static InvocationHandler invocationHandler(NodeId self, ClusterNetwork network) {
        return new InvocationHandlerImpl(self, network, Option.empty());
    }

    /**
     * Create a new InvocationHandler with metrics collection.
     *
     * @param metricsCollector The metrics collector to use
     */
    static InvocationHandler invocationHandler(NodeId self, ClusterNetwork network,
                                                InvocationMetricsCollector metricsCollector) {
        return new InvocationHandlerImpl(self, network, Option.option(metricsCollector));
    }
}

class InvocationHandlerImpl implements InvocationHandler {

    private static final Logger log = LoggerFactory.getLogger(InvocationHandlerImpl.class);

    private final NodeId self;
    private final ClusterNetwork network;
    private final Option<InvocationMetricsCollector> metricsCollector;

    // Local slices available for invocation
    private final Map<Artifact, InternalSlice> localSlices = new ConcurrentHashMap<>();

    InvocationHandlerImpl(NodeId self, ClusterNetwork network, Option<InvocationMetricsCollector> metricsCollector) {
        this.self = self;
        this.network = network;
        this.metricsCollector = metricsCollector;
    }

    @Override
    public void registerSlice(Artifact artifact, InternalSlice internalSlice) {
        localSlices.put(artifact, internalSlice);
        log.debug("Registered slice for invocation: {}", artifact);
    }

    @Override
    public void unregisterSlice(Artifact artifact) {
        localSlices.remove(artifact);
        log.debug("Unregistered slice from invocation: {}", artifact);
    }

    @Override
    public Option<InternalSlice> getLocalSlice(Artifact artifact) {
        return Option.option(localSlices.get(artifact));
    }

    @Override
    public Option<InvocationMetricsCollector> metricsCollector() {
        return metricsCollector;
    }

    @Override
    public void onInvokeRequest(InvokeRequest request) {
        log.debug("Received invocation request [{}]: {}.{}",
                  request.correlationId(), request.targetSlice(), request.method());

        var internalSlice = localSlices.get(request.targetSlice());

        if (internalSlice == null) {
            log.warn("Slice not found for invocation: {}", request.targetSlice());
            if (request.expectResponse()) {
                sendErrorResponse(request, "Slice not found: " + request.targetSlice());
            }
            return;
        }

        // Invoke the slice method
        invokeSliceMethod(request, internalSlice);
    }

    private void invokeSliceMethod(InvokeRequest request, InternalSlice internalSlice) {
        var inputBuf = Unpooled.wrappedBuffer(request.payload());
        var startTime = System.nanoTime();
        var requestBytes = request.payload().length;

        internalSlice.call(request.method(), inputBuf)
                     .onSuccess(outputBuf -> {
                         var durationNs = System.nanoTime() - startTime;
                         var responseBytes = 0;
                         try {
                             responseBytes = outputBuf.readableBytes();
                             if (request.expectResponse()) {
                                 // Convert ByteBuf to byte array
                                 var responseData = new byte[responseBytes];
                                 outputBuf.readBytes(responseData);
                                 sendSuccessResponse(request, responseData);
                             }
                         } finally {
                             // Always release outputBuf regardless of expectResponse
                             outputBuf.release();
                         }

                         // Record success metrics
                         final int respSize = responseBytes;
                         metricsCollector.onPresent(mc ->
                             mc.recordSuccess(request.targetSlice(), request.method(),
                                              durationNs, requestBytes, respSize));
                     })
                     .onFailure(cause -> {
                         var durationNs = System.nanoTime() - startTime;
                         log.error("Invocation failed [{}]: {}",
                                   request.correlationId(), cause.message());
                         if (request.expectResponse()) {
                             sendErrorResponse(request, cause.message());
                         }

                         // Record failure metrics
                         metricsCollector.onPresent(mc ->
                             mc.recordFailure(request.targetSlice(), request.method(),
                                              durationNs, requestBytes, cause.getClass().getSimpleName()));
                     })
                     .onResultRun(() -> {
                         // Always release inputBuf after call completes
                         inputBuf.release();
                     });
    }

    private void sendSuccessResponse(InvokeRequest request, byte[] payload) {
        var response = new InvokeResponse(
                self,
                request.correlationId(),
                true,
                payload
        );
        network.send(request.sender(), response);
        log.debug("Sent success response [{}]", request.correlationId());
    }

    private void sendErrorResponse(InvokeRequest request, String errorMessage) {
        var response = new InvokeResponse(
                self,
                request.correlationId(),
                false,
                errorMessage.getBytes(StandardCharsets.UTF_8)
        );
        network.send(request.sender(), response);
        log.debug("Sent error response [{}]: {}", request.correlationId(), errorMessage);
    }
}
