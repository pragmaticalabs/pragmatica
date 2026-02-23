package org.pragmatica.aether.invoke;

import org.pragmatica.lang.Option;
import org.pragmatica.utility.KSUID;

import java.util.function.Supplier;

import org.slf4j.MDC;

/// ScopedValue-based context for propagating request ID through invocation chains.
///
///
/// When a request enters the system (via HTTP or inter-slice call),
/// the request ID is set in this context using scoped execution. When slices invoke other slices,
/// the request ID is automatically propagated within the scope.
///
///
/// Usage:
/// ```{@code
/// // At entry point (InvocationHandler, HTTP router):
/// InvocationContext.runWithRequestId(requestId, () -> {
///     // process request - requestId available via currentRequestId()
/// });
///
/// // When making outbound calls (SliceInvoker):
/// var requestId = InvocationContext.currentRequestId()
///                                  .or(InvocationContext::generateRequestId);
/// }```
///
///
/// For async context propagation across thread boundaries:
/// ```{@code
/// var snapshot = InvocationContext.captureContext();
/// // In another thread:
/// snapshot.runWithCaptured(() -> {
///     // requestId restored
/// });
/// }```
public final class InvocationContext {
    private static final ScopedValue<String> REQUEST_ID = ScopedValue.newInstance();
    private static final ScopedValue<Integer> DEPTH = ScopedValue.newInstance();
    private static final ScopedValue<Boolean> SAMPLED = ScopedValue.newInstance();
    private static final String MDC_KEY = "requestId";

    private InvocationContext() {}

    /// Get the current request ID if set.
    ///
    /// @return Option containing the request ID, or empty if not in an invocation context
    public static Option<String> currentRequestId() {
        return REQUEST_ID.isBound()
               ? Option.option(REQUEST_ID.get())
               : Option.empty();
    }

    /// Get current request ID or generate a new one.
    ///
    /// @return the current request ID, or a newly generated one
    @SuppressWarnings("JBCT-RET-03") // Returns non-null String (fallback to generateRequestId)
    public static String getOrGenerateRequestId() {
        return REQUEST_ID.isBound() && REQUEST_ID.get() != null
               ? REQUEST_ID.get()
               : generateRequestId();
    }

    /// Get the current invocation depth (0 if not in an invocation context).
    public static int currentDepth() {
        return DEPTH.isBound()
               ? DEPTH.get()
               : 0;
    }

    /// Check if the current request is sampled for tracing.
    public static boolean isSampled() {
        return SAMPLED.isBound() && SAMPLED.get();
    }

    /// Run a supplier within a request ID scope.
    ///
    /// @param requestId the request ID to set for this scope
    /// @param supplier  the supplier to execute within the scope
    /// @param <T>       the return type
    ///
    /// @return the result of the supplier
    public static <T> T runWithRequestId(String requestId, Supplier<T> supplier) {
        MDC.put(MDC_KEY, requestId);
        try{
            return ScopedValue.where(REQUEST_ID, requestId)
                              .call(supplier::get);
        } finally{
            MDC.remove(MDC_KEY);
        }
    }

    /// Run a runnable within a request ID scope.
    ///
    /// @param requestId the request ID to set for this scope
    /// @param runnable  the runnable to execute within the scope
    @SuppressWarnings("JBCT-RET-01") // ScopedValue.run() requires Runnable — void is inherent
    public static void runWithRequestId(String requestId, Runnable runnable) {
        MDC.put(MDC_KEY, requestId);
        try{
            ScopedValue.where(REQUEST_ID, requestId)
                       .run(runnable);
        } finally{
            MDC.remove(MDC_KEY);
        }
    }

    /// Run a supplier within a full invocation context scope (requestId + depth + sampled).
    ///
    /// @param requestId the request ID to set for this scope
    /// @param depth     the invocation depth
    /// @param sampled   whether the request is sampled for tracing
    /// @param supplier  the supplier to execute within the scope
    /// @param <T>       the return type
    ///
    /// @return the result of the supplier
    public static <T> T runWithContext(String requestId, int depth, boolean sampled, Supplier<T> supplier) {
        MDC.put(MDC_KEY, requestId);
        try{
            return ScopedValue.where(REQUEST_ID, requestId)
                              .where(DEPTH, depth)
                              .where(SAMPLED, sampled)
                              .call(supplier::get);
        } finally{
            MDC.remove(MDC_KEY);
        }
    }

    /// Run a runnable within a full invocation context scope (requestId + depth + sampled).
    ///
    /// @param requestId the request ID to set for this scope
    /// @param depth     the invocation depth
    /// @param sampled   whether the request is sampled for tracing
    /// @param runnable  the runnable to execute within the scope
    @SuppressWarnings("JBCT-RET-01") // ScopedValue.run() requires Runnable — void is inherent
    public static void runWithContext(String requestId, int depth, boolean sampled, Runnable runnable) {
        MDC.put(MDC_KEY, requestId);
        try{
            ScopedValue.where(REQUEST_ID, requestId)
                       .where(DEPTH, depth)
                       .where(SAMPLED, sampled)
                       .run(runnable);
        } finally{
            MDC.remove(MDC_KEY);
        }
    }

    /// Capture the current context for propagation across async boundaries.
    ///
    /// @return a snapshot that can be used to restore context in another thread
    @SuppressWarnings("JBCT-RET-03") // Nullable requestId required for ContextSnapshot — null means "no context"
    public static ContextSnapshot captureContext() {
        return new ContextSnapshot(currentRequestId().or((String) null), currentDepth(), isSampled());
    }

    /// Generate a new request ID using KSUID.
    ///
    /// @return new KSUID-based request ID
    public static String generateRequestId() {
        return KSUID.ksuid()
                    .toString();
    }

    /// A snapshot of the invocation context that can be captured and restored
    /// across thread boundaries for async operations.
    ///
    /// @param requestId the request ID (null if no context was active)
    /// @param depth     the invocation depth at capture time
    /// @param sampled   whether the request was sampled at capture time
    public record ContextSnapshot(String requestId, int depth, boolean sampled) {
        /// Run a supplier with the captured context restored.
        ///
        /// @param supplier the supplier to execute
        /// @param <T>      the return type
        ///
        /// @return the result of the supplier
        public <T> T runWithCaptured(Supplier<T> supplier) {
            if (requestId == null) {
                return supplier.get();
            }
            return runWithContext(requestId, depth, sampled, supplier);
        }

        /// Run a runnable with the captured context restored.
        ///
        /// @param runnable the runnable to execute
        @SuppressWarnings("JBCT-RET-01") // Delegates to Runnable-based API
        public void runWithCaptured(Runnable runnable) {
            if (requestId == null) {
                runnable.run();
                return;
            }
            runWithContext(requestId, depth, sampled, runnable);
        }
    }
}
