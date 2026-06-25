// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import org.pragmatica.dht.DHTError;
import org.pragmatica.dht.OwnerEpochSource;
import org.pragmatica.dht.storage.StorageEngine;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Promise.promise;
import static org.pragmatica.lang.Promise.unitPromise;


/// Fenced, owner-stamped [DurableEntity] backed by a DHT [StorageEngine] (plan Phase 2b-i — the
/// spec's "first functional cut on the #345 fence"). State for a key is committed as a fenced DHT
/// entry: every write is stamped with this node's current owner [Epoch] (via [OwnerEpochSource]) and
/// rejected at the storage engine's commit point if that epoch is stale — so a deposed owner cannot
/// double-write across a governor handover (spec §4.2, §6).
///
/// ## What this cut delivers vs defers
///
///   - **Fenced single-writer correctness.** Commits go through [StorageEngine#putVersioned] with the
///     owner epoch; a stale-owner stamp is rejected with [DHTError.StaleEpochWrite], surfaced here as
///     [DurableEntityError.StaleOwner]. This is the split-brain guarantee spec §6 requires.
///   - **Per-key serialization.** Same-key operations are totally ordered via the same lock-free
///     tail-chaining idiom as [InMemoryDurableEntity]; different keys proceed in parallel.
///   - **HA, not restart-durable.** The engine is the in-memory `MemoryStorageEngine`; restart
///     durability is the fenced-log slice (spec §4.4 / plan Phase 3), behind this same API.
///   - **Single-replica, local-owner.** This cut commits to ONE engine and assumes it runs on the
///     owner; cross-node owner-routing of updates and quorum replication are later sub-slices (2b-ii)
///     — no general owner-routed invocation mechanism exists yet to run a mutator on a remote owner.
///
/// ## State representation
///
/// A key `K` is mapped to the DHT key bytes `keyspace + "/" + key` (UTF-8). The state `S` is encoded
/// with the injected [Serializer] and decoded with the [Deserializer]. A monotonic process-wide
/// version sequencer supplies the within-epoch HLC-LWW `version`; under the per-key tail each commit
/// for a key gets a strictly higher version than its predecessor, so the last committed write wins.
///
/// @param <K> entity key type — rendered to bytes via `String.valueOf` for the DHT key (first cut)
/// @param <S> entity state type — an application-defined immutable value, encoded via [Serializer]
final class FencedDurableEntity<K, S> implements DurableEntity<K, S> {
    private final StorageEngine storage;
    private final OwnerEpochSource ownerEpoch;
    private final Serializer serializer;
    private final Deserializer deserializer;
    private final String keyspace;
    private final AtomicLong versionSequencer;
    private final ConcurrentHashMap<K, Promise<Object>> tails;

    private FencedDurableEntity(StorageEngine storage,
                                OwnerEpochSource ownerEpoch,
                                Serializer serializer,
                                Deserializer deserializer,
                                String keyspace) {
        this.storage = storage;
        this.ownerEpoch = ownerEpoch;
        this.serializer = serializer;
        this.deserializer = deserializer;
        this.keyspace = keyspace;
        this.versionSequencer = new AtomicLong();
        this.tails = new ConcurrentHashMap<>();
    }

    static <K, S> DurableEntity<K, S> fencedDurableEntity(StorageEngine storage,
                                                          OwnerEpochSource ownerEpoch,
                                                          Serializer serializer,
                                                          Deserializer deserializer,
                                                          String keyspace) {
        return new FencedDurableEntity<>(storage, ownerEpoch, serializer, deserializer, keyspace);
    }

    @Override
    public Promise<S> create(K key, S initial) {
        return submit(key, () -> doCreate(key, initial));
    }

    @Override
    public Promise<Option<S>> get(K key) {
        return submit(key, () -> doGet(key));
    }

    @Override
    public Promise<S> update(K key, Fn1<S, S> mutator) {
        return submit(key, () -> doUpdate(key, mutator));
    }

