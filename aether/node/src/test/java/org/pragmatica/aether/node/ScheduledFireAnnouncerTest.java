// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.ArrayList;
import java.util.List;

import org.pragmatica.aether.api.OperationalEvent;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskKey;
import org.pragmatica.consensus.NodeId;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1930: what the scheduler observes of an in-flight fire becomes operational events carrying the task, the node, the
/// fire's start time and how long it was in flight, with ids that are the node, the task and the fire, so one fire's
/// event can never be mistaken for another's.
class ScheduledFireAnnouncerTest {
    private static final ScheduledTaskKey KEY = ScheduledTaskKey.scheduledTaskKey("cache",
                                                                                  Artifact.artifact("org.example:my-slice:1.0.0").unwrap(),
                                                                                  MethodName.methodName("cleanup").unwrap());

    private final List<OperationalEvent> events = new ArrayList<>();
    private final ScheduledFireAnnouncer announcer = ScheduledFireAnnouncer.scheduledFireAnnouncer(new NodeId("node-a"), events::add);

    @Test
    void heldFire_becomesAHeldEvent_withTaskNodeFireTimeAndInFlightTime() {
        announcer.onFireHeld(KEY, 1_000L, 1_200L);

        assertThat(events).singleElement().isInstanceOfSatisfying(OperationalEvent.ScheduledTaskFireHeld.class, event -> {
            assertThat(event.task()).isEqualTo("cache/org.example:my-slice:1.0.0/cleanup");
            assertThat(event.node()).isEqualTo("node-a");
            assertThat(event.fireAt()).isEqualTo(1_000L);
            assertThat(event.inFlightMs()).isEqualTo(1_200L);
            assertThat(event.eventId()).isEqualTo("scheduled-fire-held:node-a:cache/org.example:my-slice:1.0.0/cleanup:1000");
        });
    }

    @Test
    void releasedFire_becomesAReleasedEvent_withTheOutcome() {
        announcer.onFireReleased(KEY, 1_000L, 5_000L, "unknown");

        assertThat(events).singleElement().isInstanceOfSatisfying(OperationalEvent.ScheduledTaskFireReleased.class, event -> {
            assertThat(event.outcome()).isEqualTo("unknown");
            assertThat(event.inFlightMs()).isEqualTo(5_000L);
            assertThat(event.fireAt()).isEqualTo(1_000L);
            assertThat(event.eventId()).isEqualTo("scheduled-fire-released:node-a:cache/org.example:my-slice:1.0.0/cleanup:1000");
        });
    }

    @Test
    void twoFiresOfOneTask_neverShareAnId() {
        announcer.onFireHeld(KEY, 1_000L, 1L);
        announcer.onFireHeld(KEY, 2_000L, 1L);

        assertThat(events).extracting(event -> ((OperationalEvent.ScheduledTaskFireHeld) event).eventId()).doesNotHaveDuplicates();
    }
}
