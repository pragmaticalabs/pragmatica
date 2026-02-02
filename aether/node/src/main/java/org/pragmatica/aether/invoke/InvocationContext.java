package org.pragmatica.aether.invoke;

import org.pragmatica.lang.Option;
import org.pragmatica.utility.KSUID;

import java.util.function.Supplier;

/**
 * ScopedValue-based context for propagating request ID through invocation chains.
 *
 * <p>When a request enters the system (via HTTP or inter-slice call),
 * the request ID is set in this context using scoped execution. When slices invoke other slices,
 * the request ID is automatically propagated within the scope.
 *
 * <p>Usage:
 * <pre>{@code
 * // At entry point (InvocationHandler, HTTP router):
 * InvocationContext.runWithRequestId(requestId, () -> {
 *     // process request - requestId available via currentRequestId()
 * });
 *
 * // When making outbound calls (SliceInvoker):
 * var requestId = InvocationContext.currentRequestId()
 *                                  .or(InvocationContext::generateRequestId);
 * }</pre>
 *
 * <p>For async context propagation across thread boundaries:
 * <pre>{@code
 * var snapshot = InvocationContext.captureContext();
 * // In another thread:
 * snapshot.runWithCaptured(() -> {
 *     // requestId restored
 * });
 * }</pre>
 */
public final class InvocationContext {
    private static final ScopedValue<String> REQUEST_ID = ScopedValue.newInstance();

    private InvocationContext() {}

    /**
     * Get the current request ID if set.
     *
     * @return Option containing the request ID, or empty if not in an invocation context
     */
    public static Option<String> currentRequestId() {
        return REQUEST_ID.isBound()
               ? Option.option(REQUEST_ID.get())
               : Option.empty();
    }

    /**
     * Get current request ID or generate a new one.
     *
     * @return the current request ID, or a newly generated one
     */
    public static String getOrGenerateRequestId() {
        return REQUEST_ID.isBound() && REQUEST_ID.get() != null
               ? REQUEST_ID.get()
               : generateRequestId();
    }

    /**
     * Run a supplier within a request ID scope.
     *
     * @param requestId the request ID to set for this scope
     * @param supplier  the supplier to execute within the scope
     * @param <T>       the return type
     *
     * @return the result of the supplier
     */
    public static <T> T runWithRequestId(String requestId, Supplier<T> supplier) {
        return ScopedValue.where(REQUEST_ID, requestId)
                          .call(supplier::get);
    }

    /**
     * Run a runnable within a request ID scope.
     *
     * @param requestId the request ID to set for this scope
     * @param runnable  the runnable to execute within the scope
     */
    public static void runWithRequestId(String requestId, Runnable runnable) {
        ScopedValue.where(REQUEST_ID, requestId)
                   .run(runnable);
    }

    /**
     * Capture the current context for propagation across async boundaries.
     *
     * @return a snapshot that can be used to restore context in another thread
     */
    public static ContextSnapshot captureContext() {
        return new ContextSnapshot(currentRequestId().or((String) null));
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

    /**
     * A snapshot of the invocation context that can be captured and restored
     * across thread boundaries for async operations.
     */
    public record ContextSnapshot(String requestId) {
        /**
         * Run a supplier with the captured context restored.
         *
         * @param supplier the supplier to execute
         * @param <T>      the return type
         *
         * @return the result of the supplier
         */
        public <T> T runWithCaptured(Supplier<T> supplier) {
            if (requestId == null) {
                return supplier.get();
            }
            return runWithRequestId(requestId, supplier);
        }

        /**
         * Run a runnable with the captured context restored.
         *
         * @param runnable the runnable to execute
         */
        public void runWithCaptured(Runnable runnable) {
            if (requestId == null) {
                runnable.run();
                return;
            }
            runWithRequestId(requestId, runnable);
        }
    }
}
