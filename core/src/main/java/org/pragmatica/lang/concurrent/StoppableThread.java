package org.pragmatica.lang.concurrent;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/// Thread-safe stoppable thread holder.
/// Replaces the nullable AtomicReference + getAndSet(null) + interrupt pattern.
public final class StoppableThread {
    private static final VarHandle THREAD;

    static {
        try {
            THREAD = MethodHandles.lookup()
                                  .findVarHandle(StoppableThread.class, "thread", Thread.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private volatile Thread thread;

    private StoppableThread() {}

    public static StoppableThread stoppableThread() {
        return new StoppableThread();
    }

    /// Set the thread.
    public void set(Thread t) {
        THREAD.setVolatile(this, t);
    }

    /// Stop the thread: interrupt and clear.
    public void stop() {
        var t = (Thread) THREAD.getAndSet(this, null);
        if (t != null) {
            t.interrupt();
        }
    }

    /// Check if a thread is currently set.
    public boolean isRunning() {
        return THREAD.getVolatile(this) != null;
    }
}
