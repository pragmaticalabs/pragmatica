// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.pragmatica.aether.resource.SpiResourceProvider.Provisioned;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.NullReturn;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Promise.promise;


/// The node's shared pools (#2973): one entry per share key, holding the pool and its holders in ONE value.
///
/// **Every transition is one `ConcurrentHashMap.compute` on the key**, so per key they are totally ordered, and no
/// second structure sits beside the map (the #900 SF-2 shape). Inside `compute` the code only allocates unresolved
/// promises and copies an immutable holder map: it runs no factory code, neither `provision` nor `view`, so a factory or
/// a view that re-enters the provider cannot hit `Recursive update`.
///
/// - **acquire** adds the slice as a holder (a keyed set: the same slice acquiring twice counts once and receives the
///   same view). The thread that inserts the entry provisions the pool after `compute` returns; the thread that adds a
///   holder resolves that holder's view slot.
/// - **release** removes one holder. The mapping function captures the entry it removes when no holder remains, and only
///   a captured entry is closed: a `null` from `compute` also means "the key was absent", so it is never read as "I am
///   the closer". Each pool is therefore closed exactly once, by the release that removed its last holder.
/// - **closeAll** (node stop) sets `closed` first, so every later acquire refuses inside `compute`, then drains the map
///   with the same capture.
///
/// Guarantee and limit: a slice that holds a pool never has it closed by another slice's release. When an acquire races
/// the last holder's release and the release runs first, the old pool closes and the acquire opens a new one; the two
/// overlap briefly on the server.
final class SharedPools {
    private static final System.Logger LOG = System.getLogger(SharedPools.class.getName());

    /// The pool (resolved by the provisioning holder) and each holder's view slot. `holders` is immutable and replaced
    /// on every transition. `Provisioned` holds the underlying pool, never a view, so the close reaches the pool.
    record SharedEntry(Promise<Provisioned<?>> pool, Map<String, Promise<?>> holders, String label) {
        SharedEntry with(String scope, Promise<?> slot) {
            var next = new HashMap<>(holders);

            next.put(scope, slot);

            return new SharedEntry(pool, Map.copyOf(next), label);
        }

        /// The entry without `scope`, or `null` when no holder would remain (`compute` removes the key then).
        @NullReturn
        SharedEntry without(String scope) {
            var next = new HashMap<>(holders);

            next.remove(scope);

            return next.isEmpty()
                   ? null
                   : new SharedEntry(pool, Map.copyOf(next), label);
        }
    }

    /// What one `acquire` transition decided, written only by its own mapping function.
    private static final class Admission {
        private boolean refused;
        private boolean created;
        private boolean newHolder;
        private SharedEntry entry;
        private Promise<?> slot;
    }

    /// What one release transition captured, written only by its own mapping function.
    private static final class Departure {
        private SharedEntry removed;
        private int remaining;
        private boolean wasHolder;
        /// A release's claim on `closeAll`'s wait, registered before the removing `compute` (see [#departing]); absent for
        /// a drain, whose closes `closeAll` collects itself.
        private Promise<Unit> pending;
    }

    private final Map<Object, SharedEntry> entries = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    /// Every release or close in flight, whoever started it: `closeAll` must not report node stop done while a release is
    /// between removing its pool and finishing the close. A release registers its claim BEFORE its removing `compute`,
    /// so there is no moment when the pool has left the map and no claim stands for it; the claim leaves the set when the
    /// release settles (its close completed, or nothing was captured).
    private final Set<Promise<Unit>> pendingCloses = ConcurrentHashMap.newKeySet();
    private final Fn1<Promise<Unit>, Provisioned<?>> closer;

    private SharedPools(Fn1<Promise<Unit>, Provisioned<?>> closer) {
        this.closer = closer;
    }

    /// `closer` is the provider's one close funnel, shared with the per-slice path.
    static SharedPools sharedPools(Fn1<Promise<Unit>, Provisioned<?>> closer) {
        return new SharedPools(closer);
    }

