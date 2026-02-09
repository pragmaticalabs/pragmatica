package org.pragmatica.aether.slice;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;

import java.util.concurrent.atomic.AtomicReference;

/// A deferred SliceInvokerFacade that can be wired after construction.
///
/// Use this when SliceStore needs to be created before SliceInvoker is available.
/// The actual invoker is set later via {@link #setDelegate(SliceInvokerFacade)}.
///
/// Invocations before delegate is set will fail with a clear error message.
public final class DeferredSliceInvokerFacade implements SliceInvokerFacade {
    private final AtomicReference<SliceInvokerFacade> delegate = new AtomicReference<>();

    private DeferredSliceInvokerFacade() {}

    public static DeferredSliceInvokerFacade deferredSliceInvokerFacade() {
        return new DeferredSliceInvokerFacade();
    }

    private static final org.pragmatica.lang.Cause DELEGATE_ALREADY_SET =
        Causes.cause("Delegate already set");

    /// Set the actual SliceInvokerFacade delegate.
    /// Must be called before any invocations occur.
    ///
    /// @return Result.success if set, or failure if already set
    public Result<Unit> setDelegate(SliceInvokerFacade invoker) {
        return delegate.compareAndSet(null, invoker)
               ? Result.success(Unit.unit())
               : DELEGATE_ALREADY_SET.result();
    }

    private static final org.pragmatica.lang.Cause NOT_INITIALIZED =
        Causes.cause("SliceInvokerFacade not initialized");

    @Override
    public <R, T> Result<MethodHandle<R, T>> methodHandle(String sliceArtifact,
                                                          String methodName,
                                                          TypeToken<T> requestType,
                                                          TypeToken<R> responseType) {
        return Option.option(delegate.get())
                     .toResult(NOT_INITIALIZED)
                     .flatMap(d -> d.methodHandle(sliceArtifact, methodName, requestType, responseType));
    }
}
