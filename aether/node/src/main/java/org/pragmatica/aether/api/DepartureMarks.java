// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;


/// The planned-departure marks of one observer, and the order they keep against each life's DEAD edge (#3858 round 2,
/// invariant 1): a mark ends only when the life it was created for is over, it is created only while that life's DEAD
/// edge has not consumed it, and it is dropped early only on evidence that no DEAD edge of that life is pending or in
/// flight.
///
/// THE ENFORCER is this type. The marks, the record timestamps and the SETTLED set (the ids whose DEAD edge consumed
/// their mark on this observer) are private and change only through the operations below, and every operation is one
/// section of one monitor. So a producer thread (the ping handler, the leader's record tick), the age-out, the handshake
/// route and the DEAD edge's consumer cannot interleave inside an operation, and the question "did the consumer already
/// run?" is answered by the SETTLED set in the same section that writes the mark: there is no read of FSM state between
/// a check and a write. Before this type the producer read the FSM state `Dead`, which flips BEFORE the DEAD edge's
/// staged emissions run and tells nothing about whether the consumer has run, and then wrote on its own thread.
///
/// - [`#announceFromEdge`] and [`#announceFromRecord`] create a mark unless the id is SETTLED. A node the FSM holds `Dead`
///   with its edge still in flight is not settled, so a record that names it still marks it, and the consumer reads the mark.
/// - [`#settle`] is the consumer: it ends the mark AND records the id as settled, in one step. Nothing re-creates a mark
///   after it (R1), until the FSM shows the id as a live life again.
/// - A SETTLED id is released, under the same monitor, when the FSM no longer holds the node `Dead`: a rejoin (`Dead` ->
///   `Observed`) or a retired tracking. `Dead` is terminal for a life: it ends by a fenced rejoin or by the tracking leaving the
///   roster, and the old life's hooks finish under the tracking's guard before a rejoin dispatch can run, so a node the FSM does not
///   hold is a later life, and the
///   set stays at most the FSM's own DEAD entries.
/// - [`#expire`] drops a mark only after the omission grace, on `drainEnded` evidence, and never while the FSM holds the node's
///   life `Dead` or its DEAD edge in flight (`MembershipFsm#holdsDeadLife`: the state flips before the hooks run, and a scope change can
///   remove the tracking inside that window, so the FSM counts the dispatches that have not finished their hooks).
/// - [`#endOnHandshake`] drops a mark only when the caller proves no DEAD edge can come for the node (`MembershipFsm#noDeadEdgePending`:
///   untracked, and no dispatch in flight; the tracking is retired under its own guard before it leaves the roster), read inside the
///   monitor (A4). A handshake from a node the FSM still tracks proves nothing about its DEAD edge, so its mark stays for the consumer
///   or the age-out. Residual: the FSM can start tracking a node between that read and the removal; the mark lost then is a spurious
///   CRITICAL, never a suppressed one.
///
/// The predicates passed in are leaf reads of FSM or metrics maps; none of them calls back into this type, so the monitor
/// is never held across a call that could wait on it.
final class DepartureMarks {
    private final int cap;
    private final Map<NodeId, Long> marks = new HashMap<>();
    private final Map<NodeId, Long> recordSeen = new HashMap<>();
    private final Set<NodeId> settled = new HashSet<>();

    private DepartureMarks(int cap) {
        this.cap = cap;
    }

    static DepartureMarks departureMarks(int cap) {
        return new DepartureMarks(cap);
    }

    /// A mark from the local FSM's `DrainRequested` edge. That edge is raised by a live life, so the id is not settled.
    /// Bounded by the cap: when full, a new mark is DROPPED rather than evicting, so the failure is a spurious CRITICAL
    /// alert and never a suppressed one.
    @Contract
    synchronized void announceFromEdge(NodeId id, long nowMs) {
        settled.remove(id);
        putMark(id, nowMs);
    }

    /// A mark from a drain record. `holdsDead` says the observer's FSM still holds the node `Dead`: while it does and the id is
    /// settled, the life is over and nothing is created; once it does not, the id is a later life and is released first.
    @Contract
    synchronized void announceFromRecord(NodeId id, long nowMs, Predicate<NodeId> holdsDead) {
        releaseIfLaterLife(id, holdsDead);
        if (!settled.contains(id) && putMark(id, nowMs)) {
            recordSeen.put(id, nowMs);
        }
    }

    /// The DEAD edge's consumer: ends the mark and the stamp, and settles the id so nothing re-creates them. Returns whether
    /// a mark was held, which is whether the departure was planned.
    synchronized boolean settle(NodeId id) {
        recordSeen.remove(id);
        settled.add(id);

        return marks.remove(id) != null;
    }

    /// Drop the mark on a transport handshake, only when `noEdgePending` proves that no DEAD edge can come for the node.
    @Contract
    synchronized void endOnHandshake(NodeId id, Predicate<NodeId> noEdgePending) {
        if (noEdgePending.test(id)) {
            marks.remove(id);
            recordSeen.remove(id);
        }
    }

    /// Age out one id that the latest drain record does not name. A settled id the FSM no longer holds `Dead` is released.
    /// A record-created mark is dropped when the node has been absent for `graceMs`, `drainEnded` holds, and the FSM does not
    /// hold the node `Dead` (a `Dead` node with an unconsumed mark is the edge in flight).
    @Contract
    synchronized void expire(NodeId id,
                             long nowMs,
                             long graceMs,
                             Predicate<NodeId> drainEnded,
                             Predicate<NodeId> holdsDead) {
        releaseIfLaterLife(id, holdsDead);
        Option.option(recordSeen.get(id))
              .filter(lastSeen -> nowMs - lastSeen >= graceMs)
              .filter(_ -> drainEnded.test(id) && !holdsDead.test(id))
              .onPresent(_ -> dropMark(id));
    }

    /// Every id this observer holds state for: marks, record stamps and settled ids.
    synchronized Set<NodeId> known() {
        var all = new HashSet<>(marks.keySet());

        all.addAll(recordSeen.keySet());
        all.addAll(settled);

        return all;
    }

    synchronized boolean isMarked(NodeId id) {
        return marks.containsKey(id);
    }

    /// Distinct ids with a mark. A record stamp exists only while its mark does (every operation that removes one removes the other, in
    /// one monitor section), so the marks are the whole of the drain tracking. Settled ids are not drain tracking and are not counted.
    synchronized int trackedCount() {
        return marks.size();
    }

    private boolean putMark(NodeId id, long nowMs) {
        if (marks.size() >= cap && !marks.containsKey(id)) {
            return false;
        }

        marks.put(id, nowMs);

        return true;
    }

    private void dropMark(NodeId id) {
        marks.remove(id);
        recordSeen.remove(id);
    }

    private void releaseIfLaterLife(NodeId id, Predicate<NodeId> holdsDead) {
        if (settled.contains(id) && !holdsDead.test(id)) {
            settled.remove(id);
        }
    }
}
