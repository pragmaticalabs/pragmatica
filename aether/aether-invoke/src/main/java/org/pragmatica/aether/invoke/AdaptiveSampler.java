package org.pragmatica.aether.invoke;

import org.pragmatica.lang.utils.SharedScheduler;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Adaptive per-node sampler that adjusts sampling rate based on throughput.
/// At low throughput (below targetTracesPerSec), effectively 100% sampling.
/// At high throughput, auto-adjusts to maintain target traces/sec.
public final class AdaptiveSampler {
    private static final long RECALCULATION_INTERVAL_SEC = 5;

    private final int targetTracesPerSec;

    private final AtomicLong invocationCount = new AtomicLong();

    private volatile double effectiveRate = 1.0;

    private AdaptiveSampler(int targetTracesPerSec) {
        this.targetTracesPerSec = targetTracesPerSec;
        SharedScheduler.scheduleAtFixedRate(this::recalculate, timeSpan(RECALCULATION_INTERVAL_SEC).seconds());
    }

    public static AdaptiveSampler adaptiveSampler(int targetTracesPerSec) {
        return new AdaptiveSampler(targetTracesPerSec);
    }

    @SuppressWarnings("JBCT-RET-01") public void recordInvocation() {
        invocationCount.incrementAndGet();
    }

    public boolean shouldSample() {
        return ThreadLocalRandom.current().nextDouble() <effectiveRate;
    }

    public double effectiveRate() {
        return effectiveRate;
    }

    private void recalculate() {
        var count = invocationCount.getAndSet(0);
        var throughput = count / (double) RECALCULATION_INTERVAL_SEC;
        effectiveRate = throughput > 0
                       ? Math.min((double) targetTracesPerSec / throughput, 1.0)
                       : 1.0;
    }
}
