// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.forward;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.io.TimeSpan;


/// The hold a caller keeps after a forward read the transport refused ([StreamForwardError.ForwardNotSent], #2574), per key
/// (CTO ruling, know 89e17399a9). A refused read fails at once, where it used to wait out its 2 s read timeout, so a caller
/// that re-reads on its own trigger (a gap trigger per received batch, a 50 ms consumer poll, a gate run per owner action)
/// would re-send on the congested FORWARD lane at its trigger's rate. It holds instead:
///
/// - the first refusal holds [#FIRST_HOLD] (250 ms), each consecutive one doubles it, capped at [#MAX_HOLD] (4 s). The cap
///   keeps a held key's wait after the lane recovers under the 5 s CF2 ack timeout, even with jitter: at most
///   `4 s x 1.2 = 4.8 s` ([#LONGEST_HOLD]);
/// - each hold is jittered by +-20% ([#JITTER]), so the keys of one node do not re-send in lockstep against a recovering lane;
/// - a read that was sent ([#sent], the caller saw no refusal) resets its key to the first hold.
///
/// Under a lane that keeps refusing a key sends at about 0, 0.25, 0.75, 1.75, 3.75 s and then every 4 s: a first-10 s burst
/// slightly above the timeout-paced rate (one send per 2 s) and about half of it sustained.
public final class RefusalBackoff<K> {
    public static final TimeSpan FIRST_HOLD = TimeSpan.timeSpan(250).millis();
    public static final TimeSpan MAX_HOLD = TimeSpan.timeSpan(4).seconds();
    public static final double JITTER = 0.2;

    /// The longest hold any key can be given: the cap with the full upward jitter.
    public static final TimeSpan LONGEST_HOLD = TimeSpan.timeSpan(Math.round(MAX_HOLD.nanos() * (1.0 + JITTER))).nanos();

    private final ConcurrentHashMap<K, Long> nominalHoldNanos = new ConcurrentHashMap<>();
    private final DoubleSupplier unitRandom;

    private RefusalBackoff(DoubleSupplier unitRandom) {
        this.unitRandom = unitRandom;
    }

    /// The production backoff, jittered from the thread-local random source.
    public static <K> RefusalBackoff<K> refusalBackoff() {
        return refusalBackoff(() -> ThreadLocalRandom.current().nextDouble());
    }

    /// A backoff whose jitter draws from `unitRandom` (uniform in [0, 1)); a seeded source makes it repeatable in tests.
    public static <K> RefusalBackoff<K> refusalBackoff(DoubleSupplier unitRandom) {
        return new RefusalBackoff<>(unitRandom);
    }

    /// Records a refused read for `key` and answers how long to hold before the next send: the doubled hold (the first one
    /// after a send), capped, jittered.
    public TimeSpan refused(K key) {
        var nominal = nominalHoldNanos.merge(key, FIRST_HOLD.nanos(), RefusalBackoff::doubled);

        return TimeSpan.timeSpan(jittered(nominal)).nanos();
    }

    /// Records a read for `key` that was sent: its next refusal holds the first hold again.
    @Contract
    public void sent(K key) {
        nominalHoldNanos.remove(key);
    }

    /// Test-visible: the number of keys holding a doubled hold; a key's entry is what [#sent] clears.
    int tracked() {
        return nominalHoldNanos.size();
    }

    private static Long doubled(Long previous, Long first) {
        return Math.min(previous * 2, MAX_HOLD.nanos());
    }

    private long jittered(long nominal) {
        return Math.round(nominal * (1.0 - JITTER + 2 * JITTER * unitRandom.getAsDouble()));
    }
}
