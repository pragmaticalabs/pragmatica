// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

/// Read-routing preference for a replicated-stream read.
///
///   - `GOVERNOR` — read the local partition unconditionally (the owner/governor serves its own log).
///   - `ANY_REPLICA` / `NEAREST` — route to any caught-up replica (or the deterministic HRW owner during
///     the bootstrap window), failing soft to a local read.
///   - `LINEARIZABLE` — route strictly to the COMMITTED owner of the `(stream, partition)` arc (the
///     fenced `StreamPartitionOwnershipValue.owner`, NOT the on-the-fly HRW owner), so a read observes
///     the authoritative log even mid-reshuffle when the computed and committed owners diverge (#345
///     item 1e). The committed owner serves only after passing the owner-side guards: it must still be
///     the committed owner, its committed epoch must not be stale, and it must have caught up to the
///     handover offset. When no ownership record is committed yet (legacy / unowned partitions) the read
///     falls back to the replica-routed behaviour, preserving compatibility.
public enum ReadPreference {
    GOVERNOR,
    ANY_REPLICA,
    NEAREST,
    LINEARIZABLE
}