    /// Adds `scope` as a holder of the pool under `key` and yields that holder's view. `provision` runs only when this
    /// call inserted the entry, after `compute` returned.
    <T> Promise<T> acquire(Object key,
                           String scope,
                           NodeShareable<T, ?> shareable,
                           Fn0<Promise<Provisioned<?>>> provision,
                           String label) {
        var admission = new Admission();

        entries.compute(key, (_, current) -> admit(current, scope, label, admission));
        if (admission.refused) {
            return ResourceProvisioningError.ProviderClosed.INSTANCE.promise();
        }

        return closed.get()
               ? refuseAfterAdmission(key, admission)
               : admitted(key, scope, shareable, provision, label, admission);
    }

    /// The acquire path after a transition that no node stop overtook: wires the eviction and the holder's slot, and
    /// provisions when this call created the entry.
    @SuppressWarnings("unchecked")
    private <T> Promise<T> admitted(Object key,
                                    String scope,
                                    NodeShareable<T, ?> shareable,
                                    Fn0<Promise<Provisioned<?>>> provision,
                                    String label,
                                    Admission admission) {
        var pool = admission.entry.pool();
        var slot = (Promise<T>) admission.slot;
        // The eviction is a DEPENDENT transform registered ahead of this holder's slot, so a caller observing the
        // failure finds the entry already gone and a retry provisions afresh (the SF-1 rule, as on the per-slice path).
        // Attached after `compute` returned: an already-failed pool applies it synchronously, and a `compute` inside a
        // `compute` throws `Recursive update`.
        var evicting = pool.withFailure(_ -> evictFailed(key, pool));
        var attached = admission.newHolder
                       ? evicting.withResult(result -> fillSlot(slot, result, shareable, key, scope))
                       : evicting;

        logAdmission(admission, label, scope);
        if (admission.created) {
            provisionUnlessStopped(pool, provision, label, scope);
        }

        return attached.flatMap(_ -> slot);
    }

    /// Opens the pool this call inserted, unless node stop began since the admission check: then the pool is resolved with
    /// `ProviderClosed` instead, so nothing is provisioned only to be closed by the drain. Contained: a factory that
    /// throws synchronously fails the pool (and is evicted) instead of leaving the placeholder unresolved, which would
    /// wedge the key and every release waiting on it.
    private Promise<Provisioned<?>> provisionUnlessStopped(Promise<Provisioned<?>> pool,
                                                           Fn0<Promise<Provisioned<?>>> provision,
                                                           String label,
                                                           String scope) {
        return closed.get()
               ? pool.resolve(ResourceProvisioningError.ProviderClosed.INSTANCE.<Provisioned<?>> result())
               : Promise.unitPromise()
                        .flatMap(_ -> provision.apply())
                        .onSuccess(_ -> LOG.log(System.Logger.Level.INFO, "opened shared pool {0} for {1}", label, scope))
                        .onResult(pool::resolve);
    }

    /// Store-then-load against `closeAll`'s flag; three cases, by where the acquirer's flag read falls.
    /// 1. The flag is already visible after the admission (this method): the acquirer fails with `ProviderClosed`, never
    ///    a view. When this call created the entry, its pool is resolved with `ProviderClosed` instead of being
    ///    provisioned, so the drain's close completes on a failed pool; holders that joined the entry meanwhile fail with
    ///    the same cause.
    /// 2. The flag is not yet visible, but the drain sees the insert: the drain registers its close on the pool before
    ///    this acquirer registers its holder slot, so the close runs first. [#fillSlot] re-reads the flag, which is always
    ///    set before the drain registers anything, and fails the slot with `ProviderClosed` instead of issuing a view of
    ///    a pool the drain has closed.
    /// 3. The flag is set after the view was issued: the holder was served from an open pool, which node stop closes.
    private <T> Promise<T> refuseAfterAdmission(Object key, Admission admission) {
        var late = departing();
        var refusal = ResourceProvisioningError.ProviderClosed.INSTANCE;

        if (admission.created) {
            admission.entry.pool().resolve(refusal.<Provisioned<?>> result());
        }

        entries.compute(key, (_, current) -> drain(current, late));
        option(late.removed).onPresent(removed -> close(removed).onResult(late.pending::resolve))
              .onEmpty(() -> late.pending.succeed(Unit.unit()));

        return refusal.promise();
    }

