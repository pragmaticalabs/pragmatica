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
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskStateKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskStateValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1723: the scheduled-task outcome events are derived from the COMMITTED task state. The condition is "the newest
/// fire's outcome is UNKNOWN": a timed-out fire begins it; a later fire that completes, or the late answer of the newest
/// fire, ends it. The count of unanswered fires (the gauge) is NOT the condition: a fire whose answer never comes would
/// hold it up for good.
class ScheduledTaskOutcomeAnnouncerTest {
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:my-slice:1.0.0").unwrap();
    private static final MethodName METHOD = MethodName.methodName("cleanup").unwrap();
    private static final ScheduledTaskStateKey KEY = ScheduledTaskStateKey.scheduledTaskStateKey("cache", ARTIFACT, METHOD);
    private static final ScheduledTaskStateKey NODE_KEY = ScheduledTaskStateKey.scheduledTaskStateKey("cache",
                                                                                                    ARTIFACT,
                                                                                                    METHOD,
                                                                                                    new NodeId("node-a"));

    private final List<OperationalEvent> events = new ArrayList<>();
    private final ScheduledTaskOutcomeAnnouncer announcer = ScheduledTaskOutcomeAnnouncer.scheduledTaskOutcomeAnnouncer(events::add);

    @Test
    void firstUnknownFire_announcesUnknown_withTaskAndFireTime() {
        var unknown = ScheduledTaskStateValue.unknownOutcomeState(Option.none(), 0);

        announcer.onStatePut(put(KEY, Option.none(), unknown));

        assertThat(events).singleElement().isInstanceOfSatisfying(OperationalEvent.ScheduledTaskOutcomeUnknown.class, event -> {
            assertThat(event.task()).isEqualTo("cache/org.example:my-slice:1.0.0/cleanup");
            assertThat(event.node()).isEmpty();
            assertThat(event.fireAt()).isEqualTo(unknown.updatedAt());
            assertThat(event.eventId()).isEqualTo("scheduled-outcome-unknown:" + KEY.asString() + ":1");
        });
    }

    @Test
    void perNodeRow_namesTheNode() {
        announcer.onStatePut(put(NODE_KEY, Option.none(), ScheduledTaskStateValue.unknownOutcomeState(Option.none(), 0)));

        assertThat(events).singleElement().isInstanceOfSatisfying(OperationalEvent.ScheduledTaskOutcomeUnknown.class,
                                                                  event -> assertThat(event.node()).isEqualTo("node-a"));
    }

    /// Nothing changes for the operator: more timeouts while already unknown, a skipped overlap, a definite fire on a
    /// task that was not unknown.
    @Test
    void conditionUnchanged_announcesNothing() {
        var first = ScheduledTaskStateValue.unknownOutcomeState(Option.none(), 0);
        var second = ScheduledTaskStateValue.unknownOutcomeState(Option.some(first), 0);
        var skipped = ScheduledTaskStateValue.skippedOverlapState(Option.some(second), 5L);

        announcer.onStatePut(put(KEY, Option.some(first), second));
        announcer.onStatePut(put(KEY, Option.some(second), skipped));
        announcer.onStatePut(put(KEY, Option.none(), ScheduledTaskStateValue.successState(Option.none(), 0)));

        assertThat(events).isEmpty();
    }

    /// The defect this replaces encoded "a definite fire after an UNKNOWN announces nothing". A fire whose answer never
    /// comes (departed callee, leader change, TTL, capacity, lost response) must not keep the task unknown through any
    /// number of later completions: the FIRST later fire that completes ends it, once, and is not a late answer.
    @Test
    void neverAnsweredUnknown_thenTenSuccesses_raisesOneRestored_notLate() {
        var row = ScheduledTaskStateValue.unknownOutcomeState(Option.none(), 0);

        announcer.onStatePut(put(KEY, Option.none(), row));
        for (int i = 0; i < 10; i++) {
            var next = ScheduledTaskStateValue.successState(Option.some(row), 0);

            announcer.onStatePut(put(KEY, Option.some(row), next));
            row = next;
        }

        assertThat(row.unknownOutcomes()).as("premise: the unanswered fire is still counted").isEqualTo(1);
        assertThat(events).hasSize(2);
        assertThat(events.get(1)).isInstanceOfSatisfying(OperationalEvent.ScheduledTaskOutcomeRestored.class, event -> {
            assertThat(event.outcome()).isEqualTo("executed");
            assertThat(event.late()).as("a later fire completed; nothing arrived late").isFalse();
            assertThat(event.eventId()).isEqualTo("scheduled-outcome-restored:" + KEY.asString() + ":2");
        });
    }

