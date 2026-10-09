// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;

import org.pragmatica.consensus.NodeId;


/// The RAW committed in-sync replica set of a `(stream, partition)` arc: `StreamPartitionOwnershipValue.isr` exactly as the
/// consensus log holds it (#2077).
///
/// Raw on purpose. The routing view of the committed owner is filtered by this node's own membership view, and a dead
/// owner vanishes from it; local membership is not consensus. Anything that decides, from the ISR, whether a record could be
/// missing from this node must read the committed record itself. The writes acked at confirmation factor 2 or more reached
/// every member of the ISR in force, so a candidate named in it, or a reachable peer named in it, holds every acked record.
///
/// Empty when no ownership record is committed yet (cold start, legacy or unowned partitions), which is also what
/// [#none] reports: no ISR, no evidence.
@FunctionalInterface
public interface CommittedStreamIsrSource {
    /// The committed ISR for `(stream, partition)`, or an empty list when no ownership record is committed.
    List<NodeId> committedIsr(String stream, int partition);

    /// No committed ISR for any arc: every decision that needs ISR evidence stays conservative.
    static CommittedStreamIsrSource none() {
        return (_, _) -> List.of();
    }
}
