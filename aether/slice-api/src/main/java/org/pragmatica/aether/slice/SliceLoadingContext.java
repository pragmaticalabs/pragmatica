package org.pragmatica.aether.slice;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.utils.Causes.cause;

/// Context for loading a slice that buffers method handles for eager materialization.
///
/// During slice loading, this context wraps the SliceCreationContext and buffers all
/// MethodHandle instances created via {@link #invoker()}. When activation begins,
/// {@link #materializeAll()} is called to verify all dependencies exist before
/// the slice's start() method is called.
///
/// This implements Part 2 of the Slice Lifecycle design: Eager Dependency Validation.
/// The goal is to ensure no technical failures occur after a slice reaches ACTIVE state.
///
/// Thread-safe: Uses CopyOnWriteArrayList for handle buffering and AtomicBoolean for state.
public final class SliceLoadingContext implements SliceCreationContext {
    private final SliceCreationContext delegate;
    private final BufferingInvokerFacade bufferingInvoker;
    private final AtomicBoolean materialized = new AtomicBoolean(false);

    private SliceLoadingContext(SliceCreationContext delegate) {
        this.delegate = delegate;
        this.bufferingInvoker = new BufferingInvokerFacade(delegate.invoker());
    }

    /// Create a new SliceLoadingContext wrapping the given delegate.
    ///
    /// @param delegate The underlying SliceCreationContext to delegate to
    /// @return A new SliceLoadingContext
    public static SliceLoadingContext sliceLoadingContext(SliceCreationContext delegate) {
        return new SliceLoadingContext(delegate);
    }

    /// Create a new SliceLoadingContext from an invoker facade and resource provider.
    ///
    /// @param invokerFacade   The slice invoker facade for cross-slice calls
    /// @param resourceFacade  The resource provider facade for resource provisioning
    /// @return A new SliceLoadingContext
    public static SliceLoadingContext sliceLoadingContext(SliceInvokerFacade invokerFacade,
                                                          ResourceProviderFacade resourceFacade) {
        return new SliceLoadingContext(SliceCreationContext.sliceCreationContext(invokerFacade, resourceFacade));
    }

    /// Create a new SliceLoadingContext from an invoker facade only (backward compatibility).
    /// Uses a no-op resource provider that fails for any resource request.
    ///
    /// @param invokerFacade The slice invoker facade for cross-slice calls
    /// @return A new SliceLoadingContext
    public static SliceLoadingContext sliceLoadingContext(SliceInvokerFacade invokerFacade) {
        return new SliceLoadingContext(SliceCreationContext.sliceCreationContext(invokerFacade, noOpResourceProvider()));
    }

    private static ResourceProviderFacade noOpResourceProvider() {
        return new NoOpResourceProvider();
    }

    @Override
    public SliceInvokerFacade invoker() {
        return bufferingInvoker;
    }

    @Override
    public ResourceProviderFacade resources() {
        return delegate.resources();
    }

    /// Materialize all buffered handles by verifying their target endpoints exist.
    ///
    /// This method should be called during slice activation, before calling start().
    /// If any handle fails to materialize, the activation should fail.
    ///
    /// @return Success if all handles materialized, or the first failure cause
    public Result<Unit> materializeAll() {
        for (var handle : bufferingInvoker.bufferedHandles()) {
            var result = handle.materialize();
            if (result.isFailure()) {
                return result;
            }
        }
        return Result.unitResult();
    }

    /// Mark this context as materialized, stopping further handle buffering.
    ///
    /// After this is called, new handles created via {@link #invoker()} will
    /// not be buffered. This is called after successful materialization.
    ///
    /// @return Result<Unit> indicating success
    public Result<Unit> markMaterialized() {
        materialized.set(true);
        bufferingInvoker.stopBuffering();
        return success(unit());
    }

    /// Check if this context has been materialized.
    ///
    /// @return true if materialized, false otherwise
    public boolean isMaterialized() {
        return materialized.get();
    }

    /// Get the number of buffered handles (for testing/debugging).
    ///
    /// @return Number of buffered handles
    public int bufferedHandleCount() {
        return bufferingInvoker.bufferedHandles()
                               .size();
    }

    /// Get the underlying delegate (for testing/debugging).
    ///
    /// @return The delegate SliceCreationContext
    public SliceCreationContext delegate() {
        return delegate;
    }

    /// No-op resource provider that fails for any resource request.
    private static final class NoOpResourceProvider implements ResourceProviderFacade {
        private static final Cause NOT_CONFIGURED = cause("Resource provisioning not configured");

        @Override
        public <T> Promise<T> provide(Class<T> resourceType, String configSection) {
            return NOT_CONFIGURED.promise();
        }

        @Override
        public <T> Promise<T> provide(Class<T> resourceType, String configSection, ProvisioningContext context) {
            return NOT_CONFIGURED.promise();
        }
    }

    /// Internal invoker facade that buffers method handles for later materialization.
    private static final class BufferingInvokerFacade implements SliceInvokerFacade {
        private final SliceInvokerFacade delegate;
        private final List<MethodHandle<?, ?>> bufferedHandles = new CopyOnWriteArrayList<>();
        private final AtomicBoolean buffering = new AtomicBoolean(true);

        BufferingInvokerFacade(SliceInvokerFacade delegate) {
            this.delegate = delegate;
        }

        @Override
        public <R, T> Result<MethodHandle<R, T>> methodHandle(String sliceArtifact,
                                                              String methodName,
                                                              TypeToken<T> requestType,
                                                              TypeToken<R> responseType) {
            return delegate.methodHandle(sliceArtifact, methodName, requestType, responseType)
                           .onSuccess(this::bufferHandleIfActive);
        }

        private <R, T> void bufferHandleIfActive(MethodHandle<R, T> handle) {
            if (buffering.get()) {
                bufferedHandles.add(handle);
            }
        }

        List<MethodHandle<?, ?>> bufferedHandles() {
            return bufferedHandles;
        }

        void stopBuffering() {
            buffering.set(false);
        }
    }
}
