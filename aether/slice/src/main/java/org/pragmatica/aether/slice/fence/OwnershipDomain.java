// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.fence;

/// The key of the per-ownership-domain epoch high-water table (#345 Phase 1, item 1b; spec §3.2 +
/// Open Q1 "a single `OwnershipDomain` abstraction keyed uniformly").
///
/// One domain per ownership *arc* — a community ([Community]), a DHT partition ([DhtPartition]), or a
/// stream partition ([StreamPartition]) — NOT per data key. Keying by the ownership arc rather than by
/// individual data keys is what lets the high-water also fence brand-new-key inserts: a deposed writer
/// cannot smuggle a stale-epoch write in under a key the table has never seen, because the floor is the
/// arc's high-water, not the key's.
///
/// Records give value-equality, so an `OwnershipDomain` is a stable `ConcurrentHashMap` key. The
/// variants come straight from the committed [org.pragmatica.cluster.state.kvstore.EpochBearing]
/// `AetherValue` arms — `GovernorAnnouncementValue` (community), `DhtPartitionOwnershipValue` (DHT
/// partition), and `StreamPartitionOwnershipValue` (stream partition; #345 item 1d-i, the start of
/// #265's reshuffle-ring work). No validation is needed: these strings/ints come from already-committed
/// KV keys.
public sealed interface OwnershipDomain {
    record Community(String communityId) implements OwnershipDomain {}

    record DhtPartition(String partitionId) implements OwnershipDomain {}

    /// A single `(stream, partition)` ownership arc. The two-tuple is the arc identity — a stream's
    /// partitions are reshuffled independently, so each carries its own epoch high-water.
    record StreamPartition(String stream, int partition) implements OwnershipDomain {}

    static Community community(String communityId) {
        return new Community(communityId);
    }

    static DhtPartition dhtPartition(String partitionId) {
        return new DhtPartition(partitionId);
    }

    static StreamPartition streamPartition(String stream, int partition) {
        return new StreamPartition(stream, partition);
    }
}
