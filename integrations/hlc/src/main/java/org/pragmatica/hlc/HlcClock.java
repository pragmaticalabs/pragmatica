/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.pragmatica.hlc;

import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.hlc.HlcTimestamp.pack;

/// Hybrid Logical Clock implementation providing causally consistent timestamps.
///
/// Thread-safe. Uses a {@link ReentrantLock} to serialize access to the mutable packed state.
/// The clock combines physical time with a logical counter to guarantee monotonically
/// increasing timestamps even when the physical clock does not advance.
public final class HlcClock {
    private static final long DEFAULT_MAX_DRIFT_MICROS = 500_000L;
    private static final int COUNTER_BITS = 16;
    private static final long COUNTER_MASK = 0xFFFFL;
    private static final int MAX_COUNTER = 0xFFFF;

    private static final Cause EMPTY_NODE_ID = Causes.cause("Node ID must not be null or empty");
    private static final Cause INVALID_MAX_DRIFT = Causes.cause("Max drift must be positive");

    private volatile long latestPacked;
    private final String nodeId;
    private final long maxDriftMicros;
    private final LongSupplier physicalClock;
    private final ReentrantLock lock = new ReentrantLock();

    private HlcClock(String nodeId, LongSupplier physicalClock, long maxDriftMicros) {
        this.nodeId = nodeId;
        this.physicalClock = physicalClock;
        this.maxDriftMicros = maxDriftMicros;
        this.latestPacked = 0L;
    }

    /// Creates an HLC clock with the given node ID, physical clock source, and maximum allowed drift.
    ///
    /// @param nodeId          unique identifier for this node
    /// @param physicalClock   supplier returning current physical time in microseconds
    /// @param maxDriftMicros  maximum allowed drift between remote and local clocks in microseconds
    public static Result<HlcClock> hlcClock(String nodeId, LongSupplier physicalClock, long maxDriftMicros) {
        if (nodeId == null || nodeId.isEmpty()) {
            return EMPTY_NODE_ID.result();
        }

        if (maxDriftMicros <= 0) {
            return INVALID_MAX_DRIFT.result();
        }

        return Result.success(new HlcClock(nodeId, physicalClock, maxDriftMicros));
    }

    /// Creates an HLC clock with default physical clock (system time in microseconds) and default max drift (500ms).
    ///
    /// @param nodeId unique identifier for this node
    public static Result<HlcClock> hlcClock(String nodeId) {
        return hlcClock(nodeId, HlcClock::systemMicros, DEFAULT_MAX_DRIFT_MICROS);
    }

    /// Generates a new timestamp for a local event.
    /// Guarantees the returned timestamp is strictly greater than any previously issued timestamp.
    public HlcTimestamp now() {
        lock.lock();
        try {
            return advanceForLocalEvent();
        } finally {
            lock.unlock();
        }
    }

    /// Updates the clock upon receiving a remote timestamp, merging remote causality.
    /// Returns a failure if the remote timestamp exceeds the maximum allowed drift.
    public Result<HlcTimestamp> update(HlcTimestamp remote) {
        lock.lock();
        try {
            return mergeRemoteTimestamp(remote);
        } finally {
            lock.unlock();
        }
    }

    /// Returns the current clock state without advancing it. Suitable for diagnostics only.
    public HlcTimestamp peek() {
        return new HlcTimestamp(latestPacked, nodeId);
    }

    /// Resets the clock to the current physical time with counter zero.
    /// Intended for operator recovery scenarios only.
    public HlcTimestamp forceReset() {
        lock.lock();
        try {
            latestPacked = pack(physicalClock.getAsLong(), 0);
            return new HlcTimestamp(latestPacked, nodeId);
        } finally {
            lock.unlock();
        }
    }

    private HlcTimestamp advanceForLocalEvent() {
        var physicalNow = physicalClock.getAsLong();
        var latestPhysical = physicalMicros(latestPacked);

        if (physicalNow > latestPhysical) {
            latestPacked = pack(physicalNow, 0);
        } else {
            var counter = counter(latestPacked) + 1;
            ensureCounterInRange(counter);
            latestPacked = pack(latestPhysical, counter);
        }

        return new HlcTimestamp(latestPacked, nodeId);
    }

    private Result<HlcTimestamp> mergeRemoteTimestamp(HlcTimestamp remote) {
        var physicalNow = physicalClock.getAsLong();
        var remotePhysical = remote.physicalMicros();

        if (remotePhysical - physicalNow > maxDriftMicros) {
            return new HlcError.ClockDriftExceeded(remotePhysical, physicalNow, maxDriftMicros).result();
        }

        var latestPhysical = physicalMicros(latestPacked);
        var maxPhysical = Math.max(physicalNow, Math.max(remotePhysical, latestPhysical));

        var counter = computeMergedCounter(maxPhysical, latestPhysical, remotePhysical, remote);
        ensureCounterInRange(counter);

        latestPacked = pack(maxPhysical, counter);
        return Result.success(new HlcTimestamp(latestPacked, nodeId));
    }

    private int computeMergedCounter(long maxPhysical, long latestPhysical, long remotePhysical, HlcTimestamp remote) {
        if (maxPhysical == latestPhysical && maxPhysical == remotePhysical) {
            return Math.max(counter(latestPacked), remote.counter()) + 1;
        }

        if (maxPhysical == latestPhysical) {
            return counter(latestPacked) + 1;
        }

        if (maxPhysical == remotePhysical) {
            return remote.counter() + 1;
        }

        return 0;
    }

    private static void ensureCounterInRange(int counter) {
        if (counter > MAX_COUNTER) {
            throw new CounterOverflowException();
        }
    }

    private static long physicalMicros(long packed) {
        return packed >>> COUNTER_BITS;
    }

    private static int counter(long packed) {
        return (int) (packed & COUNTER_MASK);
    }

    private static long systemMicros() {
        return Instant.now().toEpochMilli() * 1000;
    }
}
