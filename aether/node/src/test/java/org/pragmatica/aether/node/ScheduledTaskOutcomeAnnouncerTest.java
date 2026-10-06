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

/// #1723: the scheduled-task outcome events are derived from the COMMITTED task state, on the gauge of fires whose
/// outcome is unknown: rising from zero is an UNKNOWN, falling to zero a RESTORED, anything else silent.
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

    /// The gauge is the dedupe: more unknown fires, a definite fire and a skipped overlap keep the condition as it was.
    @Test
    void conditionUnchanged_announcesNothing() {
        var first = ScheduledTaskStateValue.unknownOutcomeState(Option.none(), 0);
        var second = ScheduledTaskStateValue.unknownOutcomeState(Option.some(first), 0);
        var success = ScheduledTaskStateValue.successState(0, 1, 0, second.unknownOutcomes(), second.fireSeq() + 1);
        var skipped = ScheduledTaskStateValue.skippedOverlapState(Option.some(success), 5L);

        announcer.onStatePut(put(KEY, Option.some(first), second));
        announcer.onStatePut(put(KEY, Option.some(second), success));
        announcer.onStatePut(put(KEY, Option.some(success), skipped));
        announcer.onStatePut(put(KEY, Option.none(), ScheduledTaskStateValue.successState(0, 1, 0, 0, 1)));

        assertThat(events).isEmpty();
    }

    @Test
    void lateSuccess_ofTheLastUnknownFire_announcesRestored_asExecuted() {
        var unknown = ScheduledTaskStateValue.unknownOutcomeState(Option.none(), 0);
        var resolved = ScheduledTaskStateValue.lateSuccessState(unknown, unknown.fireSeq());

        announcer.onStatePut(put(KEY, Option.some(unknown), resolved));

        assertThat(events).singleElement().isInstanceOfSatisfying(OperationalEvent.ScheduledTaskOutcomeRestored.class, event -> {
            assertThat(event.outcome()).isEqualTo("executed");
            assertThat(event.eventId()).isEqualTo("scheduled-outcome-restored:" + KEY.asString() + ":1");
        });
    }

    @Test
    void lateFailure_announcesRestored_asFailed_evenWhenANewerFireKeptItsOutcome() {
        var unknown = ScheduledTaskStateValue.unknownOutcomeState(Option.none(), 0);
        var newerSuccess = ScheduledTaskStateValue.successState(0, 1, 0, unknown.unknownOutcomes(), unknown.fireSeq() + 1);
        var resolved = ScheduledTaskStateValue.lateFailureState(newerSuccess, unknown.fireSeq(), "late");

        announcer.onStatePut(put(KEY, Option.some(newerSuccess), resolved));

        assertThat(events).singleElement().isInstanceOfSatisfying(OperationalEvent.ScheduledTaskOutcomeRestored.class,
                                                                  event -> assertThat(event.outcome()).isEqualTo("failed"));
    }

    /// Only the LAST unknown fire's resolution leaves the condition: 2 -> 1 announces nothing.
    @Test
    void partialResolution_announcesNothing() {
        var first = ScheduledTaskStateValue.unknownOutcomeState(Option.none(), 0);
        var second = ScheduledTaskStateValue.unknownOutcomeState(Option.some(first), 0);

        announcer.onStatePut(put(KEY, Option.some(second), ScheduledTaskStateValue.lateSuccessState(second, first.fireSeq())));

        assertThat(events).isEmpty();
    }

    private static ValuePut<ScheduledTaskStateKey, ScheduledTaskStateValue> put(ScheduledTaskStateKey key,
                                                                                Option<ScheduledTaskStateValue> old,
                                                                                ScheduledTaskStateValue value) {
        return new ValuePut<>(new KVCommand.Put<>(key, value), old);
    }
}
