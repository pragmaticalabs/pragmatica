// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

/// Resource provisioning for slices.
///
/// The TLA+ model `SharedPools` (at adb7a765da) of the node-shared pools ([SharedPools]) and the per-slice path of
/// `SpiResourceProvider.provideShared` lives in doc-comment fragments: this skeleton carries the constants,
/// variables, helpers, `Init`, `Next`, fairness and the properties; each action sits on the code that implements it.
/// `tools/tla/check.sh SharedPools <variant> aether/resource/api/src/main/java` extracts and checks it.
///
/// ```tla SharedPools:head
/// EXTENDS Naturals, FiniteSets, TLC
///
/// \* Acquirer threads are (scope, key) pairs. A pool is a provision promise plus the resource it opens.
/// \* SlicePath enables the per-slice path (provideSlicePool) beside the shared one.
/// \* Ruling: an acquire that sees closed after admission fails with ProviderClosed (not implemented at adb7a765da).
/// \* ExactEmpty: closeAll loops until a key-set pass is empty instead of reading isEmpty() (not implemented).
/// CONSTANTS Keys, Scopes, MaxPools, MaxAcquires, MaxFaults, SlicePath, Ruling, ExactEmpty
///
/// Pools == 1..MaxPools
/// Slots == 1..MaxAcquires
/// Threads == Scopes \X Keys
/// NoHolders == [s \in Scopes |-> 0]
/// NoEntry == [pool |-> 0, holders |-> NoHolders]
///
/// VARIABLES
///     entry,       \* ConcurrentHashMap: key -> [pool, holders: scope -> slot]; pool 0 = absent, slot 0 = not a holder
///     count,       \* the map's published size per key: CHM's addCount runs after the mapping function returns
///     closed,      \* SharedPools.closed
///     poolSt,      \* pool -> "none" | "pending" | "open" | "failed" | "closed"
///     shared,      \* pools created by the shared path (the others are per-slice)
///     inflight,    \* pools whose provision promise is running and will resolve
///     closing,     \* captured pools whose close (pool.flatMap(closer)) has not completed
///     captures,    \* pool -> times a compute captured it for close
///     closes,      \* pool -> times the closer ran on it
///     ca,          \* closeAll phase: "idle" | "check" | "drain" | "returned"
///     caSnap,      \* closeAll: keys of the current drain pass (List.copyOf(entries.keySet()))
///     caPools,     \* pools closeAll captured; their closes are the promises it returns
///     pc,          \* acquirer: "idle" | "admitted" | "post" | "provision" | "wait" (locals reset when idle)
///     created,     \* acquirer: Admission.created
///     newHolder,   \* acquirer: Admission.newHolder
///     tslot,       \* acquirer: Admission.slot
///     tpool,       \* acquirer: Admission.entry.pool
///     sawClosed,   \* ghost: closed as read right after the admission compute returned
///     afterClosedHit, \* ghost: an acquire that read closed after admission yielded a view
///     slotSt,      \* slot -> "none" | "pending" | "view" | "failed" | "stopped"
///     slotOf,      \* slot -> <<scope, key>> it was issued for
///     nAcq, nPools, faults,
///     staleHit,    \* ghost: a view's stop removed a holding other than the one it was issued for
///     sliceCache,  \* per-slice path: scope -> pool (0 = absent)
///     spc          \* per-slice path: scope -> pool being provisioned (0 = none)
///
/// vars == <<entry, count, closed, poolSt, shared, inflight, closing, captures, closes, ca, caSnap, caPools,
///           pc, created, newHolder, tslot, tpool, sawClosed, afterClosedHit, slotSt, slotOf, nAcq, nPools, faults,
///           staleHit, sliceCache, spc>>
/// thr == <<pc, created, newHolder, tslot, tpool, sawClosed, afterClosedHit>>
/// caVars == <<ca, caSnap, caPools>>
///
/// Total == LET F[S \in SUBSET Keys] == IF S = {} THEN 0
///                                      ELSE LET x == CHOOSE y \in S : TRUE IN count[x] + F[S \ {x}]
///          IN F[Keys]
///
/// Capture(p) == /\ captures' = [captures EXCEPT ![p] = @ + 1]
///               /\ closing' = closing \cup {p}
///
/// \* SharedPools.leave: the entry without scope s, removed and captured when s was the last holder.
/// Leave(k, s) == LET e == entry[k]
///                    rest == [e.holders EXCEPT ![s] = 0]
///                IN IF rest = NoHolders
///                   THEN /\ entry' = [entry EXCEPT ![k] = NoEntry]
///                        /\ count' = [count EXCEPT ![k] = @ - 1]
///                        /\ Capture(e.pool)
///                   ELSE /\ entry' = [entry EXCEPT ![k].holders = rest]
///                        /\ UNCHANGED <<count, captures, closing>>
///
/// \* ConcurrentHashMap internals: the inserting thread publishes the count after compute returns.
/// Acq_Publish(t) == /\ pc[t] = "admitted"
///                   /\ count' = [count EXCEPT ![t[2]] = @ + 1]
///                   /\ pc' = [pc EXCEPT ![t] = "post"]
///                   /\ UNCHANGED <<entry, closed, poolSt, shared, inflight, closing, captures, closes, caVars,
///                                  created, newHolder, tslot, tpool, sawClosed, afterClosedHit, slotSt, slotOf,
///                                  nAcq, nPools, faults, staleHit, sliceCache, spc>>
/// ```
///
/// ```tla SharedPools:tail
/// Init == /\ entry = [k \in Keys |-> NoEntry]
///         /\ count = [k \in Keys |-> 0]
///         /\ closed = FALSE
///         /\ poolSt = [p \in Pools |-> "none"]
///         /\ shared = {}
///         /\ inflight = {}
///         /\ closing = {}
///         /\ captures = [p \in Pools |-> 0]
///         /\ closes = [p \in Pools |-> 0]
///         /\ ca = "idle"
///         /\ caSnap = {}
///         /\ caPools = {}
///         /\ pc = [t \in Threads |-> "idle"]
///         /\ created = [t \in Threads |-> FALSE]
///         /\ newHolder = [t \in Threads |-> FALSE]
///         /\ tslot = [t \in Threads |-> 0]
///         /\ tpool = [t \in Threads |-> 0]
///         /\ sawClosed = [t \in Threads |-> FALSE]
///         /\ afterClosedHit = FALSE
///         /\ slotSt = [sl \in Slots |-> "none"]
///         /\ slotOf = [sl \in Slots |-> CHOOSE t \in Threads : TRUE]
///         /\ nAcq = 0
///         /\ nPools = 0
///         /\ faults = 0
///         /\ staleHit = FALSE
///         /\ sliceCache = [s \in Scopes |-> 0]
///         /\ spc = [s \in Scopes |-> 0]
///
/// \* Environment: acquires, slice unloads (releaseAll), view stops, the per-slice path's callers.
/// EnvNext == \/ \E t \in Threads : Acq_Admit(t)
///            \/ \E s \in Scopes, k \in Keys : ReleaseAll(s, k)
///            \/ \E sl \in Slots : ViewStop(sl)
///            \/ \E s \in Scopes : SP_Insert(s) \/ SP_Release(s)
///
/// SysNext == \/ \E t \in Threads : Acq_Publish(t) \/ Acq_Post(t) \/ Acq_Provision(t) \/ Acq_Fill(t)
///            \/ \E p \in Pools : Resolve(p) \/ Close_Pool(p)
///            \/ CA_Flag \/ CA_Check \/ \E k \in Keys : CA_Drain(k)
///            \/ \E s \in Scopes : SP_Provision(s)
///
/// Next == EnvNext \/ SysNext
///
/// \* Node stop (closeAll) eventually happens, and every started step eventually runs.
/// Fairness == /\ WF_vars(CA_Flag) /\ WF_vars(CA_Check) /\ \A k \in Keys : WF_vars(CA_Drain(k))
///             /\ \A t \in Threads : WF_vars(Acq_Publish(t)) /\ WF_vars(Acq_Post(t))
///                                   /\ WF_vars(Acq_Provision(t)) /\ WF_vars(Acq_Fill(t))
///             /\ \A p \in Pools : WF_vars(Resolve(p)) /\ WF_vars(Close_Pool(p))
///             /\ \A s \in Scopes : WF_vars(SP_Provision(s))
///
/// Spec == Init /\ [][Next]_vars /\ Fairness
///
/// \* Keys and scopes are interchangeable model values (safety runs only; symmetry is unsound for liveness).
/// Perms == Permutations(Keys) \cup Permutations(Scopes)
///
/// \* -- Properties --
/// Provisioning(p) == \/ p \in inflight
///                    \/ \E t \in Threads : tpool[t] = p /\ created[t] /\ pc[t] \in {"admitted", "post", "provision"}
///                    \/ \E s \in Scopes : spc[s] = p
///
/// \* No key is wedged: a pending pool in the map is always on its way to resolving.
/// NoWedge == /\ \A k \in Keys : entry[k].pool # 0 /\ poolSt[entry[k].pool] = "pending" => Provisioning(entry[k].pool)
///            /\ \A s \in Scopes : sliceCache[s] # 0 /\ poolSt[sliceCache[s]] = "pending" => Provisioning(sliceCache[s])
///
/// \* A view's stop never removes a holding other than the one it was issued for.
/// NoStaleRelease == ~staleHit
///
/// \* An acquire that sees closed after its admission never yields a usable handle (the ruling's ProviderClosed).
/// NoHandleAfterClosed == ~afterClosedHit
///
/// \* Every opened pool is captured and closed at most once.
/// ExactlyOnce == \A p \in Pools : captures[p] <= 1 /\ closes[p] <= 1
///
/// \* closeAll has completed: its loop returned and every close it returned has completed.
/// CaComplete == ca = "returned" /\ caPools \cap closing = {}
///
/// \* No shared pool is open after closeAll completes, unless a close is already pending on it (no leak).
/// NoLeakAfterStop == CaComplete => \A p \in shared : poolSt[p] = "open" => p \in closing
///
/// \* The strict form: no shared pool open at all once closeAll completed.
/// NoOpenAfterStop == CaComplete => \A p \in shared : poolSt[p] # "open"
///
/// \* Liveness: closeAll completes, and every shared pool ends failed or closed (opened => closed).
/// CloseAllCompletes == <>CaComplete
/// AllSharedSettle == <>[](\A p \in shared : poolSt[p] \in {"failed", "closed"})
/// ```
///
/// ```tlacfg SharedPools:common
/// CONSTANTS Keys = {k1, k2}
///           Scopes = {a, b}
///           MaxPools = 3
///           MaxAcquires = 4
///           MaxFaults = 1
/// SPECIFICATION Spec
/// ```
///
/// ```tlacfg SharedPools:fixed
/// SYMMETRY Perms
/// CONSTANT SlicePath = FALSE
/// CONSTANT Ruling = FALSE
/// CONSTANT ExactEmpty = FALSE
/// INVARIANT NoWedge NoStaleRelease ExactlyOnce NoLeakAfterStop
/// ```
///
/// ```tlacfg SharedPools:fixedexact
/// SYMMETRY Perms
/// CONSTANT SlicePath = FALSE
/// CONSTANT Ruling = FALSE
/// CONSTANT ExactEmpty = TRUE
/// INVARIANT NoWedge NoStaleRelease ExactlyOnce NoLeakAfterStop
/// ```
///
/// ```tlacfg SharedPools:fixedslice
/// SYMMETRY Perms
/// CONSTANT SlicePath = TRUE
/// CONSTANT Ruling = FALSE
/// CONSTANT ExactEmpty = TRUE
/// INVARIANT NoWedge NoStaleRelease ExactlyOnce NoLeakAfterStop
/// ```
///
/// ```tlacfg SharedPools:afterclosed
/// SYMMETRY Perms
/// CONSTANT SlicePath = FALSE
/// CONSTANT Ruling = FALSE
/// CONSTANT ExactEmpty = TRUE
/// INVARIANT NoHandleAfterClosed
/// ```
///
/// ```tlacfg SharedPools:ruling
/// SYMMETRY Perms
/// CONSTANT SlicePath = FALSE
/// CONSTANT Ruling = TRUE
/// CONSTANT ExactEmpty = TRUE
/// INVARIANT NoWedge NoStaleRelease NoHandleAfterClosed ExactlyOnce NoLeakAfterStop
/// ```
///
/// ```tlacfg SharedPools:strict
/// SYMMETRY Perms
/// CONSTANT SlicePath = FALSE
/// CONSTANT Ruling = TRUE
/// CONSTANT ExactEmpty = TRUE
/// INVARIANT NoOpenAfterStop
/// ```
///
/// ```tlacfg SharedPools:live
/// CONSTANT SlicePath = FALSE
/// CONSTANT Ruling = FALSE
/// CONSTANT ExactEmpty = FALSE
/// PROPERTY CloseAllCompletes AllSharedSettle
/// ```
///
/// ```tlacfg SharedPools:liveruling
/// CONSTANT SlicePath = FALSE
/// CONSTANT Ruling = TRUE
/// CONSTANT ExactEmpty = TRUE
/// PROPERTY CloseAllCompletes AllSharedSettle
/// ```
package org.pragmatica.aether.resource;
