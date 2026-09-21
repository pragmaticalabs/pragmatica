// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

/// Per-call read consistency for a [DurableEntity] read (spec §8.1, resolves S5). Every read
/// ([DurableEntity#get] — and, once the workflow/saga facades land in Phase 4, `current`/`status`) takes
/// an optional `ReadConsistency`; the no-arg [DurableEntity#get] reads [#BOUNDED_STALE]. Naming the
/// SEMANTICS (not the mechanism) lets callers mix fast polling with decision-point reads in one
/// application while ops swaps the `LINEARIZABLE` mechanism (`[durable-entity] read-linearization`)
/// without touching code.
public enum ReadConsistency {
    /// Default. Local fold read: no coordination, never torn; staleness = log replication lag (ms
    /// healthy; grows on a partitioned minority). Monotonic per node; keeps serving through owner
    /// failover and leader churn.
    ///
    /// On the shipped fenced-log implementation this is served by any node that HOLDS the key's partition
    /// log — owner or replica — from the partition fold, after the fold has been rebuilt and caught up to
    /// the local log's head; a node with no local log forwards to the committed owner rather than
    /// answering from a void. The bound is in log offsets: whatever the local log has not yet received.
    BOUNDED_STALE,
    /// Linearizable: the read reflects every write acknowledged before it began. Served by the committed
    /// partition owner only, which orders a no-op consensus round then re-checks the epoch fence AFTER
    /// the round before serving (spec §8.1 `no-op-round`); a non-owner refuses with
    /// [EntityError.NotCurrentOwner] and an owner without a consensus barrier refuses with
    /// [EntityError.LinearizableUnavailable] — both retriable, never a quiet downgrade to the local read.
    /// Only the absence of a committed ownership record for the arc degrades to the local read.
    LINEARIZABLE
}
