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
/// partition. The condition is `unknownOutcomes > 0`, the gauge of fires whose outcome is currently unknown, so a commit
/// that keeps it on the same side of zero (another unknown fire, a definite fire, a skipped overlap, a partial late
/// resolution) announces nothing: the committed gauge is the dedupe. Per-task flood control (the window, and holding an
/// UNKNOWN that resolves inside it) is the aggregator's.
///
/// What the gauge cannot see is announced as nothing: a removed task row raises no event (the condition is gone with the
/// task), and an unknown fire whose response never arrives keeps the condition, so no RESTORED follows it.
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
        var wasUnknown = before.map(state -> state.unknownOutcomes() > 0).or(false);
        var isUnknown = after.unknownOutcomes() > 0;

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
                                                                                                        lateOutcome(before,
                                                                                                                    after),
                                                                                                        "scheduled-outcome-restored:" + key.asString()
                                                                                                       + ":" + after.fireSeq()));
    }

    /// The late response made the fire an execution (counted) or a failure (not counted).
    private static String lateOutcome(Option<ScheduledTaskStateValue> before, ScheduledTaskStateValue after) {
        return after.totalExecutions() > before.map(ScheduledTaskStateValue::totalExecutions)
                                               .or(0)
               ? "executed"
               : "failed";
    }
}
