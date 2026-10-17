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

/// #1723: the scheduled-task outcome events are derived from the COMMITTED task state. The condition is solely "the newest
/// fire's outcome is UNKNOWN": a timed-out fire begins it; a later fire that completes, the late answer of the newest fire
/// or the task's removal ends it. Nothing a live process must do is part of it, so a fire whose answer never comes (its
/// node died, the leader changed) cannot hold it up.
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

    private static ScheduledTaskStateValue unknown(Option<ScheduledTaskStateValue> prior, long firedAt) {
        return ScheduledTaskStateValue.unknownOutcomeState(prior, 0, firedAt);
    }

    private static ScheduledTaskStateValue success(Option<ScheduledTaskStateValue> prior, long firedAt) {
        return ScheduledTaskStateValue.successState(prior, 0, firedAt);
    }

    @Test
    void firstUnknownFire_announcesUnknown_withTaskAndFireTime() {
        var first = unknown(Option.none(), 1_000L);

        announcer.onStatePut(put(KEY, Option.none(), first));

        assertThat(events).singleElement().isInstanceOfSatisfying(OperationalEvent.ScheduledTaskOutcomeUnknown.class, event -> {
            assertThat(event.task()).isEqualTo("cache/org.example:my-slice:1.0.0/cleanup");
            assertThat(event.node()).isEmpty();
            assertThat(event.fireAt()).as("the FIRE's start time, not the time its timeout was recorded").isEqualTo(1_000L);
            assertThat(event.eventId()).isEqualTo("scheduled-outcome-unknown:" + KEY.asString() + ":1:1000");
        });
    }

    @Test
    void perNodeRow_namesTheNode() {
        announcer.onStatePut(put(NODE_KEY, Option.none(), unknown(Option.none(), 1_000L)));

        assertThat(events).singleElement().isInstanceOfSatisfying(OperationalEvent.ScheduledTaskOutcomeUnknown.class,
                                                                  event -> assertThat(event.node()).isEqualTo("node-a"));
    }

    /// Nothing changes for the operator: more timeouts while already unknown, a skipped overlap, a definite fire on a task
    /// that was not unknown.
    @Test
    void conditionUnchanged_announcesNothing() {
        var first = unknown(Option.none(), 1_000L);
        var second = unknown(Option.some(first), 2_000L);
        var skipped = ScheduledTaskStateValue.skippedOverlapState(Option.some(second), 5L);

        announcer.onStatePut(put(KEY, Option.some(first), second));
        announcer.onStatePut(put(KEY, Option.some(second), skipped));
        announcer.onStatePut(put(KEY, Option.none(), success(Option.none(), 3_000L)));

        assertThat(events).isEmpty();
    }

    /// A fire whose answer never comes (its node died, the leader changed) must not keep the task unknown through any
    /// number of later completions: the FIRST later fire that completes ends it, once, and it is not a late answer.
    @Test
    void neverAnsweredUnknown_thenTenSuccesses_raisesOneRestored_afterTheFirst_notLate() {
        var row = unknown(Option.none(), 1_000L);

        announcer.onStatePut(put(KEY, Option.none(), row));
        for (int i = 0; i < 10; i++) {
            var next = success(Option.some(row), 2_000L + i);

            announcer.onStatePut(put(KEY, Option.some(row), next));
            if (i == 0) {
                assertThat(events).as("RESTORED after the FIRST success").hasSize(2);
            }
            row = next;
        }

        assertThat(events).hasSize(2);
        assertThat(events.get(1)).isInstanceOfSatisfying(OperationalEvent.ScheduledTaskOutcomeRestored.class, event -> {
            assertThat(event.outcome()).isEqualTo("executed");
            assertThat(event.reason()).isEqualTo("later-fire");
            assertThat(event.late()).isFalse();
            assertThat(event.eventId()).isEqualTo("scheduled-outcome-restored:" + KEY.asString() + ":2:2000");
        });
    }

    @Test
    void laterFailure_endsTheCondition_asFailed_notLate() {
        var first = unknown(Option.none(), 1_000L);

        announcer.onStatePut(put(KEY, Option.none(), first));
        announcer.onStatePut(put(KEY, Option.some(first), ScheduledTaskStateValue.failureState(Option.some(first), 0, 2_000L, "boom")));

        assertThat(events).hasSize(2);
        assertThat(events.getLast()).isInstanceOfSatisfying(OperationalEvent.ScheduledTaskOutcomeRestored.class, event -> {
            assertThat(event.outcome()).isEqualTo("failed");
            assertThat(event.reason()).isEqualTo("later-fire");
        });
    }

    /// A new timeout after a definite fire is a new UNKNOWN and is announced (the per-fire pair rule).
    @Test
    void freshTimeoutAfterADefiniteFire_isAnnouncedAgain() {
        var f1 = unknown(Option.none(), 1_000L);
        var f2 = success(Option.some(f1), 2_000L);
        var f3 = unknown(Option.some(f2), 3_000L);

        announcer.onStatePut(put(KEY, Option.none(), f1));
        announcer.onStatePut(put(KEY, Option.some(f1), f2));
        announcer.onStatePut(put(KEY, Option.some(f2), f3));

        assertThat(events).extracting(event -> event.getClass().getSimpleName())
                          .containsExactly("ScheduledTaskOutcomeUnknown", "ScheduledTaskOutcomeRestored", "ScheduledTaskOutcomeUnknown");
        assertThat(events.getLast()).isInstanceOfSatisfying(OperationalEvent.ScheduledTaskOutcomeUnknown.class,
                                                            event -> assertThat(event.eventId()).endsWith(":3:3000"));
    }

    @Test
    void lateSuccess_ofTheNewestFire_announcesRestored_asExecuted_andLate() {
        var first = unknown(Option.none(), 1_000L);
        var resolved = ScheduledTaskStateValue.lateSuccessState(first, first.fireSeq());

        announcer.onStatePut(put(KEY, Option.some(first), resolved));

        assertThat(events).singleElement().isInstanceOfSatisfying(OperationalEvent.ScheduledTaskOutcomeRestored.class, event -> {
            assertThat(event.outcome()).isEqualTo("executed");
            assertThat(event.reason()).isEqualTo("late-answer");
            assertThat(event.late()).isTrue();
            assertThat(event.eventId()).isEqualTo("scheduled-outcome-restored:" + KEY.asString() + ":1:1000");
        });
    }

    @Test
    void lateFailure_ofTheNewestFire_announcesRestored_asFailed_andLate() {
        var first = unknown(Option.none(), 1_000L);

        announcer.onStatePut(put(KEY, Option.some(first), ScheduledTaskStateValue.lateFailureState(first, first.fireSeq(), "late")));

        assertThat(events).singleElement().isInstanceOfSatisfying(OperationalEvent.ScheduledTaskOutcomeRestored.class, event -> {
            assertThat(event.outcome()).isEqualTo("failed");
            assertThat(event.late()).isTrue();
        });
    }

    /// An OLDER fire's late answer while the newest fire is still unknown changes nothing the operator sees.
    @Test
    void lateAnswerOfAnOlderFire_whileTheNewestIsUnknown_announcesNothing() {
        var first = unknown(Option.none(), 1_000L);
        var second = unknown(Option.some(first), 2_000L);

        announcer.onStatePut(put(KEY, Option.some(second), ScheduledTaskStateValue.lateSuccessState(second, first.fireSeq())));

        assertThat(events).isEmpty();
    }

    /// An older fire's late failure after a newer success keeps the success: no event.
    @Test
    void olderFireLateFailure_afterNewerSuccess_announcesNothing() {
        var first = unknown(Option.none(), 1_000L);
        var newerSuccess = success(Option.some(first), 2_000L);

        announcer.onStatePut(put(KEY,
                                 Option.some(newerSuccess),
                                 ScheduledTaskStateValue.lateFailureState(newerSuccess, first.fireSeq(), "late")));

        assertThat(events).isEmpty();
    }

    /// The task is removed while its newest fire is unknown: the manager commits the cleared row, which closes the
    /// operator's UNKNOWN as `task-removed`.
    @Test
    void taskRemovedWithOpenUnknown_announcesRestored_taskRemoved() {
        var first = unknown(Option.none(), 1_000L);

        announcer.onStatePut(put(KEY, Option.none(), first));
        announcer.onStatePut(put(KEY, Option.some(first), ScheduledTaskStateValue.conditionClearedState(first)));

        assertThat(events).hasSize(2);
        assertThat(events.getLast()).isInstanceOfSatisfying(OperationalEvent.ScheduledTaskOutcomeRestored.class, event -> {
            assertThat(event.reason()).isEqualTo("task-removed");
            assertThat(event.outcome()).isEqualTo("unknown");
        });
    }

    /// A per-node row whose node left for good while unknown: the leader commits the closed row, which ends the operator's
    /// UNKNOWN as `node-departed` (the alarm would otherwise be stale forever: nothing writes that row again).
    @Test
    void nodeDepartedWithOpenUnknown_announcesRestored_nodeDeparted() {
        var first = unknown(Option.none(), 1_000L);

        announcer.onStatePut(put(NODE_KEY, Option.none(), first));
        announcer.onStatePut(put(NODE_KEY, Option.some(first), ScheduledTaskStateValue.nodeDepartedState(first)));

        assertThat(events).hasSize(2);
        assertThat(events.getLast()).isInstanceOfSatisfying(OperationalEvent.ScheduledTaskOutcomeRestored.class, event -> {
            assertThat(event.reason()).isEqualTo("node-departed");
            assertThat(event.outcome()).isEqualTo("unknown");
            assertThat(event.node()).isEqualTo("node-a");
        });
    }

    /// A task registered again under the same key starts clean: the cleared row carries no UNKNOWN, so its first timeout
    /// is announced as a new UNKNOWN, with an id that cannot repeat the first life's (the sequence continues, and the
    /// fire's start time differs).
    @Test
    void reRegisteredTask_startsWithAFreshCondition_andNewIds() {
        var first = unknown(Option.none(), 1_000L);
        var cleared = ScheduledTaskStateValue.conditionClearedState(first);
        var again = unknown(Option.some(cleared), 9_000L);

        announcer.onStatePut(put(KEY, Option.none(), first));
        announcer.onStatePut(put(KEY, Option.some(first), cleared));
        announcer.onStatePut(put(KEY, Option.some(cleared), again));

        var ids = events.stream().map(event -> switch (event) {
            case OperationalEvent.ScheduledTaskOutcomeUnknown u -> u.eventId();
            case OperationalEvent.ScheduledTaskOutcomeRestored r -> r.eventId();
            default -> "other";
        }).toList();

        assertThat(events).extracting(event -> event.getClass().getSimpleName())
                          .containsExactly("ScheduledTaskOutcomeUnknown", "ScheduledTaskOutcomeRestored", "ScheduledTaskOutcomeUnknown");
        assertThat(ids).doesNotHaveDuplicates();
    }

    /// Two different transitions never share an id, even when the same sequence number is read twice: the id is the
    /// kind, the row, the sequence and the fire's start time.
    @Test
    void everyTransitionHasItsOwnEventId() {
        var f1 = unknown(Option.none(), 1_000L);
        var f2 = success(Option.some(f1), 2_000L);
        var f3 = unknown(Option.some(f2), 3_000L);
        var f3Resolved = ScheduledTaskStateValue.lateFailureState(f3, f3.fireSeq(), "late");

        announcer.onStatePut(put(KEY, Option.none(), f1));
        announcer.onStatePut(put(KEY, Option.some(f1), f2));
        announcer.onStatePut(put(KEY, Option.some(f2), f3));
        announcer.onStatePut(put(KEY, Option.some(f3), f3Resolved));

        var ids = events.stream().map(event -> switch (event) {
            case OperationalEvent.ScheduledTaskOutcomeUnknown u -> u.eventId();
            case OperationalEvent.ScheduledTaskOutcomeRestored r -> r.eventId();
            default -> "other";
        }).toList();

        assertThat(ids).hasSize(4).doesNotHaveDuplicates();
    }

    private static ValuePut<ScheduledTaskStateKey, ScheduledTaskStateValue> put(ScheduledTaskStateKey key,
                                                                                Option<ScheduledTaskStateValue> old,
                                                                                ScheduledTaskStateValue value) {
        return new ValuePut<>(new KVCommand.Put<>(key, value), old);
    }
}
