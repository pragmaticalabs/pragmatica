// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

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
/// @param <K> entity key type
/// @param <S> entity state type (immutable)
final class InMemoryDurableEntity<K, S> implements DurableEntity<K, S> {
    private final ConcurrentHashMap<K, S> state;
    private final PerKeySerialExecutor<K> serializer;

    private InMemoryDurableEntity() {
        this.state = new ConcurrentHashMap<>();
        this.serializer = PerKeySerialExecutor.perKeySerialExecutor();
    }

    static <K, S> DurableEntity<K, S> inMemoryDurableEntity() {
        return new InMemoryDurableEntity<>();
    }

    @Override
    public Promise<S> create(K key, S initial) {
        return serializer.submit(key, () -> doCreate(key, initial));
    }

    @Override
    public Promise<Option<S>> get(K key) {
        return serializer.submit(key, () -> doGet(key));
    }

    @Override
    public Promise<S> update(K key, Fn1<S, S> mutator) {
        return serializer.submit(key, () -> doUpdate(key, mutator));
    }

    @Override
    public Promise<Unit> delete(K key) {
        return serializer.submit(key, () -> doDelete(key));
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
        return option(state.putIfAbsent(key, initial)).fold(() -> Promise.success(initial), _ -> keyAlreadyExists(key));
    }

    private Promise<Option<S>> doGet(K key) {
        return Promise.success(option(state.get(key)));
    }

    private Promise<S> doUpdate(K key, Fn1<S, S> mutator) {
        return option(state.get(key)).fold(() -> keyNotFound(key), current -> mutate(key, current, mutator));
    }

    /// Apply the pure mutator and commit the result. Runs only under the per-key serialization (the
    /// tail), so the read-modify-write is single-threaded for this key; the mutator therefore runs
    /// outside any map bin lock — different keys mutate concurrently.
    private Promise<S> mutate(K key, S current, Fn1<S, S> mutator) {
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
        return new DurableEntityError.KeyAlreadyExists(String.valueOf(key)).promise();
    }

    private static <S> Promise<S> keyNotFound(Object key) {
        return new DurableEntityError.KeyNotFound(String.valueOf(key)).promise();
    }
}
