package org.pragmatica.aether.slice;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;

/// Interceptor for slice method invocations.
///
/// Unlike {@link Aspect}, which wraps entire slice instances,
/// MethodInterceptor operates at the individual method level,
/// allowing fine-grained control over method execution (e.g.,
/// adding metrics, tracing, or access control per method).
///
/// Example usage:
/// ```{@code
/// MethodInterceptor metricsInterceptor = new MethodInterceptor() {
///     @Override
///     public <R, T> Fn1<Promise<R>, T> intercept(Fn1<Promise<R>, T> method) {
///         return request -> {
///             var start = System.nanoTime();
///             return method.apply(request)
///                          .onSuccess(_ -> recordLatency(System.nanoTime() - start));
///         };
///     }
/// };
/// }```
@FunctionalInterface
public interface MethodInterceptor {
    /// Intercept a method invocation, wrapping it with additional behavior.
    ///
    /// @param method The original method to intercept
    /// @param <R>    Response type
    /// @param <T>    Request type
    /// @return Wrapped method with interceptor behavior applied
    <R, T> Fn1<Promise<R>, T> intercept(Fn1<Promise<R>, T> method);
}
