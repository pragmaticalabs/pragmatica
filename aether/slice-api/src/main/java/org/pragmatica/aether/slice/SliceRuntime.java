package org.pragmatica.aether.slice;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

/**
 * Provides access to runtime services for slices.
 * <p>
 * This is a static holder for runtime services that slices may need.
 * The runtime sets these services before activating slices.
 * <p>
 * Usage in slice:
 * <pre>{@code
 * // Create handle once (e.g., in factory or field initialization)
 * var handle = SliceRuntime.getSliceInvoker()
 *     .flatMap(invoker -> invoker.methodHandle(
 *         "org.example:target-slice:1.0.0",
 *         "processRequest",
 *         TypeToken.of(Request.class),
 *         TypeToken.of(Response.class)))
 *     .unwrap();
 *
 * // Use handle for invocations
 * handle.invoke(request)
 *     .onSuccess(response -> ...)
 *     .onFailure(cause -> ...);
 * }</pre>
 * <p>
 * This approach allows slices to remain records (immutable) while still
 * accessing runtime services. The trade-off is global state, but this is
 * acceptable for ambient runtime services that are set once at startup.
 */
public final class SliceRuntime {
    private static volatile SliceInvokerFacade sliceInvoker;

    private SliceRuntime() {}

    /**
     * Get the SliceInvoker for inter-slice communication.
     *
     * @return Result containing the SliceInvoker, or failure if not configured
     */
    public static Result<SliceInvokerFacade> getSliceInvoker() {
        return Option.option(sliceInvoker)
                     .toResult(SliceRuntimeError.InvokerNotConfigured.INSTANCE);
    }

    /**
     * Get the SliceInvoker if configured.
     *
     * @return Option containing the SliceInvoker, or empty if not configured
     */
    public static Option<SliceInvokerFacade> trySliceInvoker() {
        return Option.option(sliceInvoker);
    }

    /**
     * Configure the SliceInvoker. Called by the runtime during startup.
     *
     * @param invoker the SliceInvoker to use
     */
    public static void setSliceInvoker(SliceInvokerFacade invoker) {
        sliceInvoker = invoker;
    }

    /**
     * Clear all runtime services. Called during shutdown.
     */
    public static void clear() {
        sliceInvoker = null;
    }
}
