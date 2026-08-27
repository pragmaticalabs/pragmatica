// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

import org.pragmatica.aether.dht.CommittedPartitionOwnerSource;
import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.slice.fence.OwnershipEpochHighWater;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.aether.resource.Mutator;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Promise.unitPromise;


/// HA-only, in-memory [DurableEntity] (plan Phase 2a/2b).
///
/// ## Per-key serialization (no locks, no threads)
///
/// State lives in `state` (an in-memory map). Operations serialize per key via a shared
/// [PerKeySerialExecutor]: same-key operations run in strict submission order (so the read-modify-write
/// on `state` is race-free without any explicit lock), while different keys proceed concurrently. See
/// [PerKeySerialExecutor] for the lock-free `AtomicReference.getAndSet` tail-swap + `Promise`-chaining idiom.
///
/// ## Read consistency (spec §8.1)
///
/// [#get(Object)] and [#get(Object, ReadConsistency)] with [ReadConsistency#BOUNDED_STALE] serve the
/// local committed-prefix read. [ReadConsistency#LINEARIZABLE] routes through an optional
/// [LinearizableEntityServe] (committed-owner routing + no-op round + post-round epoch fence); when that
/// component is absent — the no-arg form, and the single-owner in-memory cut generally — a
/// `LINEARIZABLE` read degrades to the local read, which on a single owner already reflects every
/// acknowledged write. The wired form ([#inMemoryDurableEntity(NodeId, EntityPartitionArc,
/// CommittedPartitionOwnerSource, Option, Option)]) is exercised by tests.
///
/// Neither form reaches a running node: [DurableEntityFactory] provisions only the fenced-log
/// [PartitionFencedDurableEntity] and refuses without its fence collaborators. This entity is
/// constructed directly, by tests and harnesses.
///
/// @param <K> entity key type
/// @param <S> entity state type (immutable)
final class InMemoryDurableEntity<K, S, C extends Mutator<S>> implements DurableEntity<K, S, C> {
    private final ConcurrentHashMap<K, S> state;
    private final PerKeySerialExecutor<K> serializer;
    private final Option<LinearizableEntityServe<K, S>> linearizableServe;

    private InMemoryDurableEntity() {
        this.state = new ConcurrentHashMap<>();
        this.serializer = PerKeySerialExecutor.perKeySerialExecutor();
        this.linearizableServe = Option.none();
    }

    private InMemoryDurableEntity(NodeId selfNodeId,
                                  EntityPartitionArc arc,
                                  CommittedPartitionOwnerSource committedOwnerSource,
                                  Option<OwnershipEpochHighWater> epochHighWater,
                                  Option<EntityLinearizableBarrier> barrier) {
        this.state = new ConcurrentHashMap<>();
        this.serializer = PerKeySerialExecutor.perKeySerialExecutor();
        this.linearizableServe = Option.some(LinearizableEntityServe.linearizableEntityServe(selfNodeId,
                                                                                             arc,
                                                                                             committedOwnerSource,
                                                                                             epochHighWater,
                                                                                             barrier,
                                                                                             this::get));
    }

    static <K, S, C extends Mutator<S>> DurableEntity<K, S, C> inMemoryDurableEntity() {
        return new InMemoryDurableEntity<>();
    }

    /// Linearizable-capable in-memory entity: a [ReadConsistency#LINEARIZABLE] read routes to the key's
    /// committed partition owner via [LinearizableEntityServe] (owner-serve pipeline over the shared
    /// ownership substrate). Constructed by tests; a node provisions the fenced-log entity instead.
    static <K, S, C extends Mutator<S>> DurableEntity<K, S, C> inMemoryDurableEntity(NodeId selfNodeId,
                                                                                     EntityPartitionArc arc,
                                                                                     CommittedPartitionOwnerSource committedOwnerSource,
                                                                                     Option<OwnershipEpochHighWater> epochHighWater,
                                                                                     Option<EntityLinearizableBarrier> barrier) {
        return new InMemoryDurableEntity<>(selfNodeId, arc, committedOwnerSource, epochHighWater, barrier);
    }

    @Override
    public Promise<S> create(K key, S initial) {
        return serializer.submit(key, () -> doCreate(key, initial));
    }

    @Override
    public Promise<Option<S>> get(K key) {
        return serializer.submit(key, () -> doGet(key));
    }

    /// [ReadConsistency#BOUNDED_STALE] serves the local read [#get]; [ReadConsistency#LINEARIZABLE] routes
    /// through the owner-serve pipeline when wired, else degrades to the local read.
    @Override
    public Promise<Option<S>> get(K key, ReadConsistency consistency) {
        return switch (consistency) {
            case BOUNDED_STALE -> get(key);
            case LINEARIZABLE -> readLinearizable(key);
        };
    }

    private Promise<Option<S>> readLinearizable(K key) {
        // Unwired fold-to-local is honest ONLY while this entity is process-local single-copy (one map,
        // one serialized writer => a local get is trivially linearizable). The moment the entity gains
        // cross-node replication (#349 / Phase 3), an unwired LINEARIZABLE read MUST become a named
        // rejection (LinearizableUnavailable), never a silent local read — revisit this fold there.
        return linearizableServe.fold(() -> get(key), serve -> serve.serve(key));
    }

    @Override
    public Promise<S> update(K key, C mutator) {
        return serializer.submit(key, () -> doUpdate(key, mutator));
    }

    @Override
    public Promise<Unit> delete(K key) {
        return serializer.submit(key, () -> doDelete(key));
    }

    @Override
    public Promise<TimerToken> scheduleTimer(K key, Duration delay, C onFire, TimerToken token) {
        return new EntityError.TimerNotSupported(String.valueOf(key)).promise();
    }

    @Override
    public Promise<Unit> cancelTimer(K key, TimerToken token) {
        return new EntityError.TimerNotSupported(String.valueOf(key)).promise();
    }

    private Promise<S> doCreate(K key, S initial) {
        return option(state.putIfAbsent(key, initial)).fold(() -> Promise.success(initial), _ -> keyAlreadyExists(key));
    }

    private Promise<Option<S>> doGet(K key) {
        return Promise.success(option(state.get(key)));
    }

    private Promise<S> doUpdate(K key, C mutator) {
        return option(state.get(key)).fold(() -> keyNotFound(key), current -> mutate(key, current, mutator));
    }

    /// Apply the pure mutator and commit the result. Runs only under the per-key serialization (the
    /// tail), so the read-modify-write is single-threaded for this key; the mutator therefore runs
    /// outside any map bin lock — different keys mutate concurrently.
    private Promise<S> mutate(K key, S current, C mutator) {
        return commit(key, mutator.apply(current));
    }

    private Promise<S> commit(K key, S next) {
        state.put(key, next);

        return Promise.success(next);
    }

    private Promise<Unit> doDelete(K key) {
        return option(state.remove(key)).fold(() -> keyNotFound(key), _ -> unitPromise());
    }

    private static <S> Promise<S> keyAlreadyExists(Object key) {
        return new EntityError.EntityAlreadyExists(String.valueOf(key)).promise();
    }

    private static <S> Promise<S> keyNotFound(Object key) {
        return new EntityError.EntityNotFound(String.valueOf(key)).promise();
    }
}