    /// The `compute` mapping function: refuses after node stop (keeping whatever is there, absent included), inserts
    /// the entry when absent, otherwise joins it.
    @NullReturn
    private SharedEntry admit(SharedEntry current, String scope, String label, Admission admission) {
        if (closed.get()) {
            admission.refused = true;

            return current;
        }

        return option(current).fold(() -> inserted(scope, label, admission),
                                    present -> joined(present, scope, admission));
    }

    private static SharedEntry inserted(String scope, String label, Admission admission) {
        var slot = promise();

        admission.created = true;
        admission.newHolder = true;
        admission.slot = slot;
        admission.entry = new SharedEntry(promise(), Map.of(scope, slot), label);

        return admission.entry;
    }

    private static SharedEntry joined(SharedEntry current, String scope, Admission admission) {
        if (current.holders().containsKey(scope)) {
            admission.slot = current.holders().get(scope);
            admission.entry = current;

            return current;
        }

        var slot = promise();

        admission.newHolder = true;
        admission.slot = slot;
        admission.entry = current.with(scope, slot);

        return admission.entry;
    }

    /// Resolves the slot of the holder this thread added: the view on success, the pool's failure otherwise, and
    /// `ProviderClosed` once node stop began (the pool may already be closed by the drain). It runs as
    /// a dependent of the pool, which is after `compute` returned, so outside the bin lock.
    @SuppressWarnings("unchecked")
    private <T> Promise<T> fillSlot(Promise<T> slot,
                                    Result<Provisioned<?>> result,
                                    NodeShareable<T, ?> shareable,
                                    Object key,
                                    String scope) {
        return slot.resolve(closed.get()
                            ? ResourceProvisioningError.ProviderClosed.INSTANCE.<T> result()
                            : result.map(provisioned -> shareable.view((T) provisioned.resource(),
                                                                       () -> releaseSlot(key, scope, slot))));
    }

    /// A failed pool was never opened, so it has nothing to close. Its holders all receive the failure, and the next
    /// acquire opens a fresh pool. Conditional on identity, so it can never evict a newer entry.
    private Unit evictFailed(Object key, Promise<Provisioned<?>> failed) {
        entries.compute(key, (_, current) -> unlessFailed(current, failed));

        return Unit.unit();
    }

    /// `null` (the `compute` contract for "remove") when `current` holds the failed pool; otherwise `current` unchanged.
    @NullReturn
    private static SharedEntry unlessFailed(SharedEntry current, Promise<Provisioned<?>> failed) {
        return option(current).filter(entry -> entry.pool() != failed)
                     .or((SharedEntry) null);
    }

    /// Removes `scope` as a holder of `key`; closes the pool when this removed the last holder. Idempotent: a scope that
    /// is not a holder, or a key that is absent, changes nothing and closes nothing.
    Promise<Unit> release(Object key, String scope) {
        var departure = departing();

        entries.compute(key, (_, current) -> depart(current, scope, departure));

        return closeIfCaptured(departure, scope);
    }

    /// Release bound to the holding a view was issued for: a no-op when `scope` now holds a different slot (the view
    /// outlived its holding and the scope re-acquired) or no longer holds the key at all.
    Promise<Unit> releaseSlot(Object key, String scope, Promise<?> slot) {
        var departure = departing();

        entries.compute(key, (_, current) -> departIfHolding(current, scope, slot, departure));

        return closeIfCaptured(departure, scope);
    }

    @NullReturn
    private static SharedEntry departIfHolding(SharedEntry current,
                                               String scope,
                                               Promise<?> slot,
                                               Departure departure) {
        return option(current).filter(entry -> entry.holders()
                                                    .get(scope) == slot)
                     .fold(() -> current,
                           entry -> leave(entry, scope, departure));
    }

    /// The `compute` mapping function of a release: an absent key or a scope that is not a holder is left as it is.
    @NullReturn
    private static SharedEntry depart(SharedEntry current, String scope, Departure departure) {
        return option(current).filter(entry -> entry.holders()
                                                    .containsKey(scope))
                     .fold(() -> current,
                           entry -> leave(entry, scope, departure));
    }

    /// The entry without `scope`, or `null` (remove) when `scope` was the last holder; the removed entry is captured.
    @NullReturn
    private static SharedEntry leave(SharedEntry current, String scope, Departure departure) {
        var next = current.without(scope);

        departure.wasHolder = true;
        departure.remaining = current.holders().size() - 1;
        if (current.holders().size() == 1) {
            departure.removed = current;
        }

        return next;
    }

