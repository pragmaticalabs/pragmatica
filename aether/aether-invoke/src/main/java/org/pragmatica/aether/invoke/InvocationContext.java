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

    public static Option<String> currentRequestId() {
        return REQUEST_ID.isBound()
              ? Option.option(REQUEST_ID.get())
              : Option.empty();
    }

    public static Option<String> currentPrincipal() {
        return PRINCIPAL.isBound()
              ? Option.option(PRINCIPAL.get())
              : Option.empty();
    }

    public static Option<String> currentOriginNode() {
        return ORIGIN_NODE.isBound()
              ? Option.option(ORIGIN_NODE.get())
              : Option.empty();
    }

    @SuppressWarnings("JBCT-RET-03") public static String getOrGenerateRequestId() {
        return REQUEST_ID.isBound() && REQUEST_ID.get() != null
              ? REQUEST_ID.get()
              : generateRequestId();
    }

    public static int currentDepth() {
        return DEPTH.isBound()
              ? DEPTH.get()
              : 0;
    }

    public static boolean isSampled() {
        return SAMPLED.isBound() && SAMPLED.get();
    }

    public static Option<String> currentVariant() {
        return AB_VARIANT.isBound()
              ? Option.option(AB_VARIANT.get())
              : Option.empty();
    }

    public static <T> T runWithVariant(String variant, Supplier<T> supplier) {
        return ScopedValue.where(AB_VARIANT, variant).call(supplier::get);
    }

    public static <T> T runWithRequestId(String requestId, Supplier<T> supplier) {
        MDC.put(MDC_KEY, requestId);
        try {
            return ScopedValue.where(REQUEST_ID, requestId).call(supplier::get);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    @SuppressWarnings("JBCT-RET-01") public static void runWithRequestId(String requestId, Runnable runnable) {
        MDC.put(MDC_KEY, requestId);
        try {
            ScopedValue.where(REQUEST_ID, requestId).run(runnable);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

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
            if (principal != null) {carrier = carrier.where(PRINCIPAL, principal);}
            if (originNode != null) {carrier = carrier.where(ORIGIN_NODE, originNode);}
            return carrier.call(supplier::get);
        } finally {
            MDC.remove(MDC_KEY);
            MDC.remove(MDC_PRINCIPAL);
            MDC.remove(MDC_ORIGIN_NODE);
        }
    }

    @SuppressWarnings("JBCT-RET-01") public static void runWithContext(String requestId,
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
            if (principal != null) {carrier = carrier.where(PRINCIPAL, principal);}
            if (originNode != null) {carrier = carrier.where(ORIGIN_NODE, originNode);}
            carrier.run(runnable);
        } finally {
            MDC.remove(MDC_KEY);
            MDC.remove(MDC_PRINCIPAL);
            MDC.remove(MDC_ORIGIN_NODE);
        }
    }

    @SuppressWarnings("JBCT-RET-01") private static void putIfNotNull(String key, String value) {
        if (value != null) {MDC.put(key, value);}
    }

    @SuppressWarnings("JBCT-RET-03") public static ContextSnapshot captureContext() {
        return new ContextSnapshot(currentRequestId().or((String) null),
                                   currentPrincipal().or((String) null),
                                   currentOriginNode().or((String) null),
                                   currentDepth(),
                                   isSampled(),
                                   currentVariant().or((String) null));
    }

    public static String generateRequestId() {
        return KSUID.ksuid().toString();
    }

    public record ContextSnapshot(String requestId,
                                  String principal,
                                  String originNode,
                                  int depth,
                                  boolean sampled,
                                  String variant) {
        public <T> T runWithCaptured(Supplier<T> supplier) {
            if (requestId == null) {return runWithVariantIfPresent(supplier);}
            return runWithContext(requestId,
                                  principal,
                                  originNode,
                                  depth,
                                  sampled,
                                  () -> runWithVariantIfPresent(supplier));
        }

        @SuppressWarnings("JBCT-RET-01") public void runWithCaptured(Runnable runnable) {
            if (requestId == null) {
                runWithVariantIfPresent(runnable);
                return;
            }
            runWithContext(requestId, principal, originNode, depth, sampled, () -> runWithVariantIfPresent(runnable));
        }

        @SuppressWarnings("JBCT-RET-03") private <T> T runWithVariantIfPresent(Supplier<T> supplier) {
            return variant != null
                  ? runWithVariant(variant, supplier)
                  : supplier.get();
        }

        @SuppressWarnings("JBCT-RET-01") private void runWithVariantIfPresent(Runnable runnable) {
            if (variant != null) {ScopedValue.where(AB_VARIANT, variant).run(runnable);} else {runnable.run();}
        }
    }
}
