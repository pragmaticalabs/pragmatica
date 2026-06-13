// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

/// Self's CURRENT local watermark (highest offset held in the local partition ring; `-1` for empty)
/// for a `(stream, partition)`. Used by {@link PartitionBackfill}'s cold-start deadlock-break to compare
/// self against the watermarks reported by reachable peers via {@link ReplicaWatermarkProbe}.
///
/// The registry's self-descriptor `confirmedOffset` is NOT a substitute: after a restart it is `-1`
/// (SYNCING) even though the node may have recovered real local events, so the promotion contest must
/// read the actual local tail — the SAME notion the peer probe computes remotely.
@FunctionalInterface
public interface SelfWatermark {
    long localWatermark(String streamName, int partition);
}
