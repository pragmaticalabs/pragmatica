// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.fence;

/// The key of the per-ownership-domain epoch high-water table (#345 Phase 1, item 1b; spec §3.2 +
/// Open Q1 "a single `OwnershipDomain` abstraction keyed uniformly").
///
/// One domain per ownership *arc* — a community ([Community]) or a DHT partition ([DhtPartition]) —
/// NOT per data key. Keying by the ownership arc rather than by individual data keys is what lets the
/// high-water also fence brand-new-key inserts: a deposed writer cannot smuggle a stale-epoch write
/// in under a key the table has never seen, because the floor is the arc's high-water, not the key's.
///
/// Records give value-equality, so an `OwnershipDomain` is a stable `ConcurrentHashMap` key. The two
/// variants come straight from the two committed [org.pragmatica.cluster.state.kvstore.EpochBearing]
/// `AetherValue` arms — `GovernorAnnouncementValue` (community) and `DhtPartitionOwnershipValue` (DHT
/// partition). No validation is needed: these strings come from already-committed KV keys.
public sealed interface OwnershipDomain {
    record Community(String communityId) implements OwnershipDomain {}

    record DhtPartition(String partitionId) implements OwnershipDomain {}

    static Community community(String communityId) {
        return new Community(communityId);
    }

    static DhtPartition dhtPartition(String partitionId) {
        return new DhtPartition(partitionId);
    }
}