    /// A release's (or a refused joiner's) departure, with its claim on `closeAll`'s wait already registered.
    private Departure departing() {
        var departure = new Departure();
        var claim = Promise.<Unit> promise();

        departure.pending = claim;
        pendingCloses.add(claim);
        claim.onResultRun(() -> pendingCloses.remove(claim));

        return departure;
    }

    private Promise<Unit> closeIfCaptured(Departure departure, String scope) {
        return option(departure.removed).map(this::close)
                     .or(() -> logDeparture(departure, scope))
                     .onResult(departure.pending::resolve);
    }

    private Promise<Unit> logDeparture(Departure departure, String scope) {
        if (departure.wasHolder) {
            LOG.log(System.Logger.Level.INFO, "{0} left shared pool (holders={1})", scope, departure.remaining);
        }

        return Promise.unitPromise();
    }

    /// The shared-pool caller of the provider's one close funnel.
    private Promise<Unit> close(SharedEntry entry) {
        var closing = entry.pool()
                           .flatMap(closer)
                           .onSuccess(_ -> LOG.log(System.Logger.Level.INFO,
                                                   "closed shared pool {0}",
                                                   entry.label()));

        pendingCloses.add(closing);

        return closing.onResultRun(() -> pendingCloses.remove(closing));
    }

    /// Releases `scope` from every pool it holds. The walk is over a table snapshot, not `List.copyOf`: that asks the
    /// view `isEmpty()`, the map's striped count, and could return nothing while the scope still holds a pool.
    List<Promise<Unit>> releaseScope(String scope) {
        var releases = new ArrayList<Promise<Unit>>();

        for (var entry : new ArrayList<>(entries.entrySet())) {
            if (entry.getValue().holders().containsKey(scope)) {
                releases.add(release(entry.getKey(), scope));
            }
        }

        return releases;
    }

    /// Node stop: refuse every later acquire, then drain. Only `compute`s already in flight can still insert once the
    /// flag is visible, and the loop catches them. Each pool closes exactly once: the drain uses the same capture as a
    /// release, never a plain `remove(key)`. The loop runs on key presence, never on `isEmpty()`: the map's count is a sum
    /// of striped cells, not a snapshot, so a concurrent insert and remove of another key can make it read zero while a
    /// held key is still present. The result also carries the closes a release started before the drain, still pending.
    List<Promise<Unit>> closeAll() {
        closed.set(true);
        var closes = new LinkedHashSet<Promise<Unit>>();

        for (var keys = presentKeys(); !keys.isEmpty(); keys = presentKeys()) {
            drainKeys(keys, closes);
        }

        closes.addAll(pendingCloses);

        return new ArrayList<>(closes);
    }

    /// The keys found by walking the table. Not `List.copyOf`: that asks the key view `isEmpty()`, which is the map's
    /// count again, and returns an empty list on the very read this drain must not trust.
    private List<Object> presentKeys() {
        return new ArrayList<>(entries.keySet());
    }

    private void drainKeys(List<Object> keys, Set<Promise<Unit>> closes) {
        for (var key : keys) {
            var departure = new Departure();

            entries.compute(key, (_, current) -> drain(current, departure));
            option(departure.removed).onPresent(removed -> closes.add(close(removed)));
        }
    }

    /// The drain's `compute` mapping function: captures the entry and removes it (`null` is the `compute` contract).
    @NullReturn
    private static SharedEntry drain(SharedEntry current, Departure departure) {
        departure.removed = current;

        return null;
    }

    /// The holders of `key`, for tests and logs; empty when the key is absent.
    Option<Integer> holderCount(Object key) {
        return option(entries.get(key)).map(entry -> entry.holders()
                                                          .size());
    }

    int size() {
        return entries.size();
    }

    private static Unit logAdmission(Admission admission, String label, String scope) {
        if (admission.newHolder && !admission.created) {
            LOG.log(System.Logger.Level.INFO,
                    "{0} joined shared pool {1} (holders={2})",
                    scope,
                    label,
                    admission.entry.holders().size());
        }

        return Unit.unit();
    }
}
