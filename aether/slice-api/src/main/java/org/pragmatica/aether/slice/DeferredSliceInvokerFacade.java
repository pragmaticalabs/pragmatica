package org.pragmatica.aether.slice;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;

import java.util.concurrent.atomic.AtomicReference;

import static org.pragmatica.lang.Option.option;

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

    private static final Cause DELEGATE_ALREADY_SET = Causes.cause("Delegate already set");
    private static final Cause NOT_INITIALIZED = Causes.cause("SliceInvokerFacade not initialized");

    /// Set the actual SliceInvokerFacade delegate.
    /// Must be called before any invocations occur.
    ///
    /// @return Result.success if set, or failure if already set
    public Result<Unit> setDelegate(SliceInvokerFacade invoker) {
        return option(delegate.get()).fold(() -> storeDelegate(invoker), _ -> DELEGATE_ALREADY_SET.result());
    }

    private Result<Unit> storeDelegate(SliceInvokerFacade invoker) {
        delegate.set(invoker);
        return Result.unitResult();
    }

    @Override
    public <R, T> Result<MethodHandle<R, T>> methodHandle(String sliceArtifact,
                                                          String methodName,
                                                          TypeToken<T> requestType,
                                                          TypeToken<R> responseType) {
        return option(delegate.get()).toResult(NOT_INITIALIZED)
                     .flatMap(d -> d.methodHandle(sliceArtifact, methodName, requestType, responseType));
    }
}
