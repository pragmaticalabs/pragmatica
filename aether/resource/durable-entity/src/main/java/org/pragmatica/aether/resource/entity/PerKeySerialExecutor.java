// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Promise.promise;
import static org.pragmatica.lang.Promise.unitPromise;


/// Lock-free, thread-confined per-key serial executor — the JBCT-native serialization idiom shared by
/// every [DurableEntity] backing: the production [PartitionFencedDurableEntity] and the test-only
/// `InMemoryDurableEntity` / `FencedDurableEntity` fixtures (#1270). Operations on the SAME key run in strict submission order;
/// operations on DIFFERENT keys proceed concurrently. No `synchronized`, no raw locks, no raw threads.
///
/// ## How it works
/// A `tails` map holds, per key, an [AtomicReference] to that key's current serialization tail [Promise].
/// [#submit] publishes a fresh tail and appends the new operation onto the previous tail with a single
/// successful [AtomicReference#compareAndSet] — the only mutation on the hot path:
///
///   - **Same-key total order.** The compare-and-set atomically installs this operation's result-promise as
///     the new tail in place of the previous tail it read, so concurrent same-key submits are linearized
///     into a single chain — each operation runs strictly after its predecessor resolves, and each
///     operation's read-modify-write on its backing state is therefore race-free without any explicit
///     lock. A submit whose compare-and-set loses re-reads the tail and retries, so no two submits can
///     chain onto the same predecessor.
///   - **Cross-key parallelism.** Different keys occupy different map entries, each with its own
///     [AtomicReference], so their tails advance independently and concurrently.
///   - **Non-poisoning.** The successor is wired onto the predecessor via [Promise#fold] (not `flatMap`),
///     which runs regardless of the predecessor's outcome and ignores its [org.pragmatica.lang.Result], so
///     a failed operation does not block or fail later operations on the same key.
///
/// The hot path takes NO lock while allocating or chaining a [Promise]: the tail is swapped by a
/// compare-and-set, and the operation is chained onto the previous tail (and launched on the Promise
/// executor) entirely off any map lock. The map touch per call is [ConcurrentHashMap#computeIfAbsent]: it
/// returns an entry heading its bin without locking, and takes that bin's lock only to create an entry —
/// once per idle-to-busy transition of a key, now that idle entries are retired — or to search a collided
/// bin. Each predecessor link is released
/// the moment its dependent [Promise#fold] action fires and resolves the published tail, so the chain never
/// retains completed links (O(N) memory, not O(N²)).
///
/// ## Idle keys are retired (#1242)
/// An entry exists only while its key has an operation queued or running. Once an operation's tail
/// resolves and it is STILL the key's tail — nothing was submitted behind it — the entry is retired: the
/// tail is compare-and-set to the [#RETIRED] sentinel and the entry removed. Without this the map held one
/// entry per key ever submitted, each retaining its last operation's resolved promise and therefore its
/// RESULT, so memory grew with distinct keys × result size for the life of the entity.
///
/// The sentinel is what keeps retirement from breaking the total order. Retiring by a bare
/// `remove(key, ref)` after checking the tail is two steps: a submit landing between them chains onto the
/// removed reference, and the NEXT submit creates a fresh one and runs concurrently with it. With the
/// sentinel, retirement and a submit contend for the SAME compare-and-set on the SAME reference. A submit
/// that wins leaves the tail no longer the retiring operation's, so retirement does nothing; a retirement
/// that wins makes every later submit see [#RETIRED], drop the dead entry, and start on a fresh one — whose
/// [#SEED] predecessor is correct, because the retired chain has fully resolved.
///
/// The tail is erased to [Promise]`<Object>` purely to sequence heterogeneous operations; the per-call
/// result type is recovered by [#castResult].
///
/// @param <K> key type — used only as a map key (equals/hashCode); never mutated
final class PerKeySerialExecutor<K> {
    private static final Promise<Object> SEED = erase(unitPromise());
    /// Marks a retired entry. Never resolved and never chained onto: a submit that reads it discards the
    /// entry instead.
    private static final Promise<Object> RETIRED = promise();

    private final ConcurrentHashMap<K, AtomicReference<Promise<Object>>> tails;

    /// See [#tailReadProbe(Runnable)]. Deliberately NOT volatile: it is set before any concurrent submit
    /// starts, and `Thread.start` publishes it.
    private Runnable tailReadProbe = () -> {};

