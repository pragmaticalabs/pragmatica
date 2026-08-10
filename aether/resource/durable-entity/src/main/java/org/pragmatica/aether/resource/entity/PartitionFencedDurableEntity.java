// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.aether.dht.CommittedPartitionOwnerSource;
import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.dht.PartitionOwnerEpochSource;
import org.pragmatica.aether.slice.fence.OwnershipEpochHighWater;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.DHTError;
import org.pragmatica.dht.storage.StorageEngine;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;


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
/// ## Honest linearizable reads (#345 I1 owner ruling)
/// Both wired properties live in THIS class. Before I1 the stronger fence and the `LINEARIZABLE` read
/// pipeline lived in different classes and no variant had both: this one inherited [DurableEntity]'s
/// default `get(K, ReadConsistency)` and served a bare local read even when `LINEARIZABLE` was requested
/// — a shipped API silently ignoring its own argument. The wired form
/// ([#partitionFencedDurableEntity(StorageEngine, PartitionOwnerEpochSource, EntityPartitionArc,
/// Serializer, Deserializer, NodeId, CommittedPartitionOwnerSource, Option, Option)]) routes a
/// `LINEARIZABLE` read through [LinearizableEntityServe] — committed-owner routing, no-op round,
/// post-round epoch fence — over the SAME arc the write fence uses.
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
    private final Option<EntityOwnerAdmission> admission;
    private final Option<LinearizableEntityServe<K, S>> linearizableServe;

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
        this.admission = Option.none();
        this.linearizableServe = Option.none();
    }

    private PartitionFencedDurableEntity(StorageEngine storage,
                                         PartitionOwnerEpochSource ownerEpoch,
                                         EntityPartitionArc arc,
                                         Serializer serializer,
                                         Deserializer deserializer,
                                         NodeId selfNodeId,
                                         CommittedPartitionOwnerSource committedOwnerSource,
                                         Option<OwnershipEpochHighWater> epochHighWater,
                                         Option<EntityLinearizableBarrier> barrier) {
        this.storage = storage;
        this.ownerEpoch = ownerEpoch;
        this.arc = arc;
        this.serializer = serializer;
        this.deserializer = deserializer;
        this.versionSequencer = new AtomicLong();
        this.perKey = PerKeySerialExecutor.perKeySerialExecutor();
        this.admission = Option.some(EntityOwnerAdmission.entityOwnerAdmission(selfNodeId, arc, committedOwnerSource));
        this.linearizableServe = Option.some(LinearizableEntityServe.linearizableEntityServe(selfNodeId,
                                                                                             arc,
                                                                                             committedOwnerSource,
                                                                                             epochHighWater,
                                                                                             barrier,
                                                                                             this::get));
    }

    static <K, S> DurableEntity<K, S> partitionFencedDurableEntity(StorageEngine storage,
                                                                   PartitionOwnerEpochSource ownerEpoch,
                                                                   EntityPartitionArc arc,
                                                                   Serializer serializer,
                                                                   Deserializer deserializer) {
        return new PartitionFencedDurableEntity<>(storage, ownerEpoch, arc, serializer, deserializer);
    }

    /// Linearizable-capable per-partition-fenced entity: writes carry the per-`(keyspace, partition)`
    /// owner-epoch fence AND a [ReadConsistency#LINEARIZABLE] read routes to the key's committed partition
    /// owner via [LinearizableEntityServe]. This is the form the node wiring provisions (#345 I1); the
    /// arc is shared by both halves, so the read fence and the write fence can never disagree about which
    /// ownership arc a key belongs to.
    static <K, S> DurableEntity<K, S> partitionFencedDurableEntity(StorageEngine storage,
                                                                   PartitionOwnerEpochSource ownerEpoch,
                                                                   EntityPartitionArc arc,
                                                                   Serializer serializer,
                                                                   Deserializer deserializer,
                                                                   NodeId selfNodeId,
                                                                   CommittedPartitionOwnerSource committedOwnerSource,
                                                                   Option<OwnershipEpochHighWater> epochHighWater,
                                                                   Option<EntityLinearizableBarrier> barrier) {
        return new PartitionFencedDurableEntity<>(storage,
                                                  ownerEpoch,
                                                  arc,
                                                  serializer,
                                                  deserializer,
                                                  selfNodeId,
                                                  committedOwnerSource,
                                                  epochHighWater,
                                                  barrier);
    }

    @Override
    public Promise<S> create(K key, S initial) {
        return perKey.submit(key, () -> doCreate(key, initial));
    }

    @Override
    public Promise<Option<S>> get(K key) {
        return perKey.submit(key, () -> doGet(key));
    }

    /// [ReadConsistency#BOUNDED_STALE] serves the local fenced-storage read [#get];
    /// [ReadConsistency#LINEARIZABLE] routes through the owner-serve pipeline when wired, else degrades to
    /// the local read.
    @Override
    public Promise<Option<S>> get(K key, ReadConsistency consistency) {
        return switch (consistency) {
            case BOUNDED_STALE -> get(key);
            case LINEARIZABLE -> readLinearizable(key);
        };
    }

    private Promise<Option<S>> readLinearizable(K key) {
        // Unwired fold-to-local is honest ONLY while this entity is single-replica local-owner (ONE
        // StorageEngine, one serialized writer per key => a local get is trivially linearizable), and the
        // node wiring never takes this arm — it always supplies the serve pipeline. The moment the entity
        // gains cross-node replication (#349 / Phase 3), an unwired LINEARIZABLE read MUST become
        // DurableEntityError.LinearizableUnavailable, never a silent local read — revisit this fold there.
        return linearizableServe.fold(() -> get(key), serve -> serve.serve(key));
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
        return admitWrite(key).fold(Cause::promise, _ -> createAdmitted(key, initial));
    }

    private Promise<S> createAdmitted(K key, S initial) {
        return readState(key).flatMap(existing -> existing.fold(() -> commit(key, initial), _ -> keyAlreadyExists(key)));
    }

    private Promise<Option<S>> doGet(K key) {
        return readState(key);
    }

    private Promise<S> doUpdate(K key, Fn1<S, S> mutator) {
        return admitWrite(key).fold(Cause::promise, _ -> updateAdmitted(key, mutator));
    }

    private Promise<S> updateAdmitted(K key, Fn1<S, S> mutator) {
        return readState(key).flatMap(current -> current.fold(() -> keyNotFound(key),
                                                              state -> commit(key, mutator.apply(state))));
    }

    private Promise<Unit> doDelete(K key) {
        return admitWrite(key).fold(Cause::promise, _ -> deleteAdmitted(key));
    }

    private Promise<Unit> deleteAdmitted(K key) {
        return readState(key).flatMap(current -> current.fold(() -> keyNotFound(key), _ -> removeState(key)));
    }

    /// Owner admission, ahead of the read-modify-write and ahead of the epoch fence: only the committed
    /// owner of the key's arc may mutate it. The two guards are orthogonal and BOTH are needed — this one
    /// rejects a live non-owner, the epoch fence rejects a deposed one. See [EntityOwnerAdmission].
    ///
    /// An admission-free ([Option#none]) configuration admits every write. That arm exists only for the
    /// un-wired factory used by fence unit tests, which exercise the epoch fence in isolation with no
    /// cluster and no ownership records; the node wiring can never take it, because
    /// [DurableEntityFactory] REFUSES to provision without the collaborators this gate is built from.
    private Result<Unit> admitWrite(K key) {
        return admission.fold(Result::unitResult, gate -> gate.admit(key));
    }

    /// Read and decode the current state for `key`, mapping any backing failure to a typed cause.
    ///
    /// The decode is LIFTED, not called inline. [Deserializer] signals failure by throwing — by design,
    /// since a codec miss is a configuration fault — and a throw escaping here would propagate out of the
    /// [PerKeySerialExecutor] tail, leaving the operation's promise unresolved: the caller hangs until its
    /// own timeout and the key's serialization tail is wedged for good. A hang is a strictly worse failure
    /// than a typed one, so the codec is treated as what it is, an adapter boundary.
    private Promise<Option<S>> readState(K key) {
        return storage.get(dhtKey(key))
                      .mapError(cause -> storageFailed(key, cause))
                      .flatMap(bytes -> decodeState(key, bytes));
    }

    private Promise<Option<S>> decodeState(K key, Option<byte[]> bytes) {
        return bytes.fold(() -> Promise.success(Option.none()),
                          raw -> Result.lift(throwable -> codecFailed(key, throwable),
                                             () -> Option.some(decode(raw)))
                                       .async());
    }

    /// Encode `next` and commit it under the KEY'S PARTITION owner-epoch fence, returning `next` on
    /// success. The stamp is the partition's current committed owner epoch; the engine's
    /// [org.pragmatica.aether.dht.PartitionOwnerEpochGate] rejects a strictly-older stamp with
    /// [DHTError.StaleEpochWrite], translated here to [DurableEntityError.StaleOwner]. Any other backing
    /// failure becomes [DurableEntityError.StorageFailed].
    private Promise<S> commit(K key, S next) {
        return Result.lift(throwable -> codecFailed(key, throwable),
                           () -> serializer.encode(next))
                     .async()
                     .flatMap(encoded -> commitEncoded(key, next, encoded));
    }

    /// The encode is LIFTED by [#commit] for the same reason the decode is (see [#readState]):
    /// [Serializer] throws on a codec miss, and a throw escaping the per-key tail hangs the caller instead
    /// of failing it. A state type the slice codec does not know now surfaces as
    /// [DurableEntityError.StorageFailed] naming the codec fault — which is also the honest report, since
    /// the entity genuinely cannot store that type.
    private Promise<S> commitEncoded(K key, S next, byte[] encoded) {
        var epoch = ownerEpoch.currentOwnerEpoch(arc.partitionOf(String.valueOf(key)));

        return storage.putVersioned(dhtKey(key),
                                    encoded,
                                    versionSequencer.incrementAndGet(),
                                    epoch.rabiaTerm(),
                                    epoch.localCounter())
                      .map(_ -> next)
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

    /// A codec fault for `key`'s state, rendered as a typed cause. [Serializer]/[Deserializer] report by
    /// throwing (their contract calls a codec miss a fatal misconfiguration), so this is the boundary
    /// where that exception becomes a value.
    private DurableEntityError codecFailed(K key, Throwable throwable) {
        return storageFailed(key, Causes.fromThrowable(throwable));
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
