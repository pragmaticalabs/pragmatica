// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.cluster.state.kvstore.KVStore;


/// Reads this node's CURRENT owner epoch for a `(stream, partition)` from the committed CP ownership
/// record, to stamp outgoing LOCAL stream appends with a fencing token (#345 item 1d-ii). The
/// committed `StreamPartitionOwnershipValue.ownerEpoch` (Rabia-backed, leader-fenced — the
/// authoritative epoch, written by the 1d-i `StreamPartitionOwnershipWriter`) is the source of truth;
/// an append is stamped with whatever epoch is currently committed for its `(stream, partition)` arc.
///
/// Mirrors the DHT's `KvOwnerEpochSource` (#345 item 1c), but — because `aether-stream` may depend on
/// the BSL-1.1 `aether/slice` module directly — returns the [Epoch] without the cross-module
/// `long`-pair decomposition the DHT needs. When no ownership record is committed yet (cold start, or
/// before the 1d-iii reshuffle driver writes one) it reports [Epoch#ZERO] — the unfenced floor,
/// accepted by every fresh high-water, so the fence is inert until real ownership epochs are committed.
public final class KvStreamOwnerEpochSource implements StreamOwnerEpochSource {
    private final KVStore<AetherKey, AetherValue> kvStore;

    private KvStreamOwnerEpochSource(KVStore<AetherKey, AetherValue> kvStore) {
        this.kvStore = kvStore;
    }

    public static KvStreamOwnerEpochSource kvStreamOwnerEpochSource(KVStore<AetherKey, AetherValue> kvStore) {
        return new KvStreamOwnerEpochSource(kvStore);
    }

    @Override
    public Epoch currentOwnerEpoch(String stream, int partition) {
        return kvStore.getTyped(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(stream, partition),
                                StreamPartitionOwnershipValue.class).map(StreamPartitionOwnershipValue::ownerEpoch)
                               .or(Epoch.ZERO);
    }
}
