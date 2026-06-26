// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.dht.PartitionOwnerEpochSource;
import org.pragmatica.dht.DHTError;
import org.pragmatica.dht.storage.StorageEngine;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;


/// Per-`(keyspace, partition)` fenced, owner-stamped [DurableEntity] backed by a DHT [StorageEngine]
/// (plan Phase 2b-ii — true per-key single-writer correctness). The sibling of the coarse 2b-i
/// [FencedDurableEntity], differing in ONE dimension: the ownership arc each write is fenced against.
///
/// ## What this cut adds over 2b-i
/// [FencedDurableEntity] stamps and fences every write at the single `"core"` governor arc, whose epoch
/// advances only on a GOVERNOR change. It therefore MISSES a deposed partition owner on a
/// same-generation reshuffle — an owner change with no governor handover. This entity instead routes
/// each key to its own `(keyspace, partition)` ownership arc:
///
///   - the key's partition is derived once via [EntityPartitionArc] and embedded in the DHT key bytes;
///   - the write is stamped with the partition's CURRENT owner epoch (via [PartitionOwnerEpochSource],
///     reading the committed `StreamPartitionOwnershipValue.ownerEpoch`);
///   - the storage engine's [org.pragmatica.aether.dht.PartitionOwnerEpochGate] re-derives the SAME arc
///     from the key bytes and rejects the write if its epoch is strictly older than the partition's
///     committed high-water.
///
/// So a deposed partition owner whose `"core"` epoch is still current — which 2b-i would WAVE THROUGH —
/// is REJECTED here with [DurableEntityError.StaleOwner], because its per-partition epoch is dominated
/// by the partition's advanced high-water (which advanced on the reshuffle with NO governor change).
///
/// ## What it shares with 2b-i
///   - **Per-key serialization.** Same [PerKeySerialExecutor] tail-chaining: same-key operations are
///     totally ordered, different keys proceed in parallel — so the read — fence-stamp — commit cycle
///     for a key is single-threaded and race-free without locks.
///   - **HA, not restart-durable.** The engine is the in-memory `MemoryStorageEngine`; restart
///     durability is the fenced-log slice (plan Phase 3) behind this same API.
///   - **Single-replica, local-owner.** Commits to ONE engine and assumes it runs on the owner;
///     cross-node owner-routing of updates and quorum replication are later sub-slices (#277).
///
/// @param <K> entity key type — rendered to bytes via `String.valueOf` for the DHT key
/// @param <S> entity state type — an application-defined immutable value, encoded via [Serializer]
final class PartitionFencedDurableEntity<K, S> implements DurableEntity<K, S> {
    private final StorageEngine storage;
    private final PartitionOwnerEpochSource ownerEpoch;
    private final EntityPartitionArc arc;
    private final Serializer serializer;
    private final Deserializer deserializer;
    private final AtomicLong versionSequencer;
    private final PerKeySerialExecutor<K> perKey;

    private PartitionFencedDurableEntity(StorageEngine storage,
                                         PartitionOwnerEpochSource ownerEpoch,
                                         EntityPartitionArc arc,
                                         Serializer serializer,
                                         Deserializer deserializer) {
        this.storage = storage;
        this.ownerEpoch = ownerEpoch;
        this.arc = arc;
        this.serializer = serializer;
        this.deserializer = deserializer;
        this.versionSequencer = new AtomicLong();
        this.perKey = PerKeySerialExecutor.perKeySerialExecutor();
    }

    static <K, S> DurableEntity<K, S> partitionFencedDurableEntity(StorageEngine storage,
                                                                   PartitionOwnerEpochSource ownerEpoch,
                                                                   EntityPartitionArc arc,
                                                                   Serializer serializer,
                                                                   Deserializer deserializer) {
        return new PartitionFencedDurableEntity<>(storage, ownerEpoch, arc, serializer, deserializer);
    }

    @Override
    public Promise<S> create(K key, S initial) {
        return perKey.submit(key, () -> doCreate(key, initial));
    }

