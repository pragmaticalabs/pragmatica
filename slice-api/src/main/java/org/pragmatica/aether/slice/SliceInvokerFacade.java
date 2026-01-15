package org.pragmatica.aether.slice;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

/**
 * Facade interface for slice invocation.
 * This is a simplified interface in slice-api that the full SliceInvoker implements.
 * <p>
 * Slices use this to call other slices without depending on the node module.
 */
public interface SliceInvokerFacade {
    /**
     * Invoke a method on another slice and wait for the response.
     * <p>
     * For repeated calls to the same slice/method, prefer using {@link #methodHandle}
     * to avoid repeated parsing overhead.
     *
     * @param sliceArtifact  Target slice artifact coordinate (e.g., "org.example:my-slice:1.0.0")
     * @param methodName     Name of the method to invoke
     * @param request        Request object
     * @param responseType   Expected response type
     * @param <R>            Response type
     * @return Promise resolving to the response
     * @deprecated Use {@link #methodHandle(String, String, Class, Class)} for better performance.
     *             This method parses artifact and method on every call.
     */
    @Deprecated
    <R> Promise<R> invoke(String sliceArtifact,
                          String methodName,
                          Object request,
                          Class<R> responseType);

    /**
     * Create a reusable method handle for invoking a specific slice method.
     * <p>
     * The handle pre-parses artifact coordinates and method names, avoiding
     * repeated parsing overhead on each invocation. Use this for performance-critical
     * code paths with repeated invocations.
     *
     * @param sliceArtifact  Target slice artifact coordinate (e.g., "org.example:my-slice:1.0.0")
     * @param methodName     Name of the method to invoke
     * @param requestType    Request type class
     * @param responseType   Expected response type class
     * @param <R>            Response type (first, per pragmatica-lite convention)
     * @param <T>            Request type (last, per pragmatica-lite convention)
     * @return Result containing the method handle, or failure if artifact/method parsing fails
     */
    <R, T> Result<MethodHandle<R, T>> methodHandle(String sliceArtifact,
                                                   String methodName,
                                                   Class<T> requestType,
                                                   Class<R> responseType);
}
