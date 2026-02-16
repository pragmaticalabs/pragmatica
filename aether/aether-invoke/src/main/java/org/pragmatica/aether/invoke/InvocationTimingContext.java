package org.pragmatica.aether.invoke;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Records nanosecond-precision stage timings for an invocation.
/// Zero-overhead when trace logging is disabled — all operations are no-ops.
@SuppressWarnings("JBCT-RET-01") // Timing methods are fire-and-forget state mutations — void is intentional
public final class InvocationTimingContext {
    private static final Logger log = LoggerFactory.getLogger(InvocationTimingContext.class);
    private static final InvocationTimingContext NOOP = new InvocationTimingContext(false);

    private final boolean active;
    private long startNs;
    private long routeResolvedNs;
    private long serializedNs;
    private long endpointSelectedNs;
    private long networkSentNs;
    private long handlerReceivedNs;
    private long bridgeInvokedNs;
    private long responseSerializedNs;
    private long networkResponseNs;
    private long deserializedNs;
    private long completedNs;

    private InvocationTimingContext(boolean active) {
        this.active = active;
        if (active) {
            this.startNs = System.nanoTime();
        }
    }

    /// Factory: returns a real context if trace logging is enabled, otherwise NOOP.
    public static InvocationTimingContext invocationTimingContext() {
        return log.isTraceEnabled()
               ? new InvocationTimingContext(true)
               : NOOP;
    }

    public void routeResolved() {
        if (active) routeResolvedNs = System.nanoTime();
    }

    public void serialized() {
        if (active) serializedNs = System.nanoTime();
    }

    public void endpointSelected() {
        if (active) endpointSelectedNs = System.nanoTime();
    }

    public void networkSent() {
        if (active) networkSentNs = System.nanoTime();
    }

    public void handlerReceived() {
        if (active) handlerReceivedNs = System.nanoTime();
    }

    public void bridgeInvoked() {
        if (active) bridgeInvokedNs = System.nanoTime();
    }

    public void responseSerialized() {
        if (active) responseSerializedNs = System.nanoTime();
    }

    public void networkResponse() {
        if (active) networkResponseNs = System.nanoTime();
    }

    public void deserialized() {
        if (active) deserializedNs = System.nanoTime();
    }

    /// Complete timing and emit structured log.
    public void complete(String artifact, String method) {
        if (!active) {
            return;
        }
        completedNs = System.nanoTime();
        log.trace("Invocation timing [{}::{}] total={}us route={}us ser={}us endpoint={}us net_send={}us "
                  + "handler={}us bridge={}us resp_ser={}us net_resp={}us deser={}us",
                  artifact,
                  method,
                  deltaUs(startNs, completedNs),
                  deltaUs(startNs, routeResolvedNs),
                  deltaUs(routeResolvedNs, serializedNs),
                  deltaUs(serializedNs, endpointSelectedNs),
                  deltaUs(endpointSelectedNs, networkSentNs),
                  deltaUs(networkSentNs, handlerReceivedNs),
                  deltaUs(handlerReceivedNs, bridgeInvokedNs),
                  deltaUs(bridgeInvokedNs, responseSerializedNs),
                  deltaUs(responseSerializedNs, networkResponseNs),
                  deltaUs(networkResponseNs, deserializedNs));
    }

    private static long deltaUs(long from, long to) {
        return to > 0 && from > 0
               ? (to - from) / 1_000
               : 0;
    }
}
