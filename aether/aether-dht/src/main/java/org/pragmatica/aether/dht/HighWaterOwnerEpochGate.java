// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.dht;

import org.pragmatica.aether.slice.fence.OwnershipDomain;
import org.pragmatica.aether.slice.fence.OwnershipEpochHighWater;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.dht.storage.OwnerEpochGate;
import org.pragmatica.lang.Contract;


/// Binds the DHT data-plane fence ([OwnerEpochGate], Apache-2.0 `integrations/dht`) to the
/// per-ownership-domain [OwnershipEpochHighWater] (BSL-1.1 `aether/slice`) — the cross-module seam of
/// #345 piece 1c. The DHT engine carries the owner epoch only as two primitive `long`s; this class is
/// the one place that reassembles them into an [Epoch] and consults / advances the high-water, so the
/// `Epoch` type never leaks into the lower DHT module.
///
/// **Single ownership arc today.** Every DHT key handled by an aether node belongs to the one
/// `DhtPartitionOwnershipKey("core")` arc (`BootstrapModule.CORE_PARTITION_ID`), so this gate ignores
/// the per-call key and fences against the fixed [OwnershipDomain#dhtPartition] for that arc. The DHT
/// SPI still passes the key through, leaving room for a future multi-arc mapping without an engine
/// change.
///
/// **Enforce-at-replica.** A `MemoryStorageEngine` built with this gate enforces the fence at its own
/// commit point, so each replica rejects a deposed owner's strictly-older-epoch write independently of
/// what that owner believes — exactly the replica-side enforcement spec §6 requires.
public final class HighWaterOwnerEpochGate implements OwnerEpochGate {
    private final OwnershipEpochHighWater highWater;
    private final OwnershipDomain.DhtPartition domain;

    private HighWaterOwnerEpochGate(OwnershipEpochHighWater highWater, String partitionId) {
        this.highWater = highWater;
        this.domain = OwnershipDomain.dhtPartition(partitionId);
    }

    /// Gate fencing the DHT puts of the given ownership-arc `partitionId` (today: `"core"`) against
    /// `highWater`.
    public static HighWaterOwnerEpochGate highWaterOwnerEpochGate(OwnershipEpochHighWater highWater,
                                                                  String partitionId) {
        return new HighWaterOwnerEpochGate(highWater, partitionId);
    }

    @Override
    public boolean isStale(byte[] key, long epochTerm, long epochCounter) {
        return highWater.isStale(domain, Epoch.epoch(epochTerm, epochCounter));
    }

    @Contract
    @Override
    public void advance(byte[] key, long epochTerm, long epochCounter) {
        highWater.advance(domain, Epoch.epoch(epochTerm, epochCounter));
    }
}
