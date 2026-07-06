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
    /// Default. Local committed-prefix read: no coordination, never torn; staleness = consensus apply
    /// lag (ms healthy; grows on a partitioned minority). Monotonic per node; keeps serving through
    /// owner failover and leader churn.
    ///
    /// On the current HA-only in-memory cut this is the process-local read of the single owner's
    /// in-memory map, single-writer-serialized per key (the honest current semantics; the bounded-stale
    /// bound proper is Phase-3-defined, #349, once the entity has a consensus apply).
    BOUNDED_STALE,
    /// Linearizable: the read reflects every write acknowledged before it began. Routes to the committed
    /// partition owner and, at the owner, orders a no-op consensus round then re-checks the epoch fence
    /// AFTER the round before serving (spec §8.1 `no-op-round`); blocks (retriable) during owner/leader
    /// churn. When the owner-routing substrate is not yet wired (cluster wiring, #277) or no ownership
    /// record is committed for the arc, it degrades to the local read — which on a single-owner cut
    /// already reflects every acknowledged write.
    LINEARIZABLE
}
