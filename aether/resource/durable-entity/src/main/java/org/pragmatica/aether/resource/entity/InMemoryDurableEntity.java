// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Promise.promise;
import static org.pragmatica.lang.Promise.unitPromise;


/// HA-only, in-memory [DurableEntity] (plan Phase 2a/2b).
///
/// ## Per-key serialization (no locks, no threads)
///
/// State lives in `state` (an in-memory map). Operations serialize per key via `tails`: a map
/// from key to that key's current serialization tail [Promise]. Each submitted operation is
/// chained onto the key's tail with [Promise#fold] — so:
///
///   - **Same-key total order.** `tails.compute` is an atomic read-modify-write (under the bin
///     lock), so the new tail is appended after the current one; same-key operations run strictly
///     in submission order. The read-modify-write on `state` is therefore race-free without any
///     explicit lock.
///   - **Cross-key parallelism.** Different keys occupy different map entries (different bins), so
///     their tails advance independently and concurrently.
///   - **Non-poisoning.** [Promise#fold] runs the next operation regardless of the previous
///     operation's outcome (it ignores the prior [org.pragmatica.lang.Result]), so a failed
///     operation does not block or fail later operations on the same key.
///
/// Each operation is launched on the Promise executor (never run inside the `compute` bin lock —
/// see [#launch]), so the lock is held only for the cheap tail-append and the state mutation runs
/// later on an executor thread.
///
/// This idiom is the JBCT-native serial executor: `ConcurrentHashMap.compute` for atomic ordering
/// plus `Promise` chaining for sequencing — no `synchronized`, no raw locks, no raw threads.
///
/// @param <K> entity key type
/// @param <S> entity state type (immutable)
final class InMemoryDurableEntity<K, S> implements DurableEntity<K, S> {
    private final ConcurrentHashMap<K, S> state;
    private final ConcurrentHashMap<K, Promise<Object>> tails;

    private InMemoryDurableEntity() {
        this.state = new ConcurrentHashMap<>();
        this.tails = new ConcurrentHashMap<>();
    }

    static <K, S> DurableEntity<K, S> inMemoryDurableEntity() {
        return new InMemoryDurableEntity<>();
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
    ///
    /// The `tails.compute` call atomically appends the operation after the key's current tail, so
    /// same-key operations are totally ordered while different keys proceed in parallel. The
    /// returned [Promise] is the new tail itself, which resolves with this operation's result.
    private <R> Promise<R> submit(K key, Fn0<Promise<R>> operation) {
        return castResult(tails.compute(key, (_, previous) -> chainOnto(previous, operation)));
    }

    /// Build the new tail: run `operation` once `previous` resolves, regardless of its outcome.
    ///
    /// [Promise#fold] is used (not `flatMap`) so a failed predecessor does not short-circuit the
    /// successor — each queued operation runs in turn and reports its own result. When `previous`
    /// is `null` (first operation for this key) an already-resolved unit promise seeds the chain.
    ///
    /// The operation is [#launch]ed on the Promise executor rather than run inline: [Promise#fold]
    /// executes its action on the calling thread when `previous` is already resolved, and this
    /// chaining happens inside [ConcurrentHashMap#compute] (holding a bin lock). Launching keeps the
    /// `compute` call to a cheap pointer-swap — the state mutation runs later, off the bin lock, so
    /// different keys mutate concurrently. The tail is erased to [Promise]`<Object>` purely to
    /// sequence heterogeneous operations; the result type is recovered by [#castResult].
    private static <R> Promise<Object> chainOnto(Promise<Object> previous, Fn0<Promise<R>> operation) {
        return seed(previous).fold(_ -> launch(operation));
    }

    private static Promise<Object> seed(Promise<Object> previous) {
        return option(previous).or(erase(unitPromise()));
    }

    /// Launch `operation` on the Promise executor and bridge its outcome into the (erased) tail.
    /// The consumer passed to `promise(...)` runs asynchronously, so the state mutation never
    /// executes inside the [ConcurrentHashMap#compute] bin lock.
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
