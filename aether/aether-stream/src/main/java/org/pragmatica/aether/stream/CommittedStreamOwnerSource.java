// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;


/// Ambient source of the COMMITTED owner of a `(stream, partition)` arc — the fenced, Rabia-backed
/// `StreamPartitionOwnershipValue.owner` (#345 item 1e), NOT the on-the-fly HRW owner the publish/
/// reconcile paths compute. During a reshuffle the computed HRW owner and the committed owner diverge:
/// the committed owner is the authoritative one a `LINEARIZABLE` read must route to so the read observes
/// the fenced log.
///
/// A sibling of [StreamOwnerEpochSource] / [KvStreamOwnerEpochSource]: that source reads the same
/// committed record's `ownerEpoch` to stamp outgoing appends; this source reads its `owner` (and carries
/// the `ownerEpoch` alongside so the read path can run the owner-side epoch fence with a single committed
/// lookup). [Option#none] when no ownership record is committed yet (cold start, legacy / unowned
/// partitions) — the [LINEARIZABLE] arm falls back to the replica-routed behaviour in that case so those
/// partitions keep working.
@FunctionalInterface
public interface CommittedStreamOwnerSource {
    /// The committed owner record for `(stream, partition)`, or [Option#none] when none is committed.
    Option<CommittedOwner> committedOwner(String stream, int partition);

    /// The committed owner identity plus the fencing epoch it was committed at — the two facts the
    /// [LINEARIZABLE] read path needs from a single committed lookup: `owner` drives routing and the
    /// `NotCurrentOwner` guard, `ownerEpoch` drives the `StaleEpochRead` guard.
    record CommittedOwner(NodeId owner, Epoch ownerEpoch) {}

    /// The no-owner source ([Option#none] for every arc) for non-cluster stream paths, legacy callers,
    /// and tests — the [LINEARIZABLE] arm degrades to the replica-routed read for every partition.
    static CommittedStreamOwnerSource none() {
        return (_, _) -> Option.none();
    }
}
