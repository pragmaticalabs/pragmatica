// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Option;


/// Reads the COMMITTED owner of a `(stream, partition)` from the committed CP ownership record, so a
/// `LINEARIZABLE` read routes to the authoritative (fenced) owner rather than the on-the-fly HRW owner
/// (#345 item 1e). The committed `StreamPartitionOwnershipValue` (Rabia-backed, leader-fenced — written
/// by the 1d-i `StreamPartitionOwnershipWriter`) is the source of truth.
///
/// A direct sibling of [KvStreamOwnerEpochSource]: that source reads the same record's `ownerEpoch` (to
/// stamp outgoing appends); this one reads its `owner` (to route the read) AND its `ownerEpoch` (so the
/// read-side epoch fence runs off the same single committed lookup). When no ownership record is
/// committed yet (cold start, or a legacy / unowned partition predating the 1d-iii reshuffle driver) it
/// reports [Option#none] — the [LINEARIZABLE] arm then falls back to the replica-routed read so those
/// partitions are never broken.
public final class KvCommittedStreamOwnerSource implements CommittedStreamOwnerSource {
    private final KVStore<AetherKey, AetherValue> kvStore;

    private KvCommittedStreamOwnerSource(KVStore<AetherKey, AetherValue> kvStore) {
        this.kvStore = kvStore;
    }

    public static KvCommittedStreamOwnerSource kvCommittedStreamOwnerSource(KVStore<AetherKey, AetherValue> kvStore) {
        return new KvCommittedStreamOwnerSource(kvStore);
    }

    @Override
    public Option<CommittedOwner> committedOwner(String stream, int partition) {
        return kvStore.getTyped(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(stream, partition),
                                StreamPartitionOwnershipValue.class)
                      .map(KvCommittedStreamOwnerSource::toCommittedOwner);
    }

    private static CommittedOwner toCommittedOwner(StreamPartitionOwnershipValue value) {
        return new CommittedOwner(value.owner(), value.ownerEpoch());
    }
}
