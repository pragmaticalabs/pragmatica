// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Promise;

import java.util.concurrent.ConcurrentHashMap;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Promise.promise;
import static org.pragmatica.lang.Promise.unitPromise;


/// Lock-free, thread-confined per-key serial executor — the JBCT-native serialization idiom shared by
/// every [DurableEntity] backing ([InMemoryDurableEntity], [FencedDurableEntity],
/// [PartitionFencedDurableEntity]). Operations on the SAME key run in strict submission order;
/// operations on DIFFERENT keys proceed concurrently. No `synchronized`, no raw locks, no raw threads.
///
/// ## How it works
/// A `tails` map holds, per key, that key's current serialization tail [Promise]. [#submit] chains the
/// new operation onto the key's tail inside [ConcurrentHashMap#compute]:
///
///   - **Same-key total order.** `tails.compute` is an atomic read-modify-write under the bin lock, so
///     the new tail is appended after the current one — same-key operations run strictly in order, and
///     each operation's read-modify-write on its backing state is therefore race-free without any
///     explicit lock.
///   - **Cross-key parallelism.** Different keys occupy different map entries (different bins), so their
///     tails advance independently and concurrently.
///   - **Non-poisoning.** [Promise#fold] runs the next operation regardless of the previous operation's
///     outcome (it ignores the prior [org.pragmatica.lang.Result]), so a failed operation does not block
///     or fail later operations on the same key.
///
/// Each operation is [#launch]ed on the Promise executor — never run inside the `compute` bin lock — so
/// the lock holds only for the cheap tail-append while the actual operation runs later on an executor
/// thread. The tail is erased to [Promise]`<Object>` purely to sequence heterogeneous operations; the
/// per-call result type is recovered by [#castResult].
///
/// @param <K> key type — used only as a map key (equals/hashCode); never mutated
final class PerKeySerialExecutor<K> {
    private final ConcurrentHashMap<K, Promise<Object>> tails;

    private PerKeySerialExecutor() {
        this.tails = new ConcurrentHashMap<>();
    }

    static <K> PerKeySerialExecutor<K> perKeySerialExecutor() {
        return new PerKeySerialExecutor<>();
    }

    /// Chain `operation` onto `key`'s serialization tail and return this operation's own outcome. The
    /// `tails.compute` call atomically appends the operation after the key's current tail, so same-key
    /// operations are totally ordered while different keys proceed in parallel.
    <R> Promise<R> submit(K key, Fn0<Promise<R>> operation) {
        return castResult(tails.compute(key, (_, previous) -> chainOnto(previous, operation)));
    }

    /// Build the new tail: run `operation` once `previous` resolves, regardless of its outcome.
    /// [Promise#fold] (not `flatMap`) keeps a failed predecessor from short-circuiting the successor, and
    /// [#launch] moves the operation off the [ConcurrentHashMap#compute] bin lock onto the Promise
    /// executor so the lock holds only for the cheap tail-append.
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
}
