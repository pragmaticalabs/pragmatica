// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import org.pragmatica.lang.utils.SharedScheduler;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


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
