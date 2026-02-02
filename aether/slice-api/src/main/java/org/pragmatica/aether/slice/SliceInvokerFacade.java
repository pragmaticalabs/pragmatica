package org.pragmatica.aether.slice;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;

/**
 * Facade interface for slice invocation.
 * This is a simplified interface in slice-api that the full SliceInvoker implements.
 * <p>
 * Slices use this to call other slices without depending on the node module.
 */
public interface SliceInvokerFacade {
    /**
     * Create a reusable method handle for invoking a specific slice method.
     * <p>
     * The handle pre-parses artifact coordinates and method names, avoiding
     * repeated parsing overhead on each invocation. Use this for performance-critical
     * code paths with repeated invocations.
     * <p>
     * For generic types like {@code List<User>}, use anonymous TypeToken subclass:
     * <pre>{@code
     * invoker.methodHandle("org.example:users:1.0.0", "listAll",
     *     TypeToken.of(ListRequest.class),
     *     new TypeToken<List<User>>() {})
     * }</pre>
     *
     * @param sliceArtifact  Target slice artifact coordinate (e.g., "org.example:my-slice:1.0.0")
     * @param methodName     Name of the method to invoke
     * @param requestType    Request type token
     * @param responseType   Expected response type token
     * @param <R>            Response type (first, per pragmatica-lite convention)
     * @param <T>            Request type (last, per pragmatica-lite convention)
     * @return Result containing the method handle, or failure if artifact/method parsing fails
     */
    <R, T> Result<MethodHandle<R, T>> methodHandle(String sliceArtifact,
                                                   String methodName,
                                                   TypeToken<T> requestType,
                                                   TypeToken<R> responseType);
}
