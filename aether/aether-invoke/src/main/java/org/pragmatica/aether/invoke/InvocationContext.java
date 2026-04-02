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
    private static final ScopedValue<String> PRINCIPAL = ScopedValue.newInstance();
    private static final ScopedValue<String> ORIGIN_NODE = ScopedValue.newInstance();
    private static final ScopedValue<Integer> DEPTH = ScopedValue.newInstance();
    private static final ScopedValue<Boolean> SAMPLED = ScopedValue.newInstance();
    private static final ScopedValue<String> AB_VARIANT = ScopedValue.newInstance();

    private static final String MDC_KEY = "requestId";
    private static final String MDC_PRINCIPAL = "principal";
    private static final String MDC_ORIGIN_NODE = "originNode";

    private InvocationContext() {}

    /// Get the current request ID if set.
    ///
    /// @return Option containing the request ID, or empty if not in an invocation context
    public static Option<String> currentRequestId() {
        return REQUEST_ID.isBound()
               ? Option.option(REQUEST_ID.get())
               : Option.empty();
    }

    /// Get the current principal if set.
    ///
    /// @return Option containing the principal, or empty if not in a context with principal
    public static Option<String> currentPrincipal() {
        return PRINCIPAL.isBound()
               ? Option.option(PRINCIPAL.get())
               : Option.empty();
    }

    /// Get the current origin node if set.
    ///
    /// @return Option containing the origin node, or empty if not in a context with origin node
    public static Option<String> currentOriginNode() {
        return ORIGIN_NODE.isBound()
               ? Option.option(ORIGIN_NODE.get())
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

    /// Get the current A/B test variant if set.
    ///
    /// @return Option containing the variant name, or empty if not in an A/B test context
    public static Option<String> currentVariant() {
        return AB_VARIANT.isBound()
               ? Option.option(AB_VARIANT.get())
               : Option.empty();
    }

    /// Run a supplier within an A/B test variant scope.
    ///
    /// @param variant  the variant name to set for this scope
    /// @param supplier the supplier to execute within the scope
    /// @param <T>      the return type
    ///
    /// @return the result of the supplier
    public static <T> T runWithVariant(String variant, Supplier<T> supplier) {
        return ScopedValue.where(AB_VARIANT, variant).call(supplier::get);
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
        try {
            return ScopedValue.where(REQUEST_ID, requestId).call(supplier::get);
        } finally {
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
        try {
            ScopedValue.where(REQUEST_ID, requestId).run(runnable);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /// Run a supplier within a full context scope (requestId + principal + originNode + depth + sampled).
    ///
    /// @param requestId  the request ID to set for this scope
    /// @param principal  the principal identity (nullable)
    /// @param originNode the origin node identifier (nullable)
    /// @param depth      the invocation depth
    /// @param sampled    whether the request is sampled for tracing
    /// @param supplier   the supplier to execute within the scope
    /// @param <T>        the return type
    ///
    /// @return the result of the supplier
    public static <T> T runWithContext(String requestId,
                                       String principal,
                                       String originNode,
                                       int depth,
                                       boolean sampled,
                                       Supplier<T> supplier) {
        MDC.put(MDC_KEY, requestId);
        putIfNotNull(MDC_PRINCIPAL, principal);
        putIfNotNull(MDC_ORIGIN_NODE, originNode);
        try {
            var carrier = ScopedValue.where(REQUEST_ID, requestId).where(DEPTH, depth)
                                           .where(SAMPLED, sampled);
            if ( principal != null) {
            carrier = carrier.where(PRINCIPAL, principal);}
            if ( originNode != null) {
            carrier = carrier.where(ORIGIN_NODE, originNode);}
            return carrier.call(supplier::get);
        } finally {
            MDC.remove(MDC_KEY);
            MDC.remove(MDC_PRINCIPAL);
            MDC.remove(MDC_ORIGIN_NODE);
        }
    }

    /// Run a runnable within a full context scope (requestId + principal + originNode + depth + sampled).
    ///
    /// @param requestId  the request ID to set for this scope
    /// @param principal  the principal identity (nullable)
    /// @param originNode the origin node identifier (nullable)
    /// @param depth      the invocation depth
    /// @param sampled    whether the request is sampled for tracing
    /// @param runnable   the runnable to execute within the scope
    @SuppressWarnings("JBCT-RET-01") // ScopedValue.run() requires Runnable — void is inherent
    public static void runWithContext(String requestId,
                                      String principal,
                                      String originNode,
                                      int depth,
                                      boolean sampled,
                                      Runnable runnable) {
        MDC.put(MDC_KEY, requestId);
        putIfNotNull(MDC_PRINCIPAL, principal);
        putIfNotNull(MDC_ORIGIN_NODE, originNode);
        try {
            var carrier = ScopedValue.where(REQUEST_ID, requestId).where(DEPTH, depth)
                                           .where(SAMPLED, sampled);
            if ( principal != null) {
            carrier = carrier.where(PRINCIPAL, principal);}
            if ( originNode != null) {
            carrier = carrier.where(ORIGIN_NODE, originNode);}
            carrier.run(runnable);
        } finally {
            MDC.remove(MDC_KEY);
            MDC.remove(MDC_PRINCIPAL);
            MDC.remove(MDC_ORIGIN_NODE);
        }
    }

    @SuppressWarnings("JBCT-RET-01") // Utility method for MDC population
    private static void putIfNotNull(String key, String value) {
        if ( value != null) {
        MDC.put(key, value);}
    }

    /// Capture the current context for propagation across async boundaries.
    ///
    /// @return a snapshot that can be used to restore context in another thread
    @SuppressWarnings("JBCT-RET-03") // Nullable fields required for ContextSnapshot — null means "no context"
    public static ContextSnapshot captureContext() {
        return new ContextSnapshot(currentRequestId().or((String) null),
                                   currentPrincipal().or((String) null),
                                   currentOriginNode().or((String) null),
                                   currentDepth(),
                                   isSampled(),
                                   currentVariant().or((String) null));
    }

    /// Generate a new request ID using KSUID.
    ///
    /// @return new KSUID-based request ID
    public static String generateRequestId() {
        return KSUID.ksuid().toString();
    }

    /// A snapshot of the invocation context that can be captured and restored
    /// across thread boundaries for async operations.
    ///
    /// @param requestId  the request ID (null if no context was active)
    /// @param principal  the principal identity (null if no context was active)
    /// @param originNode the origin node identifier (null if no context was active)
    /// @param depth      the invocation depth at capture time
    /// @param sampled    whether the request was sampled at capture time
    /// @param variant    the A/B test variant (null if not in an A/B test context)
    public record ContextSnapshot(String requestId,
                                  String principal,
                                  String originNode,
                                  int depth,
                                  boolean sampled,
                                  String variant) {
        /// Run a supplier with the captured context restored.
        ///
        /// @param supplier the supplier to execute
        /// @param <T>      the return type
        ///
        /// @return the result of the supplier
        public <T> T runWithCaptured(Supplier<T> supplier) {
            if ( requestId == null) {
            return runWithVariantIfPresent(supplier);}
            return runWithContext(requestId,
                                  principal,
                                  originNode,
                                  depth,
                                  sampled,
                                  () -> runWithVariantIfPresent(supplier));
        }

        /// Run a runnable with the captured context restored.
        ///
        /// @param runnable the runnable to execute
        @SuppressWarnings("JBCT-RET-01") // Delegates to Runnable-based API
        public// Delegates to Runnable-based API
        // Delegates to Runnable-based API
        // Delegates to Runnable-based API
        // Delegates to Runnable-based API
        // Delegates to Runnable-based API
        // Delegates to Runnable-based API
        // Delegates to Runnable-based API
        // Delegates to Runnable-based API
        // Delegates to Runnable-based API
        // Delegates to Runnable-based API
        // Delegates to Runnable-based API
        // Delegates to Runnable-based API
        // Delegates to Runnable-based API
        // Delegates to Runnable-based API
        // Delegates to Runnable-based API
        // Delegates to Runnable-based API
        // Delegates to Runnable-based API
        // Delegates to Runnable-based API
        // Delegates to Runnable-based API
        // Delegates to Runnable-based API
        // Delegates to Runnable-based API
        // Delegates to Runnable-based API
        // Delegates to Runnable-based API
        // Delegates to Runnable-based API
        // Delegates to Runnable-based API
        // Delegates to Runnable-based API
        void runWithCaptured(Runnable runnable) {
            if ( requestId == null) {
                runWithVariantIfPresent(runnable);
                return;
            }
            runWithContext(requestId, principal, originNode, depth, sampled, () -> runWithVariantIfPresent(runnable));
        }

        @SuppressWarnings("JBCT-RET-03") // Delegates to supplier — null safety is caller's responsibility
        private// Delegates to supplier — null safety is caller's responsibility
        // Delegates to supplier — null safety is caller's responsibility
        // Delegates to supplier — null safety is caller's responsibility
        // Delegates to supplier — null safety is caller's responsibility
        // Delegates to supplier — null safety is caller's responsibility
        // Delegates to supplier — null safety is caller's responsibility
        // Delegates to supplier — null safety is caller's responsibility
        // Delegates to supplier — null safety is caller's responsibility
        // Delegates to supplier — null safety is caller's responsibility
        // Delegates to supplier — null safety is caller's responsibility
        // Delegates to supplier — null safety is caller's responsibility
        // Delegates to supplier — null safety is caller's responsibility
        // Delegates to supplier — null safety is caller's responsibility
        // Delegates to supplier — null safety is caller's responsibility
        // Delegates to supplier — null safety is caller's responsibility
        // Delegates to supplier — null safety is caller's responsibility
        // Delegates to supplier — null safety is caller's responsibility
        // Delegates to supplier — null safety is caller's responsibility
        // Delegates to supplier — null safety is caller's responsibility
        // Delegates to supplier — null safety is caller's responsibility
        // Delegates to supplier — null safety is caller's responsibility
        // Delegates to supplier — null safety is caller's responsibility
        // Delegates to supplier — null safety is caller's responsibility
        // Delegates to supplier — null safety is caller's responsibility
        // Delegates to supplier — null safety is caller's responsibility
        // Delegates to supplier — null safety is caller's responsibility
        <T> T runWithVariantIfPresent(Supplier<T> supplier) {
            return variant != null
                   ? runWithVariant(variant, supplier)
                   : supplier.get();
        }

        @SuppressWarnings("JBCT-RET-01") // Void wrapper for variant scoping
        private// Void wrapper for variant scoping
        // Void wrapper for variant scoping
        // Void wrapper for variant scoping
        // Void wrapper for variant scoping
        // Void wrapper for variant scoping
        // Void wrapper for variant scoping
        // Void wrapper for variant scoping
        // Void wrapper for variant scoping
        // Void wrapper for variant scoping
        // Void wrapper for variant scoping
        // Void wrapper for variant scoping
        // Void wrapper for variant scoping
        // Void wrapper for variant scoping
        // Void wrapper for variant scoping
        // Void wrapper for variant scoping
        // Void wrapper for variant scoping
        // Void wrapper for variant scoping
        // Void wrapper for variant scoping
        // Void wrapper for variant scoping
        // Void wrapper for variant scoping
        // Void wrapper for variant scoping
        // Void wrapper for variant scoping
        // Void wrapper for variant scoping
        // Void wrapper for variant scoping
        // Void wrapper for variant scoping
        // Void wrapper for variant scoping
        void runWithVariantIfPresent(Runnable runnable) {
            if ( variant != null) {
            ScopedValue.where(AB_VARIANT, variant).run(runnable);} else
            {
            runnable.run();}
        }
    }
}
