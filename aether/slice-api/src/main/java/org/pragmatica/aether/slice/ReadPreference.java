// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice;

/// Read-routing preference for a replicated-stream read.
///
///   - `GOVERNOR` — read the local partition unconditionally (the owner/governor serves its own log).
///   - `ANY_REPLICA` — route to a caught-up replica (one retry on another), or the deterministic HRW owner
///     during the bootstrap window. A read whose every selected peer could not be asked (timeout, unavailable
///     forwarder, no such partition there) is answered from this node's own copy when this node is itself a
///     caught-up replica; otherwise, and for any typed answer of a peer, the remote cause is returned, never a
///     local read that would mask it (#946).
///   - `NEAREST` — read local when this node is a caught-up replica, else local-first and forward to the HRW owner
///     on a miss; the owner forward degrades to the local read when the owner could not be asked.
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
