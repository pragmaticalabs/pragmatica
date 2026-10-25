// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;


/// How much of the JVM's maximum heap the node's memory tiers may take in total (A0b).
///
/// A memory tier holds its blocks on the heap. The defaults (256 MiB for `artifacts`, 256 MiB for `content`, 16 MiB for
/// `streams`) are 528 MiB whatever the heap is, so a 512m or 256m launcher could be asked for more than it has. The
/// total is bounded by [#HEAP_FRACTION] of the maximum heap; `reservedBytes` (the streams tier, which is not
/// configurable) comes off the top. When the requested sizes of the configured instances do not fit what is left, each
/// is cut in the same proportion. A request that fits is left exactly as configured.
///
/// The cap is on what the tiers may hold, not on what the JVM then does with the rest; it is a fraction, not a
/// guarantee that the heap is never exhausted.
record MemoryTierBudget(long totalBytes) {
    /// Half of the maximum heap for all memory tiers together; the rest is for the node itself (a guess: the defaults, 528 MiB, fit from 1.06 GiB of heap up).
    static final double HEAP_FRACTION = 0.5;

    static MemoryTierBudget ofHeap(long maxHeapBytes, long reservedBytes) {
        return new MemoryTierBudget(Math.max(0L, (long) (maxHeapBytes * HEAP_FRACTION) - reservedBytes));
    }

    /// The sizes to build with: `requested` itself when its sum fits, else every entry scaled by the same ratio (rounded
    /// down, so the scaled sum never exceeds the budget).
    Map<String, Long> fit(Map<String, Long> requested) {
        var sum = requested.values()
                           .stream()
                           .mapToLong(Long::longValue)
                           .sum();

        return sum <= totalBytes
               ? requested
               : scaled(requested, sum);
    }

    private Map<String, Long> scaled(Map<String, Long> requested, long sum) {
        var result = new LinkedHashMap<String, Long>();

        requested.forEach((name, bytes) -> result.put(name, share(bytes, sum)));

        return result;
    }

    /// `bytes * totalBytes / sum` exactly: the product of a large configured size and a large heap overflows a long.
    private long share(long bytes, long sum) {
        return BigInteger.valueOf(bytes)
                         .multiply(BigInteger.valueOf(totalBytes))
                         .divide(BigInteger.valueOf(sum))
                         .longValueExact();
    }
}