    @Override
    public Promise<Unit> delete(K key) {
        return submit(key, () -> doDelete(key));
    }

    @Override
    public Promise<TimerToken> scheduleTimer(K key, Duration delay, Fn1<S, S> onFire) {
        return new DurableEntityError.TimerNotSupported(String.valueOf(key)).promise();
    }

    @Override
    public Promise<Unit> cancelTimer(K key, TimerToken token) {
        return new DurableEntityError.TimerNotSupported(String.valueOf(key)).promise();
    }

    /// Chain `operation` onto `key`'s serialization tail and return this operation's own outcome.
    /// Same idiom as [InMemoryDurableEntity#submit]: `tails.compute` atomically appends after the
    /// key's current tail, so same-key operations are totally ordered while different keys proceed in
    /// parallel. The read (decode) — fence-stamp — commit cycle for a key is therefore single-threaded
    /// and race-free without locks.
    private <R> Promise<R> submit(K key, Fn0<Promise<R>> operation) {
        return castResult(tails.compute(key, (_, previous) -> chainOnto(previous, operation)));
    }

    /// Build the new tail: run `operation` once `previous` resolves, regardless of its outcome.
    /// [Promise#fold] (not `flatMap`) keeps a failed predecessor from short-circuiting the successor,
    /// and [#launch] moves the operation off the [ConcurrentHashMap#compute] bin lock onto the Promise
    /// executor, so the lock holds only for the cheap tail-append.
    private static <R> Promise<Object> chainOnto(Promise<Object> previous, Fn0<Promise<R>> operation) {
        return seed(previous).fold(_ -> launch(operation));
    }

    private static Promise<Object> seed(Promise<Object> previous) {
        return option(previous).or(erase(unitPromise()));
    }

    private static <R> Promise<Object> launch(Fn0<Promise<R>> operation) {
        return promise(target -> erase(operation.apply()).onResult(target::resolve));
    }

    @SuppressWarnings("unchecked")
    private static <R> Promise<Object> erase(Promise<R> promise) {
        return (Promise<Object>) promise;
    }

    @SuppressWarnings("unchecked")
    private static <R> Promise<R> castResult(Promise<Object> tail) {
        return (Promise<R>) tail;
    }

    private Promise<S> doCreate(K key, S initial) {
        return readState(key).flatMap(existing -> existing.fold(() -> commit(key, initial), _ -> keyAlreadyExists(key)));
    }

    private Promise<Option<S>> doGet(K key) {
        return readState(key);
    }

    private Promise<S> doUpdate(K key, Fn1<S, S> mutator) {
        return readState(key).flatMap(current -> current.fold(() -> keyNotFound(key), state -> commit(key, mutator.apply(state))));
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

    /// Encode `next` and commit it under the owner-epoch fence, returning `next` on success. A stale
    /// owner stamp is rejected by [StorageEngine#putVersioned] with [DHTError.StaleEpochWrite] and
    /// translated to [DurableEntityError.StaleOwner]; any other backing failure becomes
    /// [DurableEntityError.StorageFailed].
    private Promise<S> commit(K key, S next) {
        return storage.putVersioned(dhtKey(key),
                                    serializer.encode(next),
                                    versionSequencer.incrementAndGet(),
                                    ownerEpoch.currentEpochTerm(),
                                    ownerEpoch.currentEpochCounter())
                      .map(_ -> next)
                      .mapError(cause -> translateCommitFailure(key, cause));
    }

    private Promise<Unit> removeState(K key) {
        return storage.remove(dhtKey(key)).mapToUnit().mapError(cause -> storageFailed(key, cause));
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
        return (keyspace + "/" + key).getBytes(StandardCharsets.UTF_8);
    }

    private static <S> Promise<S> keyAlreadyExists(Object key) {
        return new DurableEntityError.KeyAlreadyExists(String.valueOf(key)).promise();
    }

    private static <S> Promise<S> keyNotFound(Object key) {
        return new DurableEntityError.KeyNotFound(String.valueOf(key)).promise();
    }
}
