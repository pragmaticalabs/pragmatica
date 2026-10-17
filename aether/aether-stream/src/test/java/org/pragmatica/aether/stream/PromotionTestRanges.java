// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;
import java.util.Map;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

/// #1555 promotion-gate test wiring shared by the in-JVM ring tests: the overlap read answered straight from each
/// node's real ring, and the alarm/window defaults for tests that do not exercise reporting.
sealed interface PromotionTestRanges {
    OwnerActivation.BlockAlarm NO_ALARM = _ -> Unit.unit();
    TimeSpan NEVER_ALARM = TimeSpan.timeSpan(1).hours();

    /// The appended records of each node's own ring.
    static OwnerActivation.RecordRange over(Map<NodeId, StreamPartitionManager> rings) {
        return (node, stream, partition, from, to) -> appended(rings.get(node), stream, partition, from, to);
    }

    private static Promise<List<OffHeapRingBuffer.RawEvent>> appended(StreamPartitionManager ring,
                                                                    String stream,
                                                                    int partition,
                                                                    long from,
                                                                    long to) {
        return ring.readAppended(stream, partition, from, (int) (to - from + 1))
                   .async();
    }

    record unused() implements PromotionTestRanges {}
}
