// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.fence;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DhtPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DhtPartitionOwnershipValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageReceiver;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Per-ownership-domain monotonic high-water of the latest committed owner/governor [Epoch] — the
/// DATA-plane half of the #345 ownership fence (Phase 1, item 1b). The CP-plane half is the Rabia
/// applier's generalized epoch fence over [org.pragmatica.cluster.state.kvstore.EpochBearing]
/// values (piece 1a); this table is its data-plane mirror.
///
/// The table advances by OBSERVING committed `EpochBearing` [ValuePut] notifications and is seeded
/// from committed KV on construction/restore. The query API ([#highWater]/[#isStale]) is what the
/// data-plane write paths (1c DHT `putVersioned` / 1d stream append — NOT YET WIRED) will consult to
/// reject a deposed writer: a presented epoch STRICTLY older than the domain's high-water is stale.
///
/// **Determinism.** Per-node, in-memory, derived PURELY from the committed KV — the same committed
/// state yields an identical table on every node. It is NOT itself replicated state; it is
/// reconstructible on restart by re-seeding from the committed `EpochBearing` values.
///
/// **Concurrency.** Monotonic and concurrency-safe via [ConcurrentHashMap#merge] with a max-merge:
/// older writes are ignored, equal is a no-op, newer advances. No torn reads; concurrent advances on
/// the same domain converge to the maximum regardless of interleaving.
@SuppressWarnings("JBCT-RET-01")
public final class OwnershipEpochHighWater {
    private static final Logger log = LoggerFactory.getLogger(OwnershipEpochHighWater.class);

    private final KVStore<AetherKey, AetherValue> kvStore;
    private final Map<OwnershipDomain, Epoch> highWaterMarks = new ConcurrentHashMap<>();

    private OwnershipEpochHighWater(KVStore<AetherKey, AetherValue> kvStore) {
        this.kvStore = kvStore;
    }

    public static OwnershipEpochHighWater ownershipEpochHighWater(KVStore<AetherKey, AetherValue> kvStore) {
        var registry = new OwnershipEpochHighWater(kvStore);

        registry.seedFromKvStore();

        return registry;
    }

    public static OwnershipEpochHighWater readOnly(KVStore<AetherKey, AetherValue> kvStore) {
        return ownershipEpochHighWater(kvStore);
    }

    /// Monotonic max-merge: older epochs are ignored, equal is a no-op, newer advances. Thread-safe
    /// and idempotent.
    public void advance(OwnershipDomain domain, Epoch epoch) {
        highWaterMarks.merge(domain, epoch, OwnershipEpochHighWater::maxEpoch);
    }

    /// The recorded high-water for the domain, or [Option#none] (the floor) if the domain has never
    /// been advanced.
    public Option<Epoch> highWater(OwnershipDomain domain) {
        return Option.option(highWaterMarks.get(domain));
    }

    /// `true` iff there IS a recorded high-water for the domain AND `presentedEpoch` is strictly
    /// older than it. Unknown domain (the floor) and equal-or-newer presented epochs are NOT stale.
    public boolean isStale(OwnershipDomain domain, Epoch presentedEpoch) {
        return highWater(domain).map(hw -> hw.isStrictlyAfter(presentedEpoch)).or(false);
    }

    private void seedFromKvStore() {
        kvStore.forEach(GovernorAnnouncementKey.class, GovernorAnnouncementValue.class, this::seedGovernor);
        kvStore.forEach(DhtPartitionOwnershipKey.class, DhtPartitionOwnershipValue.class, this::seedDhtPartition);
        kvStore.forEach(StreamPartitionOwnershipKey.class, StreamPartitionOwnershipValue.class, this::seedStreamPartition);
        log.info("Seeded {} ownership-domain epoch high-water marks from KV-Store", highWaterMarks.size());
    }

    private void seedGovernor(GovernorAnnouncementKey key, GovernorAnnouncementValue value) {
        advance(OwnershipDomain.community(key.communityId()), value.fenceEpoch());
    }

    private void seedDhtPartition(DhtPartitionOwnershipKey key, DhtPartitionOwnershipValue value) {
        advance(OwnershipDomain.dhtPartition(key.partitionId()), value.fenceEpoch());
    }

    private void seedStreamPartition(StreamPartitionOwnershipKey key, StreamPartitionOwnershipValue value) {
        advance(OwnershipDomain.streamPartition(key.stream(), key.partition()), value.fenceEpoch());
    }

    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01")
    public void onGovernorAnnouncementPut(ValuePut<GovernorAnnouncementKey, GovernorAnnouncementValue> valuePut) {
        var key = valuePut.cause().key();
        var value = valuePut.cause().value();

        advance(OwnershipDomain.community(key.communityId()), value.fenceEpoch());
        log.debug("Governor announcement epoch observed from cluster: {} -> {}",
                  key.communityId(),
                  value.fenceEpoch());
    }

    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01")
    public void onDhtOwnershipPut(ValuePut<DhtPartitionOwnershipKey, DhtPartitionOwnershipValue> valuePut) {
        var key = valuePut.cause().key();
        var value = valuePut.cause().value();

        advance(OwnershipDomain.dhtPartition(key.partitionId()), value.fenceEpoch());
        log.debug("DHT partition ownership epoch observed from cluster: {} -> {}",
                  key.partitionId(),
                  value.fenceEpoch());
    }

    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01")
    public void onStreamPartitionOwnershipPut(ValuePut<StreamPartitionOwnershipKey, StreamPartitionOwnershipValue> valuePut) {
        var key = valuePut.cause().key();
        var value = valuePut.cause().value();

        advance(OwnershipDomain.streamPartition(key.stream(), key.partition()), value.fenceEpoch());
        log.debug("Stream partition ownership epoch observed from cluster: {}/{} -> {}",
                  key.stream(),
                  key.partition(),
                  value.fenceEpoch());
    }

    private static Epoch maxEpoch(Epoch a, Epoch b) {
        return a.isAtLeast(b) ? a : b;
    }
}
