package org.pragmatica.aether.slice;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

/// A pre-parsed, reusable handle for invoking a specific method on a specific slice.
///
/// Created via {@link SliceInvokerFacade#methodHandle(String, String, Class, Class)}.
///
/// Using a MethodHandle avoids repeated parsing of artifact coordinates and method names,
/// significantly improving performance for repeated invocations.
///
/// @param <R> Response/return type (first, per pragmatica-lite convention)
/// @param <T> Request/parameter type (last, per pragmatica-lite convention)
public interface MethodHandle<R, T> {
    /// Invoke the method and wait for response.
    ///
    /// @param request Request object
    /// @return Promise resolving to the response
    Promise<R> invoke(T request);

    /// Invoke the method without waiting for response (fire-and-forget).
    ///
    /// @param request Request object
    /// @return Promise resolving when request is sent
    Promise<Unit> fireAndForget(T request);

    /// Get the target slice artifact coordinate (e.g., "org.example:my-slice:1.0.0").
    /// Useful for debugging and logging.
    ///
    /// @return Artifact coordinate string
    String artifactCoordinate();

    /// Get the target method name.
    /// Useful for debugging and logging.
    ///
    /// @return Method name
    MethodName methodName();

    /// Materialize this handle by verifying the target endpoint exists.
    ///
    /// Called during slice activation (ACTIVATING phase) to eagerly validate
    /// that all dependencies are resolvable. This ensures no technical failures
    /// occur after the slice reaches ACTIVE state.
    ///
    /// Default implementation returns success (for backwards compatibility).
    /// Implementations that support eager validation should override this.
    ///
    /// @return Success if endpoint exists, failure with cause if not
    default Result<Unit> materialize() {
        return Result.unitResult();
    }
}
