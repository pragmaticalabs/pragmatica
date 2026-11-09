// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    }

    private final Map<Object, SharedEntry> entries = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Fn1<Promise<Unit>, Provisioned<?>> closer;
    private volatile TraceSink trace;

    /// Test-only trace hook for TLA+ trace validation (`tools/tla/trace.py`, spec `SharedPools`): `null` in production,
    /// where no event is built and no lock is taken. When set, each atomic region runs and records its event under the
    /// sink's monitor, taken inside any bin lock the region already holds (bin, then sink: the only order), so the
    /// recorded sequence is a linearization of the regions. Event names are the spec's action names.
    interface TraceSink {
        void event(Object... event);
    }

    void traceTo(TraceSink sink) {
        this.trace = sink;
    }

    private <R> R region(Fn0<R> body, Fn1<Object[], R> event) {
        var sink = trace;

        if (sink == null) {
            return body.apply();
        }
        synchronized (sink) {
            var result = body.apply();

            sink.event(event.apply(result));

            return result;
        }
    }

    private Unit note(Object... event) {
        return region(Unit::unit, _ -> event);
    }

    private static String outcome(Admission admission) {
        return admission.refused
               ? "refused"
               : admission.created
                 ? "insert"
                 : admission.newHolder
                   ? "add"
                   : "join";
    }

    private SharedPools(Fn1<Promise<Unit>, Provisioned<?>> closer) {
        this.closer = closer;
    }

    /// `closer` is the provider's one close funnel, shared with the per-slice path.
    static SharedPools sharedPools(Fn1<Promise<Unit>, Provisioned<?>> closer) {
        return new SharedPools(closer);
    }

    /// Adds `scope` as a holder of the pool under `key` and yields that holder's view. `provision` runs only when this
    /// call inserted the entry, after `compute` returned.
    ///
    /// ```tla SharedPools:Acq_Provision
    /// \* Contained (`Promise.unitPromise().flatMap(_ -> provision.apply())`): a factory that throws synchronously fails
    /// \* the pool like an asynchronous failure (Resolve's failure branch), so it is evicted instead of wedging the key.
    /// Acq_Provision(t) ==
    ///     /\ pc[t] = "provision"
    ///     /\ pc' = [pc EXCEPT ![t] = "wait"]
    ///     /\ inflight' = inflight \cup {tpool[t]}
    ///     /\ UNCHANGED <<entry, count, closed, poolSt, shared, closing, captures, closes, caVars, created, newHolder,
    ///                    tslot, tpool, sawClosed, afterClosedHit, slotSt, slotOf, nAcq, nPools, faults, staleHit,
    ///                    sliceCache, spc>>
    /// ```
    @SuppressWarnings("unchecked")
    <T> Promise<T> acquire(Object key,
                           String scope,
                           NodeShareable<T, ?> shareable,
                           Fn0<Promise<Provisioned<?>>> provision,
                           String label) {
        var admission = new Admission();

        entries.compute(key,
                        (_, current) -> region(() -> admit(current, scope, label, admission),
                                               _ -> new Object[]{"Acq_Admit", scope, key, outcome(admission),
                                                                 admission.created ? admission.entry.pool() : ""}));
        if (admission.refused) {
            return ResourceProvisioningError.ProviderClosed.INSTANCE.promise();
        }

        drainIfClosedMeanwhile(key, scope);
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
            // Contained: a factory that throws synchronously fails the pool (and is evicted) instead of leaving the
            // placeholder unresolved, which would wedge the key and every release waiting on it.
            Promise.unitPromise()
                   .flatMap(_ -> {
                       note("Acq_Provision", scope, key);

                       return provision.apply();
                   })
                   .onSuccess(_ -> LOG.log(System.Logger.Level.INFO, "opened shared pool {0} for {1}", label, scope))
                   .onResult(result -> {
                       note("Resolve", pool, result.isSuccess() ? "open" : "failed");
                       pool.resolve(result);
                   });
        }

        return attached.flatMap(_ -> slot);
    }

    /// The `compute` mapping function: refuses after node stop (keeping whatever is there, absent included), inserts
    /// the entry when absent, otherwise joins it.
    ///
    /// ```tla SharedPools:Acq_Admit
    /// \* One compute on the key: refuse after node stop, insert (this thread provisions), re-join, or join.
    /// Acq_Admit(t) ==
    ///     LET s == t[1]
    ///         k == t[2]
    ///         e == entry[k]
    ///         sl == nAcq + 1
    ///         p == nPools + 1
    ///     IN /\ pc[t] = "idle"
    ///        /\ nAcq < MaxAcquires
    ///        /\ nAcq' = nAcq + 1
    ///        /\ sawClosed' = [sawClosed EXCEPT ![t] = FALSE]
    ///        /\ IF closed
    ///           THEN /\ UNCHANGED pc
    ///                /\ UNCHANGED <<entry, poolSt, shared, nPools, slotSt, slotOf, created, newHolder, tslot, tpool>>
    ///           ELSE IF e.pool = 0
    ///           THEN /\ nPools < MaxPools
    ///                /\ entry' = [entry EXCEPT ![k] = [pool |-> p, holders |-> [NoHolders EXCEPT ![s] = sl]]]
    ///                /\ poolSt' = [poolSt EXCEPT ![p] = "pending"]
    ///                /\ shared' = shared \cup {p}
    ///                /\ nPools' = p
    ///                /\ slotSt' = [slotSt EXCEPT ![sl] = "pending"]
    ///                /\ slotOf' = [slotOf EXCEPT ![sl] = t]
    ///                /\ created' = [created EXCEPT ![t] = TRUE]
    ///                /\ newHolder' = [newHolder EXCEPT ![t] = TRUE]
    ///                /\ tslot' = [tslot EXCEPT ![t] = sl]
    ///                /\ tpool' = [tpool EXCEPT ![t] = p]
    ///                /\ pc' = [pc EXCEPT ![t] = "admitted"]
    ///           ELSE IF e.holders[s] # 0
    ///           THEN /\ created' = [created EXCEPT ![t] = FALSE]
    ///                /\ newHolder' = [newHolder EXCEPT ![t] = FALSE]
    ///                /\ tslot' = [tslot EXCEPT ![t] = e.holders[s]]
    ///                /\ tpool' = [tpool EXCEPT ![t] = e.pool]
    ///                /\ pc' = [pc EXCEPT ![t] = "post"]
    ///                /\ UNCHANGED <<entry, poolSt, shared, nPools, slotSt, slotOf>>
    ///           ELSE /\ entry' = [entry EXCEPT ![k].holders[s] = sl]
    ///                /\ slotSt' = [slotSt EXCEPT ![sl] = "pending"]
    ///                /\ slotOf' = [slotOf EXCEPT ![sl] = t]
    ///                /\ created' = [created EXCEPT ![t] = FALSE]
    ///                /\ newHolder' = [newHolder EXCEPT ![t] = TRUE]
    ///                /\ tslot' = [tslot EXCEPT ![t] = sl]
    ///                /\ tpool' = [tpool EXCEPT ![t] = e.pool]
    ///                /\ pc' = [pc EXCEPT ![t] = "post"]
    ///                /\ UNCHANGED <<poolSt, shared, nPools>>
    ///        /\ UNCHANGED <<count, closed, inflight, closing, captures, closes, caVars, faults, staleHit, sliceCache, spc,
    ///                       afterClosedHit>>
    /// ```
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

    /// Resolves the slot of the holder this thread added: the view on success, the pool's failure otherwise. It runs as
    /// a dependent of the pool, which is after `compute` returned, so outside the bin lock.
    ///
    /// ```tla SharedPools:Acq_Fill
    /// \* The holder's slot resolves from the pool: the view on success, the failure otherwise. A re-joining holder
    /// \* receives its existing slot.
    /// Acq_Fill(t) ==
    ///     LET p == tpool[t]
    ///         sl == tslot[t]
    ///         view == IF newHolder[t] THEN poolSt[p] # "failed" ELSE slotSt[sl] # "failed"
    ///     IN /\ pc[t] = "wait"
    ///        /\ IF newHolder[t]
    ///           THEN /\ poolSt[p] \in {"open", "failed", "closed"}
    ///                /\ slotSt' = [slotSt EXCEPT ![sl] = IF poolSt[p] = "failed" THEN "failed" ELSE "view"]
    ///           ELSE /\ slotSt[sl] # "pending"
    ///                /\ UNCHANGED slotSt
    ///        /\ afterClosedHit' = (afterClosedHit \/ (view /\ sawClosed[t]))
    ///        /\ pc' = [pc EXCEPT ![t] = "idle"]
    ///        /\ created' = [created EXCEPT ![t] = FALSE]
    ///        /\ newHolder' = [newHolder EXCEPT ![t] = FALSE]
    ///        /\ tslot' = [tslot EXCEPT ![t] = 0]
    ///        /\ tpool' = [tpool EXCEPT ![t] = 0]
    ///        /\ sawClosed' = [sawClosed EXCEPT ![t] = FALSE]
    ///        /\ UNCHANGED <<entry, count, closed, poolSt, shared, inflight, closing, captures, closes, caVars,
    ///                       slotOf, nAcq, nPools, faults, staleHit, sliceCache, spc>>
    /// ```
    @SuppressWarnings("unchecked")
    private <T> Promise<T> fillSlot(Promise<T> slot,
                                    Result<Provisioned<?>> result,
                                    NodeShareable<T, ?> shareable,
                                    Object key,
                                    String scope) {
        note("Acq_Fill", scope, key, result.isSuccess() ? "view" : "failed");

        return slot.resolve(result.map(provisioned -> shareable.view((T) provisioned.resource(),
                                                                     () -> releaseSlot(key, scope, slot))));
    }

    /// A failed pool was never opened, so it has nothing to close. Its holders all receive the failure, and the next
    /// acquire opens a fresh pool. Conditional on identity, so it can never evict a newer entry.
    ///
    /// ```tla SharedPools:Resolve
    /// \* The provision promise resolves (environment: the factory). On failure the eviction dependent removes the entry
    /// \* that still holds this pool (conditional on identity); the per-slice path's `withFailure` eviction likewise.
    /// Resolve(p) ==
    ///     /\ p \in inflight
    ///     /\ inflight' = inflight \ {p}
    ///     /\ \/ /\ poolSt' = [poolSt EXCEPT ![p] = "open"]
    ///           /\ UNCHANGED <<entry, count, faults, sliceCache>>
    ///        \/ /\ faults < MaxFaults
    ///           /\ faults' = faults + 1
    ///           /\ poolSt' = [poolSt EXCEPT ![p] = "failed"]
    ///           /\ entry' = [k \in Keys |-> IF entry[k].pool = p THEN NoEntry ELSE entry[k]]
    ///           /\ count' = [k \in Keys |-> IF entry[k].pool = p THEN count[k] - 1 ELSE count[k]]
    ///           /\ sliceCache' = [s \in Scopes |-> IF sliceCache[s] = p THEN 0 ELSE sliceCache[s]]
    ///     /\ UNCHANGED <<closed, shared, closing, captures, closes, caVars, thr, slotSt, slotOf, nAcq, nPools,
    ///                    staleHit, spc>>
    /// ```
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
        var departure = new Departure();

        entries.compute(key, (_, current) -> depart(current, scope, departure));

        return closeIfCaptured(departure, scope);
    }

    /// Release bound to the holding a view was issued for: a no-op when `scope` now holds a different slot (the view
    /// outlived its holding and the scope re-acquired) or no longer holds the key at all.
    Promise<Unit> releaseSlot(Object key, String scope, Promise<?> slot) {
        var departure = new Departure();

        entries.compute(key, (_, current) -> departIfHolding(current, scope, slot, departure));

        return closeIfCaptured(departure, scope);
    }

    /// The `compute` mapping function of a view's release: leaves only when `scope` still holds `slot`.
    ///
    /// ```tla SharedPools:ViewStop
    /// \* The view's stop runs releaseSlot(key, scope, slot): it leaves only the holding it was issued for. The ghost
    /// \* staleHit records a stop that changed a holding other than its own (implementation-independent).
    /// ViewStop(sl) ==
    ///     LET s == slotOf[sl][1]
    ///         k == slotOf[sl][2]
    ///     IN /\ slotSt[sl] = "view"
    ///        /\ slotSt' = [slotSt EXCEPT ![sl] = "stopped"]
    ///        /\ IF entry[k].holders[s] = sl
    ///           THEN Leave(k, s)
    ///           ELSE UNCHANGED <<entry, count, captures, closing>>
    ///        /\ staleHit' = (staleHit \/ (entry[k].holders[s] # sl /\ entry'[k].holders[s] # entry[k].holders[s]))
    ///        /\ UNCHANGED <<closed, poolSt, shared, inflight, closes, caVars, thr, slotOf, nAcq, nPools, faults,
    ///                       sliceCache, spc>>
    /// ```
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

    /// Store-then-load against `closeAll`'s flag: either the drain sees this acquirer's insert, or this acquirer sees
    /// the flag and drains its own key through the same capture.
    ///
    /// ```tla SharedPools:Acq_Post
    /// \* Runs after the admission compute returned: re-reads `closed` and, when set, drains this key through the same
    /// \* capture as closeAll. The ghost sawClosed records the read. Ruling (2026-11-09, NOT implemented at adb7a765da,
    /// \* enabled by the constant Ruling): an acquire that sees closed here fails with ProviderClosed, and a pool it
    /// \* created is resolved with that failure instead of being provisioned.
    /// Acq_Post(t) ==
    ///     LET k == t[2]
    ///         e == entry[k]
    ///         fail == Ruling /\ closed
    ///     IN /\ pc[t] = "post"
    ///        /\ sawClosed' = [sawClosed EXCEPT ![t] = closed /\ ~fail]
    ///        /\ IF closed /\ e.pool # 0
    ///           THEN /\ entry' = [entry EXCEPT ![k] = NoEntry]
    ///                /\ count' = [count EXCEPT ![k] = @ - 1]
    ///                /\ Capture(e.pool)
    ///           ELSE UNCHANGED <<entry, count, captures, closing>>
    ///        /\ IF fail
    ///           THEN /\ poolSt' = IF created[t] THEN [poolSt EXCEPT ![tpool[t]] = "failed"] ELSE poolSt
    ///                /\ slotSt' = IF newHolder[t] THEN [slotSt EXCEPT ![tslot[t]] = "failed"] ELSE slotSt
    ///                /\ pc' = [pc EXCEPT ![t] = "idle"]
    ///                /\ created' = [created EXCEPT ![t] = FALSE]
    ///                /\ newHolder' = [newHolder EXCEPT ![t] = FALSE]
    ///                /\ tslot' = [tslot EXCEPT ![t] = 0]
    ///                /\ tpool' = [tpool EXCEPT ![t] = 0]
    ///           ELSE /\ pc' = [pc EXCEPT ![t] = IF created[t] THEN "provision" ELSE "wait"]
    ///                /\ UNCHANGED <<poolSt, slotSt, created, newHolder, tslot, tpool>>
    ///        /\ UNCHANGED <<closed, shared, inflight, closes, caVars, afterClosedHit, slotOf, nAcq, nPools, faults,
    ///                       staleHit, sliceCache, spc>>
    /// ```
    private void drainIfClosedMeanwhile(Object key, String scope) {
        if (region(closed::get, seen -> seen ? new Object[0] : new Object[]{"Acq_Post", scope, key, "nodrain"})) {
            var late = new Departure();

            entries.compute(key,
                            (_, current) -> region(() -> drain(current, late),
                                                   _ -> new Object[]{"Acq_Post", scope, key,
                                                                     late.removed == null ? "nodrain" : "drained"}));
            option(late.removed).onPresent(this::close);
        }
    }

    /// The `compute` mapping function of a release: an absent key or a scope that is not a holder is left as it is.
    ///
    /// ```tla SharedPools:ReleaseAll
    /// \* releaseScope -> release(key, scope): one compute that removes the holder, capturing the entry when last.
    /// ReleaseAll(s, k) ==
    ///     /\ entry[k].holders[s] # 0
    ///     /\ Leave(k, s)
    ///     /\ UNCHANGED <<closed, poolSt, shared, inflight, closes, caVars, thr, slotSt, slotOf, nAcq, nPools, faults,
    ///                    staleHit, sliceCache, spc>>
    /// ```
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

    private Promise<Unit> closeIfCaptured(Departure departure, String scope) {
        return option(departure.removed).map(this::close)
                     .or(() -> logDeparture(departure, scope));
    }

    private Promise<Unit> logDeparture(Departure departure, String scope) {
        if (departure.wasHolder) {
            LOG.log(System.Logger.Level.INFO, "{0} left shared pool (holders={1})", scope, departure.remaining);
        }

        return Promise.unitPromise();
    }

    /// The shared-pool caller of the provider's one close funnel.
    ///
    /// ```tla SharedPools:Close_Pool
    /// \* pool.flatMap(closer): waits for the pool to resolve; closes an opened pool, skips a failed one.
    /// Close_Pool(p) ==
    ///     /\ p \in closing
    ///     /\ poolSt[p] \in {"open", "failed"}
    ///     /\ closing' = closing \ {p}
    ///     /\ IF poolSt[p] = "open"
    ///        THEN /\ poolSt' = [poolSt EXCEPT ![p] = "closed"]
    ///             /\ closes' = [closes EXCEPT ![p] = @ + 1]
    ///        ELSE UNCHANGED <<poolSt, closes>>
    ///     /\ UNCHANGED <<entry, count, closed, shared, inflight, captures, caVars, thr, slotSt, slotOf, nAcq, nPools,
    ///                    faults, staleHit, sliceCache, spc>>
    /// ```
    private Promise<Unit> close(SharedEntry entry) {
        return entry.pool()
                    .flatMap(provisioned -> {
                        note("Close_Pool", entry.pool());

                        return closer.apply(provisioned);
                    })
                    .onSuccess(_ -> LOG.log(System.Logger.Level.INFO,
                                            "closed shared pool {0}",
                                            entry.label()));
    }

    /// Releases `scope` from every pool it holds.
    List<Promise<Unit>> releaseScope(String scope) {
        var releases = new ArrayList<Promise<Unit>>();

        for (var entry : List.copyOf(entries.entrySet())) {
            if (entry.getValue().holders().containsKey(scope)) {
                releases.add(release(entry.getKey(), scope));
            }
        }

        return releases;
    }

    /// Node stop: refuse every later acquire, then drain. Only `compute`s already in flight can still insert once the
    /// flag is visible, and the loop catches them. Each pool closes exactly once: the drain uses the same capture as a
    /// release, never a plain `remove(key)`.
    ///
    /// ```tla SharedPools:CA_Flag
    /// CA_Flag == /\ ca = "idle"
    ///            /\ closed' = TRUE
    ///            /\ ca' = "check"
    ///            /\ UNCHANGED <<entry, count, poolSt, shared, inflight, closing, captures, closes, caSnap, caPools, thr,
    ///                           slotSt, slotOf, nAcq, nPools, faults, staleHit, sliceCache, spc>>
    /// ```
    ///
    /// ```tla SharedPools:CA_Check
    /// \* `while (!entries.isEmpty())` reads the published count (CHM sumCount, which a concurrent insert not yet
    /// \* counted can drive to <= 0 while another key is present); a pass snapshots the key set. ExactEmpty (NOT
    /// \* implemented at adb7a765da) is the candidate fix: loop until a key-set pass finds no key.
    /// CA_Check == /\ ca = "check"
    ///             /\ IF IF ExactEmpty THEN \A k \in Keys : entry[k].pool = 0 ELSE Total <= 0
    ///                THEN /\ ca' = "returned"
    ///                     /\ UNCHANGED caSnap
    ///                ELSE /\ caSnap' = {k \in Keys : entry[k].pool # 0}
    ///                     /\ ca' = IF caSnap' = {} THEN "check" ELSE "drain"
    ///             /\ UNCHANGED <<entry, count, closed, poolSt, shared, inflight, closing, captures, closes, caPools, thr,
    ///                            slotSt, slotOf, nAcq, nPools, faults, staleHit, sliceCache, spc>>
    /// ```
    List<Promise<Unit>> closeAll() {
        region(() -> {
                   closed.set(true);

                   return Unit.unit();
               },
               _ -> new Object[]{"CA_Flag"});
        var closes = new ArrayList<Promise<Unit>>();

        for (var pass = nextPass(); pass.isPresent(); pass = nextPass()) {
            for (var key : pass.unwrap()) {
                var departure = new Departure();

                entries.compute(key,
                                (_, current) -> region(() -> drain(current, departure),
                                                       _ -> new Object[]{"CA_Drain", key,
                                                                         departure.removed == null ? "empty" : "captured"}));
                option(departure.removed).onPresent(removed -> closes.add(close(removed)));
            }
        }

        return closes;
    }

    /// One pass of closeAll's loop: none when `isEmpty()` reads true, else the key-set snapshot to drain.
    private Option<List<Object>> nextPass() {
        return region(() -> entries.isEmpty()
                            ? Option.<List<Object>> none()
                            : Option.some(List.copyOf(entries.keySet())),
                      pass -> new Object[]{"CA_Check", pass.map(keys -> keys.isEmpty() ? "check" : "drain")
                                                          .or("returned")});
    }

    /// The drain's `compute` mapping function: captures the entry and removes it (`null` is the `compute` contract).
    ///
    /// ```tla SharedPools:CA_Drain
    /// CA_Drain(k) ==
    ///     /\ ca = "drain"
    ///     /\ k \in caSnap
    ///     /\ caSnap' = caSnap \ {k}
    ///     /\ ca' = IF caSnap' = {} THEN "check" ELSE "drain"
    ///     /\ IF entry[k].pool # 0
    ///        THEN /\ entry' = [entry EXCEPT ![k] = NoEntry]
    ///             /\ count' = [count EXCEPT ![k] = @ - 1]
    ///             /\ Capture(entry[k].pool)
    ///             /\ caPools' = caPools \cup {entry[k].pool}
    ///        ELSE UNCHANGED <<entry, count, captures, closing, caPools>>
    ///     /\ UNCHANGED <<closed, poolSt, shared, inflight, closes, thr, slotSt, slotOf, nAcq, nPools, faults, staleHit,
    ///                    sliceCache, spc>>
    /// ```
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
