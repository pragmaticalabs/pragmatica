// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.dht;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DhtPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DhtPartitionOwnershipValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.dht.OwnerEpochSource;


/// Reads this node's CURRENT owner epoch for its DHT ownership arc from the committed CP record, to
/// stamp outgoing DHT puts with a fencing token (#345 piece 1c). The committed
/// `DhtPartitionOwnershipValue.ownerEpoch` (Rabia-backed, leader-fenced — the authoritative epoch) is
/// the source of truth; a put is stamped with whatever epoch is currently committed for the node's
/// arc.
///
/// This is the one cross-module place that turns an [Epoch] into the two primitive `long`s the DHT
/// wire carries, keeping the BSL-1.1 `Epoch` type out of the Apache-2.0 `integrations/dht` module.
/// When no ownership record is committed yet (cold start), it reports [Epoch#ZERO] — the unfenced
/// floor, accepted by every fresh high-water.
public final class KvOwnerEpochSource implements OwnerEpochSource {
    private final KVStore<AetherKey, AetherValue> kvStore;
    private final DhtPartitionOwnershipKey ownershipKey;

    private KvOwnerEpochSource(KVStore<AetherKey, AetherValue> kvStore, String partitionId) {
        this.kvStore = kvStore;
        this.ownershipKey = DhtPartitionOwnershipKey.dhtPartitionOwnershipKey(partitionId);
    }

    /// Source reading the committed owner epoch of the given ownership-arc `partitionId` (today:
    /// `"core"`) from `kvStore`.
    public static KvOwnerEpochSource kvOwnerEpochSource(KVStore<AetherKey, AetherValue> kvStore, String partitionId) {
        return new KvOwnerEpochSource(kvStore, partitionId);
    }

    @Override
    public long currentEpochTerm() {
        return currentEpoch().rabiaTerm();
    }

    @Override
    public long currentEpochCounter() {
        return currentEpoch().localCounter();
    }

    private Epoch currentEpoch() {
        return kvStore.getTyped(ownershipKey, DhtPartitionOwnershipValue.class)
                      .map(DhtPartitionOwnershipValue::ownerEpoch)
                      .or(Epoch.ZERO);
    }
}
