package org.pragmatica.aether.invoke;

import io.netty.buffer.Unpooled;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.invoke.InvocationMessage.InvokeRequest;
import org.pragmatica.aether.invoke.InvocationMessage.InvokeResponse;
import org.pragmatica.aether.slice.InternalSlice;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.SliceStore;
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
     * Create a new InvocationHandler.
     */
    static InvocationHandler invocationHandler(NodeId self, ClusterNetwork network) {
        return new InvocationHandlerImpl(self, network);
    }
}

class InvocationHandlerImpl implements InvocationHandler {

    private static final Logger log = LoggerFactory.getLogger(InvocationHandlerImpl.class);

    private final NodeId self;
    private final ClusterNetwork network;

    // Local slices available for invocation
    private final Map<Artifact, InternalSlice> localSlices = new ConcurrentHashMap<>();

    InvocationHandlerImpl(NodeId self, ClusterNetwork network) {
        this.self = self;
        this.network = network;
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

        internalSlice.call(request.method(), inputBuf)
                     .onSuccess(outputBuf -> {
                         if (request.expectResponse()) {
                             // Convert ByteBuf to byte array
                             var responseBytes = new byte[outputBuf.readableBytes()];
                             outputBuf.readBytes(responseBytes);
                             outputBuf.release();

                             sendSuccessResponse(request, responseBytes);
                         }
                     })
                     .onFailure(cause -> {
                         log.error("Invocation failed [{}]: {}",
                                   request.correlationId(), cause.message());
                         if (request.expectResponse()) {
                             sendErrorResponse(request, cause.message());
                         }
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