    @Test
    void laterFailure_endsTheCondition_asFailed_notLate() {
        var unknown = ScheduledTaskStateValue.unknownOutcomeState(Option.none(), 0);

        announcer.onStatePut(put(KEY, Option.none(), unknown));
        announcer.onStatePut(put(KEY, Option.some(unknown), ScheduledTaskStateValue.failureState(Option.some(unknown), 0, "boom")));

        assertThat(events).hasSize(2);
        assertThat(events.getLast()).isInstanceOfSatisfying(OperationalEvent.ScheduledTaskOutcomeRestored.class, event -> {
            assertThat(event.outcome()).isEqualTo("failed");
            assertThat(event.late()).isFalse();
        });
    }

    /// A new timeout after a definite fire is a new UNKNOWN and is announced (the per-fire pair rule).
    @Test
    void freshTimeoutAfterADefiniteFire_isAnnouncedAgain() {
        var f1 = ScheduledTaskStateValue.unknownOutcomeState(Option.none(), 0);
        var f2 = ScheduledTaskStateValue.successState(Option.some(f1), 0);
        var f3 = ScheduledTaskStateValue.unknownOutcomeState(Option.some(f2), 0);

        announcer.onStatePut(put(KEY, Option.none(), f1));
        announcer.onStatePut(put(KEY, Option.some(f1), f2));
        announcer.onStatePut(put(KEY, Option.some(f2), f3));

        assertThat(events).extracting(event -> event.getClass().getSimpleName())
                          .containsExactly("ScheduledTaskOutcomeUnknown", "ScheduledTaskOutcomeRestored", "ScheduledTaskOutcomeUnknown");
        assertThat(events.getLast()).isInstanceOfSatisfying(OperationalEvent.ScheduledTaskOutcomeUnknown.class,
                                                            event -> assertThat(event.eventId()).endsWith(":3"));
    }

    @Test
    void lateSuccess_ofTheNewestFire_announcesRestored_asExecuted_andLate() {
        var unknown = ScheduledTaskStateValue.unknownOutcomeState(Option.none(), 0);
        var resolved = ScheduledTaskStateValue.lateSuccessState(unknown, unknown.fireSeq());

        announcer.onStatePut(put(KEY, Option.some(unknown), resolved));

        assertThat(events).singleElement().isInstanceOfSatisfying(OperationalEvent.ScheduledTaskOutcomeRestored.class, event -> {
            assertThat(event.outcome()).isEqualTo("executed");
            assertThat(event.late()).isTrue();
            assertThat(event.eventId()).isEqualTo("scheduled-outcome-restored:" + KEY.asString() + ":1");
        });
    }

    @Test
    void lateFailure_ofTheNewestFire_announcesRestored_asFailed_andLate() {
        var unknown = ScheduledTaskStateValue.unknownOutcomeState(Option.none(), 0);

        announcer.onStatePut(put(KEY, Option.some(unknown), ScheduledTaskStateValue.lateFailureState(unknown, unknown.fireSeq(), "late")));

        assertThat(events).singleElement().isInstanceOfSatisfying(OperationalEvent.ScheduledTaskOutcomeRestored.class, event -> {
            assertThat(event.outcome()).isEqualTo("failed");
            assertThat(event.late()).isTrue();
        });
    }

    /// An OLDER fire's late answer while the newest fire is still unknown changes nothing the operator sees.
    @Test
    void lateAnswerOfAnOlderFire_whileTheNewestIsUnknown_announcesNothing() {
        var first = ScheduledTaskStateValue.unknownOutcomeState(Option.none(), 0);
        var second = ScheduledTaskStateValue.unknownOutcomeState(Option.some(first), 0);

        announcer.onStatePut(put(KEY, Option.some(second), ScheduledTaskStateValue.lateSuccessState(second, first.fireSeq())));

        assertThat(events).isEmpty();
    }

    /// An older fire's late failure after a newer success keeps the success: no event.
    @Test
    void olderFireLateFailure_afterNewerSuccess_announcesNothing() {
        var unknown = ScheduledTaskStateValue.unknownOutcomeState(Option.none(), 0);
        var newerSuccess = ScheduledTaskStateValue.successState(Option.some(unknown), 0);

        announcer.onStatePut(put(KEY,
                                 Option.some(newerSuccess),
                                 ScheduledTaskStateValue.lateFailureState(newerSuccess, unknown.fireSeq(), "late")));

        assertThat(events).isEmpty();
    }

    /// Giving a fire up lowers the gauge and invents no outcome: the newest fire is still unknown, so nothing is announced.
    @Test
    void abandonedNewestFire_announcesNothing() {
        var unknown = ScheduledTaskStateValue.unknownOutcomeState(Option.none(), 0);

        announcer.onStatePut(put(KEY, Option.some(unknown), ScheduledTaskStateValue.abandonedState(unknown)));

        assertThat(events).isEmpty();
    }

    private static ValuePut<ScheduledTaskStateKey, ScheduledTaskStateValue> put(ScheduledTaskStateKey key,
                                                                                Option<ScheduledTaskStateValue> old,
                                                                                ScheduledTaskStateValue value) {
        return new ValuePut<>(new KVCommand.Put<>(key, value), old);
    }
}
