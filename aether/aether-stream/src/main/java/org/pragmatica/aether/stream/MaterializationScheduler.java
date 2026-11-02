// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.pragmatica.aether.stream.StreamPartitionManager.PartitionRef;
import org.pragmatica.lang.Contract;


/// #2201 A-05 (spec 5.1): the materialization scheduling state of a [StreamPartitionManager], rows 20-28 of the spec table,
/// for the manager's lifetime. Moved, not rewritten: the manager's reconcile tick ([StreamPartitionManager#reconcileReshuffle])
/// reads and changes these exactly as before; each row stays individually concurrent, and none of them is per stream, so a
/// removed stream's refs do not go with it. As before: the next tick drops them from the queues, the in-flight and
/// held-unmaterialized sets and the slot tenure; the removal itself drops [#durableAtPreviousTick] (the manager's
/// `forgetHeldBack`); nothing drops them from [#releaseCandidacy] (a PR-2a finding, not changed here).
final class MaterializationScheduler {
    /// Partitions currently holding a reshuffle slot (materialize+backfill in flight). Membership set paired with the
    /// manager's reshuffle permits: `add` is the "acquire", `remove`+release is the "free". Swept each tick so a
    /// completed/lost partition frees its slot.
    private final Set<PartitionRef> inFlightMaterializations = ConcurrentHashMap.newKeySet();
    /// System-stream reshuffle queue (drains FIRST, owner decision 2026-07-05). Refs enqueued when no slot is free;
    /// dequeued on the reconcile tick as slots free.
    private final Deque<PartitionRef> systemMaterializeQueue = new ConcurrentLinkedDeque<>();
    /// App-stream reshuffle queue (drains AFTER the system queue, FIFO). A queued app partition proceeds only when a slot
    /// AND off-heap budget headroom both exist (budget-AND).
    private final Deque<PartitionRef> appMaterializeQueue = new ConcurrentLinkedDeque<>();
    /// Dedup set across both queues + a fast "is queued" membership test: a repeat materialize request for an
    /// already-queued partition is a no-op (idempotent, like the lazy-materialize path itself).
    private final Set<PartitionRef> queuedMaterializations = ConcurrentHashMap.newKeySet();
    /// First reconcile tick at which each partition was seen held (role OWNER/REPLICA) but unmaterialized (#1805).
    /// DIAGNOSTIC only: it times the WARN; the re-drive itself is level-triggered over the live state and needs no memory
    /// of past refusals. The clock restarts when the role goes NONE; entries are removed when the ring exists or the
    /// stream is gone.
    private final Map<PartitionRef, Long> heldUnmaterializedSince = new ConcurrentHashMap<>();
    /// Reconcile tick at which each in-flight partition ACQUIRED its slot, the input to starvation preemption. Removed
    /// when the slot is freed or preempted.
    private final Map<PartitionRef, Long> slotAcquiredTick = new ConcurrentHashMap<>();
    /// Partitions whose backfill is still running but which no longer hold a reshuffle slot, because they were preempted
    /// for starving the queue. Load-bearing for permit accounting, NOT bookkeeping: slot acquisition is idempotent VIA
    /// [#inFlightMaterializations] membership, so a preempted ref that re-entered the acquire path would take a SECOND
    /// permit and only ever release one, leaking the pool empty. A ref listed here reports its slot as already held and
    /// never takes another.
    private final Set<PartitionRef> preemptedSlots = ConcurrentHashMap.newKeySet();
    /// Release candidacy (#265 increment 5): a materialized partition whose role went NONE maps to the reconcile tick at
    /// which candidacy started. Debounced, then gated (catch-up + owner) before release. A role regained removes the
    /// entry (flap cancel).
    private final ConcurrentHashMap<PartitionRef, Long> releaseCandidacy = new ConcurrentHashMap<>();
    /// Durable bound each WAL-backed partition saw at the previous truncation tick, keyed `stream#partition`.
    private final ConcurrentHashMap<String, Long> durableAtPreviousTick = new ConcurrentHashMap<>();

    private MaterializationScheduler() {}

    static MaterializationScheduler materializationScheduler() {
        return new MaterializationScheduler();
    }

    Set<PartitionRef> inFlightMaterializations() {
        return inFlightMaterializations;
    }

    Deque<PartitionRef> systemMaterializeQueue() {
        return systemMaterializeQueue;
    }

    Deque<PartitionRef> appMaterializeQueue() {
        return appMaterializeQueue;
    }

    Set<PartitionRef> queuedMaterializations() {
        return queuedMaterializations;
    }

    Map<PartitionRef, Long> heldUnmaterializedSince() {
        return heldUnmaterializedSince;
    }

    Map<PartitionRef, Long> slotAcquiredTick() {
        return slotAcquiredTick;
    }

    Set<PartitionRef> preemptedSlots() {
        return preemptedSlots;
    }

    ConcurrentHashMap<PartitionRef, Long> releaseCandidacy() {
        return releaseCandidacy;
    }

    ConcurrentHashMap<String, Long> durableAtPreviousTick() {
        return durableAtPreviousTick;
    }

    /// What the manager's `close` drops: every row but the slot tenure ([#slotAcquiredTick], [#preemptedSlots]), as before.
    @Contract
    void clearOnClose() {
        durableAtPreviousTick.clear();
        inFlightMaterializations.clear();
        systemMaterializeQueue.clear();
        appMaterializeQueue.clear();
        queuedMaterializations.clear();
        heldUnmaterializedSince.clear();
        releaseCandidacy.clear();
    }
}
