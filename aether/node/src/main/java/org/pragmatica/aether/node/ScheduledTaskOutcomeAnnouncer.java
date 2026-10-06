// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.function.Consumer;

import org.pragmatica.aether.api.OperationalEvent;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskStateKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskStateValue;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;


/// #1723 (owner rule: an operator-facing condition emits an event on its transition, and a recovery event): announces a
/// scheduled task gaining a fire whose outcome is UNKNOWN, and the last such fire being answered late.
///
/// Derived from the COMMITTED task state, like [StreamIsrAnnouncer]: every node applies the same Put and derives the same
/// event from its old and new value, and the cluster-events aggregator publishes only on the owner of the cluster-events
/// partition. The condition is "the NEWEST fire's outcome is UNKNOWN" (`lastOutcome`): it begins with a fire that timed
/// out and ends with a later fire that completed (RESTORED `later-fire`), the late answer of the newest fire (RESTORED
/// `late-answer`) or the task's removal (RESTORED `task-removed`: the manager clears the open outcome when the task goes).
/// A commit that keeps it unchanged (another timed-out fire while unknown, the late answer of an OLDER fire, a skipped
/// overlap) announces nothing: the committed outcome is the dedupe. A fire whose answer never comes cannot hold the
/// condition up: nothing a live process must do is part of it. Per-task flood control (the window, and holding an UNKNOWN
/// that ends inside it) is the aggregator's.
///
/// Every event carries a deterministic `eventId` (task row, its fire sequence and the fire's start time: the sequence
/// never goes backwards, and the start time tells apart a row's lives), so two nodes that both pass the
/// events-owner gate during a membership change publish ONE event as far as every reader that de-duplicates by `eventId`
/// is concerned.
public interface ScheduledTaskOutcomeAnnouncer {
    @Contract
    void onStatePut(ValuePut<ScheduledTaskStateKey, ScheduledTaskStateValue> put);

    static ScheduledTaskOutcomeAnnouncer scheduledTaskOutcomeAnnouncer(Consumer<OperationalEvent> sink) {
        return put -> transition(put.cause().key(),
                                 put.oldValue(),
                                 put.cause().value()).onPresent(sink);
    }

    /// The event a committed change of a task's state calls for: a gauge rising from zero is an UNKNOWN, falling to zero a
    /// RESTORED (the only way the gauge falls is a late response), anything else is silent.
    static Option<OperationalEvent> transition(ScheduledTaskStateKey key,
                                               Option<ScheduledTaskStateValue> before,
                                               ScheduledTaskStateValue after) {
        var wasUnknown = before.map(ScheduledTaskStateValue::outcomeUnknown).or(false);
        var isUnknown = after.outcomeUnknown();

        if (wasUnknown == isUnknown) {
            return Option.none();
        }

        var task = key.configSection() + "/" + key.artifact().asString() + "/" + key.methodName().name();
        var node = key.node().map(NodeId::id).or("");
        var id = key.asString() + ":" + after.fireSeq() + ":" + after.newestFireAt();

        return Option.some(isUnknown
                           ? OperationalEvent.ScheduledTaskOutcomeUnknown.scheduledTaskOutcomeUnknown(task,
                                                                                                      node,
                                                                                                      after.newestFireAt(),
                                                                                                      "scheduled-outcome-unknown:" + id)
                           : OperationalEvent.ScheduledTaskOutcomeRestored.scheduledTaskOutcomeRestored(task,
                                                                                                        node,
                                                                                                        outcomeOf(after),
                                                                                                        reasonOf(before,
                                                                                                                 after),
                                                                                                        "scheduled-outcome-restored:" + id));
    }

    /// The outcome the task now records for its newest fire: an execution or a failure; none when it was cleared.
    private static String outcomeOf(ScheduledTaskStateValue after) {
        return switch (after.lastOutcome()) {
            case ScheduledTaskStateValue.OUTCOME_SUCCESS -> "executed";
            case ScheduledTaskStateValue.OUTCOME_FAILURE -> "failed";
            default -> "unknown";
        };
    }

    /// A late answer is the only commit that ends the condition and raises `lateResolutions`; a cleared outcome is the
    /// task's removal; anything else is a later fire completing.
    private static String reasonOf(Option<ScheduledTaskStateValue> before, ScheduledTaskStateValue after) {
        if (after.lastOutcome().isEmpty()) {
            return OperationalEvent.ScheduledTaskOutcomeRestored.TASK_REMOVED;
        }

        return after.lateResolutions() > before.map(ScheduledTaskStateValue::lateResolutions)
                                               .or(0)
               ? OperationalEvent.ScheduledTaskOutcomeRestored.LATE_ANSWER
               : OperationalEvent.ScheduledTaskOutcomeRestored.LATER_FIRE;
    }
}
