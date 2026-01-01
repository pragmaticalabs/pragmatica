package org.pragmatica.aether.metrics.invocation;

import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.lang.Option;

/**
 * Captured details of a slow invocation for debugging.
 * <p>
 * Slow invocations are captured when their duration exceeds the configured threshold.
 * This provides detailed information for troubleshooting without the overhead of
 * capturing every invocation.
 *
 * @param methodName    The method that was invoked
 * @param timestampNs   System.nanoTime() when the invocation started
 * @param durationNs    Total duration in nanoseconds
 * @param requestBytes  Size of the serialized request
 * @param responseBytes Size of the serialized response (0 if fire-and-forget)
 * @param success       Whether the invocation succeeded
 * @param errorType     Error type if failed (class name of the Cause)
 */
public record SlowInvocation(
 MethodName methodName,
 long timestampNs,
 long durationNs,
 int requestBytes,
 int responseBytes,
 boolean success,
 Option<String> errorType) {
    /**
     * Create a successful slow invocation record.
     */
    public static SlowInvocation success(MethodName method,
                                         long timestampNs,
                                         long durationNs,
                                         int requestBytes,
                                         int responseBytes) {
        return new SlowInvocation(method, timestampNs, durationNs, requestBytes, responseBytes, true, Option.empty());
    }

    /**
     * Create a failed slow invocation record.
     */
    public static SlowInvocation failure(MethodName method,
                                         long timestampNs,
                                         long durationNs,
                                         int requestBytes,
                                         String errorType) {
        return new SlowInvocation(method, timestampNs, durationNs, requestBytes, 0, false, Option.option(errorType));
    }

    /**
     * Duration in milliseconds for display.
     */
    public double durationMs() {
        return durationNs / 1_000_000.0;
    }
}
