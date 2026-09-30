package org.pragmatica.cluster.state.kvstore;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.pragmatica.lang.Contract;


/// A WARN for fence refusals that cannot flood (#1529). After a failover every in-flight write of the deposed
/// writer is a refusal on every replica, which is normal, so each refusal must still be COUNTED (by the caller)
/// but not each one logged. The first refusal of each distinct cause (for example an epoch pair) is logged in
/// full; later ones are only counted here and reported as one summary line at most once per interval.
public final class ThrottledWarning {
    /// Distinct causes remembered before the set is cleared, so a long run cannot grow it without bound.
    private static final int MAX_REMEMBERED = 1024;

    private final Consumer<String> sink;
    private final LongSupplier nanoClock;
    private final long intervalNanos;
    private final String subject;
    private final Set<Object> seen = ConcurrentHashMap.newKeySet();
    private final AtomicLong suppressed = new AtomicLong();
    private final AtomicLong lastSummaryAt;
    private final AtomicLong emitted = new AtomicLong();

    private ThrottledWarning(Consumer<String> sink, LongSupplier nanoClock, long intervalNanos, String subject) {
        this.sink = sink;
        this.nanoClock = nanoClock;
        this.intervalNanos = intervalNanos;
        this.subject = subject;
        this.lastSummaryAt = new AtomicLong(nanoClock.getAsLong());
    }

    public static ThrottledWarning throttledWarning(Consumer<String> sink,
                                                    LongSupplier nanoClock,
                                                    long intervalNanos,
                                                    String subject) {
        return new ThrottledWarning(sink, nanoClock, intervalNanos, subject);
    }

    /// Logs `message` if `cause` has not been seen; otherwise counts it and, once per interval, logs how many
    /// were suppressed since the last line.
    @Contract
    public void report(Object cause, Supplier<String> message) {
        if (seen.size() >= MAX_REMEMBERED) {
            seen.clear();
        }

        if (seen.add(cause)) {
            emit(message.get());

            return;
        }

        suppressed.incrementAndGet();
        var now = nanoClock.getAsLong();
        var last = lastSummaryAt.get();

        if (now - last >= intervalNanos && lastSummaryAt.compareAndSet(last, now)) {
            emit(suppressed.getAndSet(0)
                + " more " + subject
                + " since the last report (repeats of causes already logged)");
        }
    }

    /// Lines actually written, for tests and diagnostics.
    public long emitted() {
        return emitted.get();
    }

    private void emit(String line) {
        emitted.incrementAndGet();
        sink.accept(line);
    }
}
