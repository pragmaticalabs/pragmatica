package org.pragmatica.lang.concurrent;

import org.pragmatica.lang.Contract;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.ScheduledFuture;

/// Thread-safe cancellable scheduled task holder.
/// Replaces the nullable AtomicReference + getAndSet(null) pattern for ScheduledFuture fields.
public final class CancellableTask {
    private static final VarHandle FUTURE = lookupFutureHandle();

    @Contract
    private static VarHandle lookupFutureHandle() {
        try {
            return MethodHandles.lookup()
                                .findVarHandle(CancellableTask.class, "future", Object.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private volatile Object future;

    private CancellableTask(ScheduledFuture<?> future) {
        this.future = future;
    }

    public static CancellableTask cancellableTask(ScheduledFuture<?> future) {
        return new CancellableTask(future);
    }

    private CancellableTask() {}

    public static CancellableTask cancellableTask() {
        return new CancellableTask();
    }

    /// Set a new scheduled future, cancelling any existing one.
    @Contract
    public void set(ScheduledFuture<?> newFuture) {
        var prev = (ScheduledFuture<?>) FUTURE.getAndSet(this, newFuture);
        cancelIfPresent(prev);
    }

    /// Cancel the current task and clear.
    @Contract
    public void cancel() {
        var prev = (ScheduledFuture<?>) FUTURE.getAndSet(this, null);
        cancelIfPresent(prev);
    }

    /// Check if a task is currently scheduled.
    public boolean isScheduled() {
        return FUTURE.getVolatile(this) != null;
    }

    private static void cancelIfPresent(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }
}
