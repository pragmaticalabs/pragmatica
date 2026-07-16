// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.dht;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;


/// Ambient source of the COMMITTED owner of a `(keyspace, partition)` entity ownership arc — the fenced,
/// Rabia-backed `StreamPartitionOwnershipValue.owner` (#345 item 1e-b), NOT an on-the-fly HRW owner. A
/// `LINEARIZABLE` durable-entity read routes to this committed owner so the read observes the fenced
/// authoritative state even when a reshuffle has moved ownership.
///
/// The aether-dht sibling of the stream's `CommittedStreamOwnerSource` (aether-stream): the entity
/// keyspace REUSES the stream-partition ownership record family ([org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey]),
/// so both read the SAME committed record — this one under `aether-dht` because the durable-entity module
/// depends on `aether-dht`, not `aether-stream`. It is the read-routing counterpart of
/// [KvPartitionOwnerEpochSource], which reads the same record's `ownerEpoch` to STAMP writes; this source
/// reads its `owner` (to route the read) AND its `ownerEpoch` (so the read-side epoch fence runs off the
/// same single committed lookup). [Option#none] when no ownership record is committed yet (cold start, or
/// a legacy / unowned partition predating the reshuffle driver, #277) — the `LINEARIZABLE` arm then
/// degrades to the local read so those keyspaces keep serving.
@FunctionalInterface
public interface CommittedPartitionOwnerSource {
    /// The committed owner record for the entity family `keyspace`'s `partition` arc, or [Option#none]
    /// when none is committed.
    Option<CommittedOwner> committedOwner(String keyspace, int partition);

    /// The committed owner identity plus the fencing epoch it was committed at — the two facts the
    /// `LINEARIZABLE` read path needs from a single committed lookup: `owner` drives routing and the
    /// non-owner rejection, `ownerEpoch` drives the stale-epoch rejection.
    record CommittedOwner(NodeId owner, Epoch ownerEpoch) {}

    /// The no-owner source ([Option#none] for every arc) for non-cluster entity paths, legacy callers,
    /// and tests — the `LINEARIZABLE` arm degrades to the local read for every partition.
    static CommittedPartitionOwnerSource none() {
        return (_, _) -> Option.none();
    }
}
