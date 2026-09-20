// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.stream;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.pragmatica.aether.node.stream.StreamConsumerManager.PartitionAssignment;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConsumerAssignmentKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerAssignmentValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Option;


/// Leader-only, idempotent writer of the committed consumer assignment per `(stream, group, partition)`
/// (#1271) — the consumer-group mirror of `StreamPartitionOwnershipWriter` (#345).
///
/// ## Why a committed record
/// Every node used to decide which consumer partitions it delivers from its OWN view of ownership,
/// membership and placement, on its own unsynchronized tick. While two nodes' views disagreed, both
/// attached one `(group, partition)`, both delivered, and both wrote its cursor. The committed record is
/// the one authority: a node attaches only when the record names it, and the consensus applier admits a
/// cursor checkpoint only from the assignee the record names (`StreamCursorCheckpointKey` is
/// `AssignmentGuarded` by [ConsumerAssignmentKey]).
///
/// ## What it writes
/// The leader computes today's two-rule assignment (the HRW owner when it can run the slice, else HRW
/// over the nodes that can — `StreamConsumerManager`'s placement) and compares it with the committed
/// record:
///   - **no record yet** → `Put` at `assignmentTerm = 1`,
///   - **same assignee** → nothing (idempotent),
///   - **different assignee** → `Put` at `assignmentTerm + 1`,
///   - **no assignee computable** (the slice is ACTIVE nowhere) → nothing: the old record stands, and its
///     assignee cannot deliver anyway.
///
/// The epoch is `Epoch(rabiaTerm, assignmentTerm)`, exactly as the stream-ownership writer builds it: it
/// advances on a leader change and on every same-term reassignment, and the applier's `EpochBearing`
/// fence rejects a deposed leader's stale write to the record.
///
/// `assignmentTerm + 1` is derived from the leader's OWN mirror, and the epoch fence accepts an EQUAL
/// epoch (rev1335 §2 residual): a leader whose mirror lags across consecutive ticks while placement flaps
/// can therefore commit two different assignees at the same epoch. The `AssignmentToken` still tells them
/// apart (it carries the assignee); the node-local disk cursor's tag does not (epoch only), so a stale
/// local cursor from an earlier same-epoch tenure of the same node could pass the disk filter. Reaching
/// it needs both a lagging leader mirror and a flapping placement, and the cost is duplicate delivery,
/// not a skip, unless the cursor was deliberately rewound.
public interface ConsumerAssignmentWriter {
    /// The `Put`s that make the committed records match `assignments` for one declaration — empty on a
    /// follower (the leadership check runs before any committed-state read).
    List<KVCommand<AetherKey>> writeAssignmentChanges(String stream,
                                                      String group,
                                                      List<PartitionAssignment> assignments);

    /// The committed record for `(stream, partition, group)` as this node's committed-state mirror holds
    /// it now. [Option#none] means none is committed.
    @FunctionalInterface
    interface CommittedAssignments {
        Option<ConsumerAssignmentValue> assignmentOf(String stream, int partition, String group);
    }

    static ConsumerAssignmentWriter consumerAssignmentWriter(BooleanSupplier isLeader,
                                                             Supplier<Long> rabiaTerm,
                                                             HlcClock clock,
                                                             CommittedAssignments committed) {
        return (stream, group, assignments) -> isLeader.getAsBoolean()
                                               ? changes(rabiaTerm, clock, committed, stream, group, assignments)
                                               : List.of();
    }

    private static List<KVCommand<AetherKey>> changes(Supplier<Long> rabiaTerm,
                                                      HlcClock clock,
                                                      CommittedAssignments committed,
                                                      String stream,
                                                      String group,
                                                      List<PartitionAssignment> assignments) {
        return assignments.stream()
                          .flatMap(assignment -> decide(rabiaTerm.get(),
                                                        clock,
                                                        committed,
                                                        stream,
                                                        group,
                                                        assignment).stream())
                          .toList();
    }

    private static Option<KVCommand<AetherKey>> decide(long term,
                                                       HlcClock clock,
                                                       CommittedAssignments committed,
                                                       String stream,
                                                       String group,
                                                       PartitionAssignment assignment) {
        return assignment.consumerNode()
                         .flatMap(assignee -> decideFor(term,
                                                        clock,
                                                        committed.assignmentOf(stream,
                                                                               assignment.partition(),
                                                                               group),
                                                        ConsumerAssignmentKey.consumerAssignmentKey(stream,
                                                                                                    assignment.partition(),
                                                                                                    group),
                                                        assignee));
    }

    private static Option<KVCommand<AetherKey>> decideFor(long term,
                                                          HlcClock clock,
                                                          Option<ConsumerAssignmentValue> current,
                                                          ConsumerAssignmentKey key,
                                                          NodeId assignee) {
        return current.fold(() -> Option.some(put(key, assignee, term, 1L, clock)),
                            record -> rewriteIfMoved(term, clock, record, key, assignee));
    }

    private static Option<KVCommand<AetherKey>> rewriteIfMoved(long term,
                                                               HlcClock clock,
                                                               ConsumerAssignmentValue record,
                                                               ConsumerAssignmentKey key,
                                                               NodeId assignee) {
        return record.assignee()
                     .equals(assignee)
               ? Option.none()
               : Option.some(put(key, assignee, term, record.assignmentTerm() + 1L, clock));
    }

    private static KVCommand<AetherKey> put(ConsumerAssignmentKey key,
                                            NodeId assignee,
                                            long term,
                                            long assignmentTerm,
                                            HlcClock clock) {
        return new KVCommand.Put<AetherKey, AetherValue>(key,
                                                         ConsumerAssignmentValue.consumerAssignmentValue(assignee,
                                                                                                         Epoch.epoch(term,
                                                                                                                     assignmentTerm),
                                                                                                         assignmentTerm,
                                                                                                         clock.now()));
    }
}
