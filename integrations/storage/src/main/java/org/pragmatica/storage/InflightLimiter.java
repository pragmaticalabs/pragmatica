package org.pragmatica.storage;

import java.util.ArrayDeque;
import java.util.Queue;

import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.Option.option;


/// At most `maxInflight` operations running at once, without blocking a thread: an operation over the
/// limit waits in FIFO order for a permit, and a finished operation hands its permit to the next waiter.
/// A permit is released when the operation's promise resolves, either way, so a bounded operation (one
/// with a timeout) always returns its permit.
final class InflightLimiter {
    private final int maxInflight;
    private final Queue<Promise<Unit>> waiters = new ArrayDeque<>();
    private int inflight;

    private InflightLimiter(int maxInflight) {
        this.maxInflight = maxInflight;
    }

    static InflightLimiter inflightLimiter(int maxInflight) {
        return new InflightLimiter(maxInflight);
    }

    /// Runs `operation` once a permit is available and releases the permit when its promise resolves.
    <T> Promise<T> run(Fn0<Promise<T>> operation) {
        return acquire().flatMap(_ -> operation.apply())
                      .onResultRun(this::release);
    }

    /// Operations currently holding a permit (tests and diagnostics).
    synchronized int inflight() {
        return inflight;
    }

    private synchronized Promise<Unit> acquire() {
        return inflight < maxInflight
               ? Promise.success(granted())
               : queued();
    }

    private Unit granted() {
        inflight++;

        return unit();
    }

    private Promise<Unit> queued() {
        var waiter = Promise.<Unit> promise();

        waiters.add(waiter);

        return waiter;
    }

    /// The permit passes straight to the oldest waiter, so `inflight` does not change; it drops only when
    /// nobody waits. The waiter is resolved outside the monitor: its continuation may run inline.
    private void release() {
        nextWaiter().onPresent(waiter -> waiter.succeed(unit()));
    }

    private synchronized Option<Promise<Unit>> nextWaiter() {
        var next = option(waiters.poll());

        next.onEmpty(() -> inflight--);

        return next;
    }
}
