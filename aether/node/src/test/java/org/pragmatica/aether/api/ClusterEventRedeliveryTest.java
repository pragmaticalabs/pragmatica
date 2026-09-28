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
    private final List<Long> attempts = new ArrayList<>();
    private volatile boolean retriesHang;
    private final ClusterEventRedelivery redelivery = ClusterEventRedelivery.clusterEventRedelivery(this::publish, now::get);

    private Promise<Unit> publish(ClusterEvent event) {
        attempts.add(now.get());
        if (retriesHang && event.summary()
                                   .startsWith("first-wave")) {
            return Promise.promise();
        }

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
        assertThat(redelivery.held()).as("nothing is held once it landed").isZero();
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
        assertThat(redelivery.held()).isZero();
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

    /// Backoff doubles from 1 s and is capped at 8 s: retries go out 1, 2, 4, 8, 8 s apart.
    @Test
    void redeliver_backoffDoublesAndIsCapped() {
        fail(100, UNKNOWN);
        redelivery.deliver(event("keeps-failing"));

        for (int step = 0; step < 400; step++) {
            advanceAndRedeliver(100);
        }

        var gaps = new ArrayList<Long>();

        for (int i = 1; i <= 5; i++) {
            gaps.add(attempts.get(i) - attempts.get(i - 1));
        }

        assertThat(gaps).containsExactly(1_000L, 2_000L, 4_000L, 8_000L, 8_000L);
    }

    /// #1653 round 2: the bound covers held events, waiting AND in flight. With every retry hanging in flight, new
    /// failures still cannot push `held` past CAPACITY; the excess is dropped and counted.
    @Test
    void deliver_withRetriesInFlight_heldNeverExceedsCapacity() {
        fail(3 * ClusterEventRedelivery.CAPACITY, UNKNOWN);
        for (int i = 0; i < ClusterEventRedelivery.CAPACITY; i++) {
            redelivery.deliver(event("first-wave-" + i));
        }
        retriesHang = true;
        redelivery.redeliver(true);
        assertThat(redelivery.waiting()).as("control: every held event is in flight").isZero();

        for (int i = 0; i < ClusterEventRedelivery.CAPACITY; i++) {
            redelivery.deliver(event("second-wave-" + i));
        }

        assertThat(redelivery.held()).isEqualTo(ClusterEventRedelivery.CAPACITY);
        assertThat(redelivery.dropped(OVERFLOW)).isEqualTo(ClusterEventRedelivery.CAPACITY);
    }

    /// Drops are reported: the next successful publish logs one WARN naming the dropped types and reasons.
    @Test
    void deliver_afterDrops_nextSuccessLogsTheDroppedTypes() {
        var logged = capture();

        try {
            fail(1, new StreamError.EventTooLarge(10_000, 1_000));
            redelivery.deliver(event("too-big"));
            redelivery.deliver(event("fine"));
        } finally {
            release();
        }

        assertThat(logged).anyMatch(line -> line.startsWith("WARN ")
                                            && line.contains("ALERT_INJECTED/PERMANENT=1"));
    }

    /// The first expiry is reported once at ERROR (the stream refused publishes for a whole horizon), not per event.
    @Test
    void redeliver_expiries_logOneError() {
        var logged = capture();

        try {
            fail(10_000, UNKNOWN);
            redelivery.deliver(event("a"));
            redelivery.deliver(event("b"));
            for (long elapsed = 0; elapsed <= ClusterEventRedelivery.RETRY_HORIZON_MS + ClusterEventRedelivery.MAX_BACKOFF_MS; elapsed += 1_000) {
                advanceAndRedeliver(1_000);
            }
        } finally {
            release();
        }

        assertThat(redelivery.dropped(EXPIRED)).as("control").isEqualTo(2L);
        assertThat(logged.stream()
                         .filter(line -> line.startsWith("ERROR ")))
            .hasSize(1)
            .allMatch(line -> line.contains("is refusing publishes"));
    }

    private final List<String> captured = new java.util.concurrent.CopyOnWriteArrayList<>();
    private org.apache.logging.log4j.core.LoggerContext logContext;
    private org.apache.logging.log4j.core.appender.AbstractAppender appender;

    private List<String> capture() {
        logContext = (org.apache.logging.log4j.core.LoggerContext) org.apache.logging.log4j.LogManager.getContext(false);
        appender = new org.apache.logging.log4j.core.appender.AbstractAppender("RedeliveryCapture",
                                                                               null,
                                                                               null,
                                                                               true,
                                                                               org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY) {
            @Override
            public void append(org.apache.logging.log4j.core.LogEvent event) {
                captured.add(event.getLevel() + " " + event.getMessage().getFormattedMessage());
            }
        };
        appender.start();

        var config = new org.apache.logging.log4j.core.config.LoggerConfig(ClusterEventRedelivery.class.getName(),
                                                                           org.apache.logging.log4j.Level.TRACE,
                                                                           false);

        config.addAppender(appender, org.apache.logging.log4j.Level.TRACE, null);
        logContext.getConfiguration().addLogger(ClusterEventRedelivery.class.getName(), config);
        logContext.updateLoggers();

        return captured;
    }

    private void release() {
        logContext.getConfiguration().removeLogger(ClusterEventRedelivery.class.getName());
        logContext.updateLoggers();
        appender.stop();
    }
}
