package org.pragmatica.aether.invoke;

import org.pragmatica.lang.Option;
import org.pragmatica.utility.KSUID;

/**
 * Thread-local context for propagating request ID through invocation chains.
 *
 * <p>When a request enters the system (via HTTP or inter-slice call),
 * the request ID is set in this context. When slices invoke other slices,
 * the request ID is automatically propagated.
 *
 * <p>Usage:
 * <pre>{@code
 * // At entry point (InvocationHandler, HTTP router):
 * InvocationContext.setRequestId(requestId);
 * try {
 *     // process request
 * } finally {
 *     InvocationContext.clear();
 * }
 *
 * // When making outbound calls (SliceInvoker):
 * var requestId = InvocationContext.currentRequestId()
 *                                  .or(InvocationContext::generateRequestId);
 * }</pre>
 */
public final class InvocationContext {
    private static final ThreadLocal<String> REQUEST_ID = new ThreadLocal<>();

    private InvocationContext() {}

    /**
     * Get the current request ID if set.
     *
     * @return Option containing the request ID, or empty if not in an invocation context
     */
    public static Option<String> currentRequestId() {
        return Option.option(REQUEST_ID.get());
    }

    /**
     * Get current request ID or generate a new one.
     *
     * @return the current request ID, or a newly generated one
     */
    public static String getOrGenerateRequestId() {
        var current = REQUEST_ID.get();
        if (current != null) {
            return current;
        }
        return generateRequestId();
    }

    /**
     * Set the request ID for the current invocation chain.
     *
     * @param requestId the request ID to set
     */
    public static void setRequestId(String requestId) {
        REQUEST_ID.set(requestId);
    }

    /**
     * Clear the request ID after invocation completes.
     */
    public static void clear() {
        REQUEST_ID.remove();
    }

    /**
     * Generate a new request ID using KSUID.
     *
     * @return new KSUID-based request ID
     */
    public static String generateRequestId() {
        return KSUID.ksuid()
                    .toString();
    }
}
