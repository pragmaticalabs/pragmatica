// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.PublishOutcomeUnknown;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.api.ClusterEventRedelivery.DropReason.EXPIRED;
import static org.pragmatica.aether.api.ClusterEventRedelivery.DropReason.OVERFLOW;
import static org.pragmatica.aether.api.ClusterEventRedelivery.DropReason.PERMANENT;

/// #1640: a cluster event whose publish did not land is retried until it lands or its horizon passes, and
/// everything given up on is counted.
class ClusterEventRedeliveryTest {
    private static final Cause UNKNOWN = PublishOutcomeUnknown.FACTORY.apply(Causes.cause("forward timed out"));
    private static final HlcClock HLC = HlcClock.hlcClock(new NodeId("redelivery-test"));

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final List<ClusterEvent> landed = new ArrayList<>();
    private final Deque<Cause> scriptedFailures = new ArrayDeque<>();
    private final ClusterEventRedelivery redelivery = ClusterEventRedelivery.clusterEventRedelivery(this::publish, now::get);

    private Promise<Unit> publish(ClusterEvent event) {
        var failure = scriptedFailures.pollFirst();

        if (failure != null) {
            return failure.promise();
        }
        landed.add(event);

        return Promise.unitPromise();
    }

    private static ClusterEvent event(String summary) {
        return new ClusterEvent.AlertInjected(HLC.now(), ClusterEvent.Severity.INFO, summary, Map.of());
    }

    private void fail(int times, Cause cause) {
        for (int i = 0; i < times; i++) {
            scriptedFailures.addLast(cause);
        }
    }

    private void advanceAndRedeliver(long millis) {
        now.addAndGet(millis);
        redelivery.redeliver(false);
    }

    @Test
    void deliver_outcomeUnknownThreeTimes_thenLands_publishedOnceWithTheSameAt() {
        var event = event("rollback-decision");

        fail(3, UNKNOWN);
        redelivery.deliver(event);

        for (int i = 0; i < 4; i++) {
            advanceAndRedeliver(ClusterEventRedelivery.MAX_BACKOFF_MS);
        }

        assertThat(landed).containsExactly(event);
        assertThat(landed.getFirst().at()).as("a retry re-sends the same event, so the same at").isEqualTo(event.at());
        assertThat(redelivery.waiting()).isZero();
        assertThat(redelivery.outcomeUnknown()).isEqualTo(3L);
        assertThat(redelivery.retried()).isEqualTo(3L);
    }

    @Test
    void redeliver_beforeTheBackoffElapses_doesNotResend() {
        fail(1, UNKNOWN);
        redelivery.deliver(event("e"));

        advanceAndRedeliver(ClusterEventRedelivery.INITIAL_BACKOFF_MS - 1);

        assertThat(landed).isEmpty();
        assertThat(redelivery.retried()).isZero();
    }

    /// An owner change re-sends every waiting event at once, not on its backoff.
    @Test
    void redeliverAll_ownerChanged_resendsBeforeTheBackoff() {
        fail(1, UNKNOWN);
        redelivery.deliver(event("e"));

        redelivery.redeliver(true);

        assertThat(landed).hasSize(1);
    }

    @Test
    void deliver_permanentFailure_isDroppedAtOnceAndCounted() {
        fail(1, new StreamError.EventTooLarge(10_000, 1_000));
        redelivery.deliver(event("too-big"));

        assertThat(redelivery.waiting()).isZero();
        assertThat(redelivery.dropped(PERMANENT)).isEqualTo(1L);
    }

    /// The horizon: an event not delivered within RETRY_HORIZON_MS of its first failure is dropped as expired.
    @Test
    void redeliver_pastTheHorizon_expiresAndCounts() {
        fail(10_000, UNKNOWN);
        redelivery.deliver(event("never-lands"));

        for (long elapsed = 0; elapsed < ClusterEventRedelivery.RETRY_HORIZON_MS; elapsed += ClusterEventRedelivery.MAX_BACKOFF_MS) {
            advanceAndRedeliver(ClusterEventRedelivery.MAX_BACKOFF_MS);
        }
        advanceAndRedeliver(ClusterEventRedelivery.MAX_BACKOFF_MS);

        assertThat(redelivery.waiting()).isZero();
        assertThat(redelivery.dropped(EXPIRED)).isEqualTo(1L);
        assertThat(landed).isEmpty();
    }

    /// A full buffer drops its OLDEST entry (CTO ruling), counted.
    @Test
    void deliver_beyondCapacity_dropsTheOldest() {
        fail(ClusterEventRedelivery.CAPACITY + 1, UNKNOWN);

        var first = event("oldest");

        redelivery.deliver(first);
        for (int i = 1; i <= ClusterEventRedelivery.CAPACITY; i++) {
            redelivery.deliver(event("e" + i));
        }

        assertThat(redelivery.waiting()).isEqualTo(ClusterEventRedelivery.CAPACITY);
        assertThat(redelivery.dropped(OVERFLOW)).isEqualTo(1L);

        redelivery.redeliver(true);

        assertThat(landed).as("the oldest was the one dropped").doesNotContain(first).hasSize(ClusterEventRedelivery.CAPACITY);
    }
}
