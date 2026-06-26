// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Promise;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.pragmatica.lang.Promise.promise;
import static org.pragmatica.lang.Promise.unitPromise;


/// Lock-free, thread-confined per-key serial executor — the JBCT-native serialization idiom shared by
/// every [DurableEntity] backing ([InMemoryDurableEntity], [FencedDurableEntity],
/// [PartitionFencedDurableEntity]). Operations on the SAME key run in strict submission order;
/// operations on DIFFERENT keys proceed concurrently. No `synchronized`, no raw locks, no raw threads.
///
/// ## How it works
/// A `tails` map holds, per key, an [AtomicReference] to that key's current serialization tail [Promise].
/// [#submit] publishes a fresh tail and appends the new operation onto the previous tail with a single
/// wait-free [AtomicReference#getAndSet] — the only mutation on the hot path:
///
///   - **Same-key total order.** `getAndSet` atomically installs this operation's result-promise as the
///     new tail and returns the previous tail in one indivisible step, so concurrent same-key submits are
///     linearized into a single chain — each operation runs strictly after its predecessor resolves, and
///     each operation's read-modify-write on its backing state is therefore race-free without any explicit
///     lock. There is no read-modify-write window for two submits to observe the same predecessor.
///   - **Cross-key parallelism.** Different keys occupy different map entries, each with its own
///     [AtomicReference], so their tails advance independently and concurrently.
///   - **Non-poisoning.** The successor is wired onto the predecessor via [Promise#fold] (not `flatMap`),
///     which runs regardless of the predecessor's outcome and ignores its [org.pragmatica.lang.Result], so
///     a failed operation does not block or fail later operations on the same key.
///
/// The hot path takes NO lock while allocating or chaining a [Promise]: the previous tail is swapped out by
/// an atomic `getAndSet`, and the operation is chained onto it (and launched on the Promise executor)
/// entirely off any map lock. The single [ConcurrentHashMap] touch per call is a lock-free
/// [ConcurrentHashMap#get]; [ConcurrentHashMap#computeIfAbsent] runs only once per key, to create that
/// key's [AtomicReference]. Each predecessor link is released the moment its dependent [Promise#fold]
/// action fires and resolves the published tail, so the chain never retains completed links (O(N) memory,
/// not O(N²)).
///
/// Idle-key cleanup of the `tails` map is intentionally NOT addressed here — it is a separate concern, left
/// to a future bounded-eviction pass; the map grows with the number of distinct keys, as before.
///
/// The tail is erased to [Promise]`<Object>` purely to sequence heterogeneous operations; the per-call
/// result type is recovered by [#castResult].
///
/// @param <K> key type — used only as a map key (equals/hashCode); never mutated
final class PerKeySerialExecutor<K> {
    private static final Promise<Object> SEED = erase(unitPromise());

    private final ConcurrentHashMap<K, AtomicReference<Promise<Object>>> tails;

    private PerKeySerialExecutor() {
        this.tails = new ConcurrentHashMap<>();
    }

    static <K> PerKeySerialExecutor<K> perKeySerialExecutor() {
        return new PerKeySerialExecutor<>();
    }

    /// Append `operation` onto `key`'s serialization tail and return this operation's own outcome. A single
    /// wait-free `getAndSet` publishes this operation's result-promise as the new tail and returns the
    /// previous tail, so same-key operations are totally ordered while different keys proceed in parallel.
    <R> Promise<R> submit(K key, Fn0<Promise<R>> operation) {
        var published = Promise.<Object>promise();
        var previous = tailRef(key).getAndSet(published);

        return castResult(chainOnto(previous, operation, published));
    }

    private AtomicReference<Promise<Object>> tailRef(K key) {
        return tails.computeIfAbsent(key, _ -> new AtomicReference<>(SEED));
    }

    /// Wire `operation` onto `previous` OUTSIDE any lock: once `previous` resolves (success or failure),
    /// launch `operation` on the Promise executor and forward its result onto the already-published tail.
    /// Both links are dependent [Promise#fold] actions — run synchronously in the predecessor's resolution
    /// drain, exactly once and in order — and `fold` (not `flatMap`) keeps a failed predecessor from
    /// short-circuiting the successor.
    private static <R> Promise<Object> chainOnto(Promise<Object> previous,
                                                 Fn0<Promise<R>> operation,
                                                 Promise<Object> published) {
        previous.fold(_ -> launch(operation))
                .fold(published::resolve);

        return published;
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
