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
    Promise<R> invoke(T request);
    Promise<Unit> fireAndForget(T request);
    String artifactCoordinate();
    MethodName methodName();

    default Result<Unit> materialize() {
        return Result.unitResult();
    }
}
