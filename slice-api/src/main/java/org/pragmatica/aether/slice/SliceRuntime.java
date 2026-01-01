package org.pragmatica.aether.slice;

import org.pragmatica.lang.Option;

import java.util.function.Function;

/**
 * Provides access to runtime services for slices.
 * <p>
 * This is a static holder for runtime services that slices may need.
 * The runtime sets these services before activating slices.
 * <p>
 * Usage in slice:
 * <pre>{@code
 * var invoker = SliceRuntime.sliceInvoker();
 * invoker.invoke(targetSlice, method, request);
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
     * @return the configured SliceInvoker
     * @throws IllegalStateException if SliceInvoker not configured
     */
    public static SliceInvokerFacade sliceInvoker() {
        var invoker = sliceInvoker;
        if (invoker == null) {
            throw new IllegalStateException("SliceInvoker not configured. "
                                            + "This typically means the slice is being used outside of the Aether runtime.");
        }
        return invoker;
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

    /**
     * Facade interface for slice invocation.
     * This is a simplified interface in slice-api that the full SliceInvoker implements.
     * <p>
     * Slices use this to call other slices without depending on the node module.
     *
     * @param <T> the result type
     */
    @FunctionalInterface
    public interface SliceInvokerFacade {
        /**
         * Invoke a method on another slice and wait for the response.
         *
         * @param sliceArtifact  Target slice artifact coordinate (e.g., "org.example:my-slice:1.0.0")
         * @param methodName     Name of the method to invoke
         * @param request        Request object
         * @param responseType   Expected response type
         * @param <R>            Response type
         * @return Promise resolving to the response
         */
        <R> org.pragmatica.lang.Promise<R> invokeAndWait(String sliceArtifact,
                                                         String methodName,
                                                         Object request,
                                                         Class<R> responseType);
    }
}
