package org.pragmatica.aether.slice;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Unit.unit;


/// Provides access to runtime services for slices.
///
/// This is a static holder for runtime services that slices may need.
/// The runtime sets these services before activating slices.
///
/// Usage in slice:
/// ```{@code
/// // Create handle once (e.g., in factory or field initialization)
/// var handle = SliceRuntime.getSliceInvoker()
///     .flatMap(invoker -> invoker.methodHandle(
///         "org.example:target-slice:1.0.0",
///         "processRequest",
///         TypeToken.of(Request.class),
///         TypeToken.of(Response.class)))
///     .unwrap();
///
/// // Use handle for invocations
/// handle.invoke(request)
///     .onSuccess(response -> ...)
///     .onFailure(cause -> ...);
/// }```
///
/// This approach allows slices to remain records (immutable) while still
/// accessing runtime services. The trade-off is global state, but this is
/// acceptable for ambient runtime services that are set once at startup.
public sealed interface SliceRuntime {
    record unused() implements SliceRuntime {
        static Result<unused > unused() {
            return success(new unused());
        }
    }

    static Result<SliceInvokerFacade> getSliceInvoker() {
        return option(SliceRuntimeHolder.INVOKER_REF.get()).toResult(SliceRuntimeError.InvokerNotConfigured.INSTANCE);
    }

    static Option<SliceInvokerFacade> trySliceInvoker() {
        return option(SliceRuntimeHolder.INVOKER_REF.get());
    }

    static Result<Unit> setSliceInvoker(SliceInvokerFacade invoker) {
        SliceRuntimeHolder.INVOKER_REF.set(invoker);
        return success(unit());
    }

    static Result<Unit> clear() {
        SliceRuntimeHolder.INVOKER_REF.set(null);
        return success(unit());
    }
}

/// Internal mutable state holder for SliceRuntime.
sealed interface SliceRuntimeHolder {
    record unused() implements SliceRuntimeHolder{}

    java.util.concurrent.atomic.AtomicReference<SliceInvokerFacade> INVOKER_REF = new java.util.concurrent.atomic.AtomicReference<>();
}
