package org.pragmatica.aether.slice;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;

import java.util.concurrent.atomic.AtomicReference;

/**
 * A deferred SliceInvokerFacade that can be wired after construction.
 * <p>
 * Use this when SliceStore needs to be created before SliceInvoker is available.
 * The actual invoker is set later via {@link #setDelegate(SliceInvokerFacade)}.
 * <p>
 * Invocations before delegate is set will fail with a clear error message.
 */
public final class DeferredSliceInvokerFacade implements SliceInvokerFacade {
    private final AtomicReference<SliceInvokerFacade> delegate = new AtomicReference<>();

    private DeferredSliceInvokerFacade() {}

    public static DeferredSliceInvokerFacade deferredSliceInvokerFacade() {
        return new DeferredSliceInvokerFacade();
    }

    /**
     * Set the actual SliceInvokerFacade delegate.
     * Must be called before any invocations occur.
     */
    public void setDelegate(SliceInvokerFacade invoker) {
        if (!delegate.compareAndSet(null, invoker)) {
            throw new IllegalStateException("Delegate already set");
        }
    }

    @Override
    public <R, T> Result<MethodHandle<R, T>> methodHandle(String sliceArtifact,
                                                          String methodName,
                                                          TypeToken<T> requestType,
                                                          TypeToken<R> responseType) {
        var d = delegate.get();
        if (d == null) {
            return Causes.cause("SliceInvokerFacade not initialized")
                         .result();
        }
        return d.methodHandle(sliceArtifact, methodName, requestType, responseType);
    }
}
