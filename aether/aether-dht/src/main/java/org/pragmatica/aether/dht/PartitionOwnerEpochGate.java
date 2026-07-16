// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.dht;

import org.pragmatica.aether.slice.fence.OwnershipDomain.StreamPartition;
import org.pragmatica.aether.slice.fence.OwnershipEpochHighWater;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.dht.storage.OwnerEpochGate;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;


/// Per-`(keyspace, partition)` DHT data-plane fence — the per-partition counterpart of the coarse
/// [HighWaterOwnerEpochGate] (#345 plan Phase 2b-ii). Where [HighWaterOwnerEpochGate] ignores the
/// per-call key and fences EVERY write against the single `dhtPartition("core")` arc, this gate maps
/// each entity write to the `(keyspace, partition)` ownership arc embedded in its DHT key (via
/// [EntityPartitionArc]) and fences it against THAT arc's [OwnershipEpochHighWater] high-water.
///
/// ## Why this matters (the gap the coarse gate misses)
/// The `"core"` arc's epoch advances only on a GOVERNOR change. A same-generation reshuffle — a
/// partition's owner changes with no governor handover (e.g. a node join re-runs HRW) — advances ONLY
/// the per-partition [StreamPartition] high-water, not the coarse `"core"` arc. So a deposed partition
/// owner whose write the coarse fence would WAVE THROUGH (its `"core"` epoch is still current) is here
/// REJECTED, because its per-partition epoch is strictly older than the partition's advanced
/// high-water. This is the true per-key single-writer guarantee.
///
/// ## Keying & determinism
/// The arc is parsed deterministically from the DHT key bytes [EntityPartitionArc] produced, so the
/// stamp site (the entity) and this check site fence the IDENTICAL arc with no re-derivation. A key
/// that does NOT carry the entity `keyspace/partition/...` prefix ([Option#none] from
/// [EntityPartitionArc#arcOf]) is treated as unfenced — never stale, never advancing — so non-entity
/// keys routed through the same engine fall through to pure HLC-version LWW. [#isStale] reads only the
/// presented epoch and the local (CP-seeded, monotonic) high-water — no wall-clock, no randomness — so
/// every replica accepts or rejects identically (enforce-at-replica).
public final class PartitionOwnerEpochGate implements OwnerEpochGate {
    private final OwnershipEpochHighWater highWater;

    private PartitionOwnerEpochGate(OwnershipEpochHighWater highWater) {
        this.highWater = highWater;
    }

    /// Gate fencing entity DHT puts against the per-`(keyspace, partition)` `highWater`. The arc is
    /// derived per call from the DHT key bytes, so one gate instance fences every keyspace/partition
    /// the engine sees.
    public static PartitionOwnerEpochGate partitionOwnerEpochGate(OwnershipEpochHighWater highWater) {
        return new PartitionOwnerEpochGate(highWater);
    }

    @Override
    public boolean isStale(byte[] key, long epochTerm, long epochCounter) {
        return EntityPartitionArc.arcOf(key)
                                 .map(arc -> isStaleForArc(arc, epochTerm, epochCounter))
                                 .or(false);
    }

    @Contract
    @Override
    public void advance(byte[] key, long epochTerm, long epochCounter) {
        EntityPartitionArc.arcOf(key).onPresent(arc -> highWater.advance(arc, Epoch.epoch(epochTerm, epochCounter)));
    }

    private boolean isStaleForArc(StreamPartition arc, long epochTerm, long epochCounter) {
        return highWater.isStale(arc, Epoch.epoch(epochTerm, epochCounter));
    }
}
