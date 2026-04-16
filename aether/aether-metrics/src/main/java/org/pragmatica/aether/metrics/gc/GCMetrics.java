// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics.gc;

public record GCMetrics(long youngGcCount,
                        long youngGcPauseMs,
                        long oldGcCount,
                        long oldGcPauseMs,
                        long reclaimedBytes,
                        long allocationRateBytesPerSec,
                        long promotionRateBytesPerSec,
                        long lastMajorGcTimestamp) {
    public static final GCMetrics EMPTY = new GCMetrics(0, 0, 0, 0, 0, 0, 0, 0);

    public long totalGcCount() {
        return youngGcCount + oldGcCount;
    }

    public long totalPauseMs() {
        return youngGcPauseMs + oldGcPauseMs;
    }

    public double avgPauseMs() {
        long total = totalGcCount();
        if (total == 0) {return 0.0;}
        return totalPauseMs() / (double) total;
    }
}
