// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.function.Consumer;

import org.pragmatica.aether.api.OperationalEvent;
import org.pragmatica.aether.invoke.ScheduledFireObserver;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Unit;


/// #1930 (owner rule: an operator-facing condition emits an event on its transition, and a recovery event): turns what the
/// scheduler observes of a fire that is still in flight when its next tick arrives into operational events.
///
/// Unlike the outcome events of #1723 this is NOT derived from a committed record: the in-flight claim lives only in the
/// scheduler of the node that fired (the leader, for a SINGLE-mode task), so the node that observes it raises it, and the
/// aggregator publishes it from there. The event id is the node, the task and the fire's start time, so a re-delivery of the
/// same fire's event collapses.
public interface ScheduledFireAnnouncer extends ScheduledFireObserver {
    static ScheduledFireAnnouncer scheduledFireAnnouncer(NodeId self, Consumer<OperationalEvent> sink) {
        return new ScheduledFireAnnouncer() {
            @Override
            public Unit onFireHeld(ScheduledTaskKey key, long fireStartedAt, long inFlightMs) {
                sink.accept(OperationalEvent.ScheduledTaskFireHeld.scheduledTaskFireHeld(taskId(key),
                                                                                         self.id(),
                                                                                         fireStartedAt,
                                                                                         inFlightMs,
                                                                                         eventId("scheduled-fire-held",
                                                                                                 key,
                                                                                                 self,
                                                                                                 fireStartedAt)));

                return Unit.unit();
            }

            @Override
            public Unit onFireReleased(ScheduledTaskKey key, long fireStartedAt, long inFlightMs, String outcome) {
                sink.accept(OperationalEvent.ScheduledTaskFireReleased.scheduledTaskFireReleased(taskId(key),
                                                                                                 self.id(),
                                                                                                 fireStartedAt,
                                                                                                 inFlightMs,
                                                                                                 outcome,
                                                                                                 eventId("scheduled-fire-released",
                                                                                                         key,
                                                                                                         self,
                                                                                                         fireStartedAt)));

                return Unit.unit();
            }
        };
    }

    private static String taskId(ScheduledTaskKey key) {
        return key.configSection() + "/" + key.artifact()
                                              .asString() + "/" + key.methodName()
                                                                     .name();
    }

    private static String eventId(String kind, ScheduledTaskKey key, NodeId self, long fireStartedAt) {
        return kind + ":" + self.id() + ":" + taskId(key) + ":" + fireStartedAt;
    }
}
