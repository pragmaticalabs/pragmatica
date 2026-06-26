// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.dht;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.cluster.state.kvstore.KVStore;


/// Reads this node's CURRENT owner epoch for a `(keyspace, partition)` entity ownership arc from the
/// committed CP ownership record, to stamp outgoing per-partition-fenced entity writes with a fencing
/// token (#345 plan Phase 2b-ii). The committed `StreamPartitionOwnershipValue.ownerEpoch` (Rabia-backed,
/// leader-fenced — the authoritative epoch written by the 1d-i `StreamPartitionOwnershipWriter`) is the
/// source of truth; a write is stamped with whatever epoch is currently committed for its arc.
///
/// The entity keyspace reuses the stream-partition ownership record family ([StreamPartitionOwnershipKey])
/// for its `(keyspace, partition)` arcs: the SAME committed record, high-water table
/// ([OwnershipEpochHighWater]) and leader-only writer that make stream appends fenced make entity writes
/// fenced — no new ownership record type. When no ownership record is committed yet (cold start, or before
/// a reshuffle driver writes one for this keyspace) it reports [Epoch#ZERO] — the unfenced floor, accepted
/// by every fresh high-water, so the fence is inert until real ownership epochs are committed for the
/// keyspace (the AetherNode-wiring sub-slice, #277).
public final class KvPartitionOwnerEpochSource implements PartitionOwnerEpochSource {
    private final KVStore<AetherKey, AetherValue> kvStore;
    private final String keyspace;

    private KvPartitionOwnerEpochSource(KVStore<AetherKey, AetherValue> kvStore, String keyspace) {
        this.kvStore = kvStore;
        this.keyspace = keyspace;
    }

    /// Source reading the committed owner epoch of the entity family `keyspace`'s `(keyspace, partition)`
    /// arcs from `kvStore`.
    public static KvPartitionOwnerEpochSource kvPartitionOwnerEpochSource(KVStore<AetherKey, AetherValue> kvStore,
                                                                          String keyspace) {
        return new KvPartitionOwnerEpochSource(kvStore, keyspace);
    }

    @Override
    public Epoch currentOwnerEpoch(int partition) {
        return kvStore.getTyped(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(keyspace, partition),
                                StreamPartitionOwnershipValue.class).map(StreamPartitionOwnershipValue::ownerEpoch)
                               .or(Epoch.ZERO);
    }
}
