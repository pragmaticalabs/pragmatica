// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.pragmatica.consensus.NodeId;


/// Total cluster ordering identifier for a {@link ClusterEvent} (spec §6.4.1).
///
/// Combines a per-node monotonic `sequence` with the originating `nodeId`. Within a single node
/// the sequence is unique and strictly increasing; across nodes the sequence values may collide
/// but the `(sequence, nodeId)` pair is globally unique.
///
/// Total ordering: `compareTo` orders by `sequence` first, breaking ties on `nodeId` so that two
/// events emitted at the same logical sequence by different nodes have a deterministic order.
/// Per-node monotonicity already guarantees within-node uniqueness, so the tie-break only fires
/// across nodes.
public record EventId(long sequence, NodeId nodeId) implements Comparable<EventId> {
    @Override public int compareTo(EventId other) {
        var bySequence = Long.compare(sequence, other.sequence);
        return bySequence != 0 ? bySequence : nodeId.compareTo(other.nodeId);
    }
}
