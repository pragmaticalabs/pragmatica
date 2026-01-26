package org.pragmatica.aether.slice;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Context for loading a slice that buffers method handles for eager materialization.
 * <p>
 * During slice loading, this context wraps the SliceInvokerFacade and buffers all
 * MethodHandle instances created via {@link #methodHandle}. When activation begins,
 * {@link #materializeAll()} is called to verify all dependencies exist before
 * the slice's start() method is called.
 * <p>
 * This implements Part 2 of the Slice Lifecycle design: Eager Dependency Validation.
 * The goal is to ensure no technical failures occur after a slice reaches ACTIVE state.
 * <p>
 * Thread-safe: Uses CopyOnWriteArrayList for handle buffering and AtomicBoolean for state.
 */
public final class SliceLoadingContext implements SliceInvokerFacade {
    private final SliceInvokerFacade delegate;
    private final List<MethodHandle<?, ?>> bufferedHandles = new CopyOnWriteArrayList<>();
    private final AtomicBoolean materialized = new AtomicBoolean(false);

    private SliceLoadingContext(SliceInvokerFacade delegate) {
        this.delegate = delegate;
    }

    /**
     * Create a new SliceLoadingContext wrapping the given delegate.
     *
     * @param delegate The underlying SliceInvokerFacade to delegate handle creation to
     * @return A new SliceLoadingContext
     */
    public static SliceLoadingContext sliceLoadingContext(SliceInvokerFacade delegate) {
        return new SliceLoadingContext(delegate);
    }

    @Override
    public <R, T> Result<MethodHandle<R, T>> methodHandle(String sliceArtifact,
                                                          String methodName,
                                                          TypeToken<T> requestType,
                                                          TypeToken<R> responseType) {
        return delegate.methodHandle(sliceArtifact, methodName, requestType, responseType)
                       .onSuccess(this::bufferHandleIfNotMaterialized);
    }

    private <R, T> void bufferHandleIfNotMaterialized(MethodHandle<R, T> handle) {
        if (!materialized.get()) {
            bufferedHandles.add(handle);
        }
    }

    /**
     * Materialize all buffered handles by verifying their target endpoints exist.
     * <p>
     * This method should be called during slice activation, before calling start().
     * If any handle fails to materialize, the activation should fail.
     *
     * @return Success if all handles materialized, or the first failure cause
     */
    public Result<Unit> materializeAll() {
        for (var handle : bufferedHandles) {
            var result = handle.materialize();
            if (result.isFailure()) {
                return result;
            }
        }
        return Result.unitResult();
    }

    /**
     * Mark this context as materialized, stopping further handle buffering.
     * <p>
     * After this is called, new handles created via {@link #methodHandle} will
     * not be buffered. This is called after successful materialization.
     */
    public void markMaterialized() {
        materialized.set(true);
    }

    /**
     * Check if this context has been materialized.
     *
     * @return true if materialized, false otherwise
     */
    public boolean isMaterialized() {
        return materialized.get();
    }

    /**
     * Get the number of buffered handles (for testing/debugging).
     *
     * @return Number of buffered handles
     */
    public int bufferedHandleCount() {
        return bufferedHandles.size();
    }

    /**
     * Get the underlying delegate (for testing/debugging).
     *
     * @return The delegate SliceInvokerFacade
     */
    public SliceInvokerFacade delegate() {
        return delegate;
    }
}