    @Override
    public Promise<Option<S>> get(K key) {
        return perKey.submit(key, () -> doGet(key));
    }

    @Override
    public Promise<S> update(K key, Fn1<S, S> mutator) {
        return perKey.submit(key, () -> doUpdate(key, mutator));
    }

    @Override
    public Promise<Unit> delete(K key) {
        return perKey.submit(key, () -> doDelete(key));
    }

    @Override
    public Promise<TimerToken> scheduleTimer(K key, Duration delay, Fn1<S, S> onFire) {
        return new DurableEntityError.TimerNotSupported(String.valueOf(key)).promise();
    }

    @Override
    public Promise<Unit> cancelTimer(K key, TimerToken token) {
        return new DurableEntityError.TimerNotSupported(String.valueOf(key)).promise();
    }

    private Promise<S> doCreate(K key, S initial) {
        return readState(key).flatMap(existing -> existing.fold(() -> commit(key, initial), _ -> keyAlreadyExists(key)));
    }

    private Promise<Option<S>> doGet(K key) {
        return readState(key);
    }

    private Promise<S> doUpdate(K key, Fn1<S, S> mutator) {
        return readState(key).flatMap(current -> current.fold(() -> keyNotFound(key),
                                                              state -> commit(key, mutator.apply(state))));
    }

    private Promise<Unit> doDelete(K key) {
        return readState(key).flatMap(current -> current.fold(() -> keyNotFound(key), _ -> removeState(key)));
    }

    /// Read and decode the current state for `key`, mapping any backing failure to a typed cause.
    private Promise<Option<S>> readState(K key) {
        return storage.get(dhtKey(key))
                      .map(bytes -> bytes.map(this::decode))
                      .mapError(cause -> storageFailed(key, cause));
    }

    /// Encode `next` and commit it under the KEY'S PARTITION owner-epoch fence, returning `next` on
    /// success. The stamp is the partition's current committed owner epoch; the engine's
    /// [org.pragmatica.aether.dht.PartitionOwnerEpochGate] rejects a strictly-older stamp with
    /// [DHTError.StaleEpochWrite], translated here to [DurableEntityError.StaleOwner]. Any other backing
    /// failure becomes [DurableEntityError.StorageFailed].
    private Promise<S> commit(K key, S next) {
        var epoch = ownerEpoch.currentOwnerEpoch(arc.partitionOf(String.valueOf(key)));

        return storage.putVersioned(dhtKey(key),
                                    serializer.encode(next),
                                    versionSequencer.incrementAndGet(),
                                    epoch.rabiaTerm(),
                                    epoch.localCounter()).map(_ -> next)
                                   .mapError(cause -> translateCommitFailure(key, cause));
    }

    private Promise<Unit> removeState(K key) {
        return storage.remove(dhtKey(key))
                      .mapToUnit()
                      .mapError(cause -> storageFailed(key, cause));
    }

    private Cause translateCommitFailure(K key, Cause cause) {
        return cause instanceof DHTError.StaleEpochWrite stale
               ? new DurableEntityError.StaleOwner(String.valueOf(key), epochText(stale))
               : storageFailed(key, cause);
    }

    private static String epochText(DHTError.StaleEpochWrite stale) {
        return stale.epochTerm() + ":" + stale.epochCounter();
    }

    private DurableEntityError storageFailed(K key, Cause cause) {
        return new DurableEntityError.StorageFailed(String.valueOf(key), cause);
    }

    @SuppressWarnings("unchecked")
    private S decode(byte[] bytes) {
        return (S) deserializer.decode(bytes);
    }

    private byte[] dhtKey(K key) {
        return arc.dhtKey(String.valueOf(key));
    }

    private static <S> Promise<S> keyAlreadyExists(Object key) {
        return new DurableEntityError.KeyAlreadyExists(String.valueOf(key)).promise();
    }

    private static <S> Promise<S> keyNotFound(Object key) {
        return new DurableEntityError.KeyNotFound(String.valueOf(key)).promise();
    }
}