    private PerKeySerialExecutor() {
        this.tails = new ConcurrentHashMap<>();
    }

    static <K> PerKeySerialExecutor<K> perKeySerialExecutor() {
        return new PerKeySerialExecutor<>();
    }

    /// Append `operation` onto `key`'s serialization tail and return this operation's own outcome. A
    /// compare-and-set publishes this operation's result-promise as the new tail in place of the previous
    /// tail, so same-key operations are totally ordered while different keys proceed in parallel.
    <R> Promise<R> submit(K key, Fn0<Promise<R>> operation) {
        var published = Promise.<Object> promise();

        while (true) {
            var ref = tailRef(key);
            var previous = ref.get();

            if (previous == RETIRED) {
                tails.remove(key, ref);
            } else if (installAfterProbe(ref, previous, published)) {
                return castResult(chainOnto(key, ref, previous, operation, published));
            }
        }
    }

    /// The install is a compare-and-set against the tail just READ, never a `getAndSet`: between the read
    /// and the install the key's previous operation can finish and retire the entry, and a `getAndSet`
    /// would then chain this operation onto [#RETIRED] — a promise that never resolves — hanging it and
    /// every later operation on the key. A failed compare-and-set re-reads instead.
    private boolean installAfterProbe(AtomicReference<Promise<Object>> ref,
                                      Promise<Object> previous,
                                      Promise<Object> published) {
        tailReadProbe.run();

        return ref.compareAndSet(previous, published);
    }

    /// Test-only seam (#1242 review): runs in [#submit] after the tail is read and before it is replaced,
    /// so a test can retire the key inside that window deterministically. Production never touches it.
    @Contract
    void tailReadProbe(Runnable probe) {
        tailReadProbe = probe;
    }

    /// Package-private test hook: how many keys currently hold an entry.
    int trackedKeys() {
        return tails.size();
    }

    private AtomicReference<Promise<Object>> tailRef(K key) {
        return tails.computeIfAbsent(key, _ -> new AtomicReference<>(SEED));
    }

    /// Wire `operation` onto `previous` OUTSIDE any lock: once `previous` resolves (success or failure),
    /// launch `operation` on the Promise executor, forward its result onto the already-published tail, and
    /// then retire the key if nothing queued behind it. Both links are dependent [Promise#fold] actions —
    /// run synchronously in the predecessor's resolution drain, exactly once and in order — and `fold`
    /// (not `flatMap`) keeps a failed predecessor from short-circuiting the successor.
    @SuppressWarnings("JBCT-RET-07")
    private <R> Promise<Object> chainOnto(K key,
                                          AtomicReference<Promise<Object>> ref,
                                          Promise<Object> previous,
                                          Fn0<Promise<R>> operation,
                                          Promise<Object> published) {
        previous.fold(_ -> launch(operation)).fold(result -> settle(key, ref, published, result));

        return published;
    }

    /// Resolve the published tail FIRST, so a successor chained onto it launches exactly as before, and only
    /// then try to retire the key. The retirement therefore lands just after the caller's promise resolves —
    /// a caller that looks at the map the instant its operation completes may still see the entry.
    private Promise<Object> settle(K key,
                                   AtomicReference<Promise<Object>> ref,
                                   Promise<Object> published,
                                   Result<Object> result) {
        published.resolve(result);

        return retireIfIdle(key, ref, published);
    }

    /// Retire `key`'s entry if `published` is still its tail. The compare-and-set to [#RETIRED] is the whole
    /// decision; the map removal after it only tidies, and a submit that reads the sentinel first does it
    /// instead.
    private Promise<Object> retireIfIdle(K key, AtomicReference<Promise<Object>> ref, Promise<Object> published) {
        if (ref.compareAndSet(published, RETIRED)) {
            tails.remove(key, ref);
        }

        return published;
    }

    private static <R> Promise<Object> launch(Fn0<Promise<R>> operation) {
        return promise(target -> erase(started(operation)).onResult(target::resolve));
    }

    /// An operation that THROWS instead of returning a failed promise becomes a failed promise here
    /// (#1268). Unlifted, the throw escaped the launch task, the operation's promise was never resolved,
    /// and every later operation on the key — chained behind it — never ran.
    private static <R> Promise<R> started(Fn0<Promise<R>> operation) {
        return Result.lift(operation::apply).fold(Cause::promise, promise -> promise);
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
