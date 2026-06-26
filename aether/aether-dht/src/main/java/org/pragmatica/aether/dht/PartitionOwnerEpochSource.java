// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.dht;

import org.pragmatica.aether.slice.generation.Epoch;


/// Ambient source of this node's CURRENT owner [Epoch] for a single `(keyspace, partition)` entity
/// ownership arc, used to stamp each per-partition-fenced entity write with a fencing token (#345 plan
/// Phase 2b-ii). The owner stamps every entity `putVersioned` with its believed-current ownership epoch
/// for the KEY'S partition; the storage engine then rejects it (via [PartitionOwnerEpochGate]) if that
/// epoch is strictly older than the partition's committed high-water — so a deposed partition owner is
/// fenced even on a same-generation reshuffle that never touched the coarse `"core"` arc.
///
/// This is the per-partition sibling of the DHT's key-less [org.pragmatica.dht.OwnerEpochSource] (#345
/// piece 1c) and the stream's `StreamOwnerEpochSource` (1d-ii): both of those carry a SINGLE ambient
/// epoch, whereas an entity backing serves many partitions from one instance, so the epoch is looked up
/// PER partition. The production binding reads the committed `StreamPartitionOwnershipValue.ownerEpoch`
/// for the arc ([KvPartitionOwnerEpochSource]); non-fenced / legacy callers use [#zero] (the unfenced
/// floor [Epoch#ZERO], never rejected and never advancing a high-water).
@FunctionalInterface
public interface PartitionOwnerEpochSource {
    /// This node's current ownership [Epoch] for `partition` — the token stamped onto a write. The
    /// unfenced floor ([Epoch#ZERO]) for a partition this node has no committed ownership record for.
    Epoch currentOwnerEpoch(int partition);

    /// The unfenced floor source ([Epoch#ZERO] → `0:0`) for non-cluster entity paths, legacy callers,
    /// and tests. Every write stamped at the floor is never rejected and never advances a high-water.
    static PartitionOwnerEpochSource zero() {
        return _ -> Epoch.ZERO;
    }
}
