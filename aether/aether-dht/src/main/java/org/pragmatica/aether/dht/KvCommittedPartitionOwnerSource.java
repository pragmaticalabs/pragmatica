// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.dht;

import org.pragmatica.aether.dht.CommittedPartitionOwnerSource.CommittedOwner;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Option;


/// Reads the COMMITTED owner (identity + epoch) of a `(keyspace, partition)` entity ownership arc from
/// the committed CP ownership record, so a `LINEARIZABLE` durable-entity read routes to the authoritative
/// (fenced) owner rather than a local copy (#345 item 1e-b). The committed `StreamPartitionOwnershipValue`
/// (Rabia-backed, leader-fenced — written by the 1d-i `StreamPartitionOwnershipWriter`) is the source of
/// truth.
///
/// A direct sibling of the stream's `KvCommittedStreamOwnerSource` and of [KvPartitionOwnerEpochSource]:
/// the entity keyspace reuses the stream-partition ownership record family ([StreamPartitionOwnershipKey])
/// for its `(keyspace, partition)` arcs — the SAME committed record, high-water table
/// ([org.pragmatica.aether.slice.fence.OwnershipEpochHighWater]) and leader-only writer that make stream
/// reads linearizable make entity reads linearizable, with no new ownership record type. [Option#none]
/// when no ownership record is committed yet (cold start, or before a reshuffle driver writes one for this
/// keyspace, #277) — the `LINEARIZABLE` arm then degrades to the local read so those keyspaces are never
/// broken.
public final class KvCommittedPartitionOwnerSource implements CommittedPartitionOwnerSource {
    private final KVStore<AetherKey, AetherValue> kvStore;

    private KvCommittedPartitionOwnerSource(KVStore<AetherKey, AetherValue> kvStore) {
        this.kvStore = kvStore;
    }

    /// Source reading the committed owner of the entity family `keyspace`'s `(keyspace, partition)` arcs
    /// from `kvStore`.
    public static KvCommittedPartitionOwnerSource kvCommittedPartitionOwnerSource(KVStore<AetherKey, AetherValue> kvStore) {
        return new KvCommittedPartitionOwnerSource(kvStore);
    }

    @Override
    public Option<CommittedOwner> committedOwner(String keyspace, int partition) {
        return kvStore.getTyped(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(keyspace, partition),
                                StreamPartitionOwnershipValue.class)
                      .map(KvCommittedPartitionOwnerSource::toCommittedOwner);
    }

    private static CommittedOwner toCommittedOwner(StreamPartitionOwnershipValue value) {
        return new CommittedOwner(value.owner(), value.ownerEpoch());
    }
}
