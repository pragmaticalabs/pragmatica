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
/// out and ends with a later fire that completed (RESTORED, not late) or the late answer of the newest fire (RESTORED,
/// late). A commit that keeps it unchanged (another timed-out fire while unknown, the late answer of an OLDER fire, a
/// skipped overlap, a gauge falling because a fire was given up) announces nothing: the committed outcome is the dedupe.
/// Per-task flood control (the window, and holding an UNKNOWN that ends inside it) is the aggregator's.
///
/// A removed task row raises no event (the condition is gone with the task). The condition ends only through a fire's
/// completion: a task that stops firing while unknown stays unknown, and says so.
///
/// Every event carries a deterministic `eventId` (task row and its fire sequence), so two nodes that both pass the
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

        return Option.some(isUnknown
                           ? OperationalEvent.ScheduledTaskOutcomeUnknown.scheduledTaskOutcomeUnknown(task,
                                                                                                      node,
                                                                                                      after.updatedAt(),
                                                                                                      "scheduled-outcome-unknown:" + key.asString()
                                                                                                     + ":" + after.fireSeq())
                           : OperationalEvent.ScheduledTaskOutcomeRestored.scheduledTaskOutcomeRestored(task,
                                                                                                        node,
                                                                                                        outcomeOf(after),
                                                                                                        lateAnswer(before,
                                                                                                                   after),
                                                                                                        "scheduled-outcome-restored:" + key.asString()
                                                                                                       + ":" + after.fireSeq()));
    }

    /// The outcome the task now records for its newest fire: an execution or a failure.
    private static String outcomeOf(ScheduledTaskStateValue after) {
        return ScheduledTaskStateValue.OUTCOME_SUCCESS.equals(after.lastOutcome())
               ? "executed"
               : "failed";
    }

    /// A late answer is the only commit that both ends the condition and lowers the gauge; a later fire's completion
    /// carries the gauge over.
    private static boolean lateAnswer(Option<ScheduledTaskStateValue> before, ScheduledTaskStateValue after) {
        return after.unknownOutcomes() < before.map(ScheduledTaskStateValue::unknownOutcomes)
                                               .or(0);
    }
}
