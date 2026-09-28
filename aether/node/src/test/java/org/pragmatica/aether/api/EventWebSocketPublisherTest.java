// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.api.ClusterEventAggregator.EventPage;
import org.pragmatica.aether.http.security.SecurityValidator;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;

/// #1640 / #1653: the live event feed follows an offset cursor. A late-landing event is appended beyond the cursor
/// and sent once however old its `at` is; a duplicate copy of an already-sent event is not sent again; each poll reads
/// only from the cursor.
class EventWebSocketPublisherTest {
    private final List<ClusterEvent> log = new CopyOnWriteArrayList<>();
    private final List<String> broadcasts = new CopyOnWriteArrayList<>();
    private final List<Long> readsFrom = new CopyOnWriteArrayList<>();
    private final AtomicLong clock = new AtomicLong(1_000_000L);

    /// The boundary v1640 found: a retry can land at any age (304.5 s against a 300 s window). It is appended at a new
    /// offset, so the cursor reaches it whatever its `at`.
    @Test
    void publish_eventLandsLaterThanHorizonPlusBackoff_isBroadcastOnce_andNothingIsRepeated() {
        var publisher = publisher();
        var now = Instant.now();
        var beyondAnyRetry = Duration.ofMillis(ClusterEventRedelivery.RETRY_HORIZON_MS + ClusterEventRedelivery.MAX_BACKOFF_MS)
                                     .plusSeconds(30);

        log.add(event("on-time", now.minusSeconds(1), "id-1"));
        publisher.publish();

        log.add(event("late", now.minus(beyondAnyRetry), "id-2"));
        publisher.publish();
        publisher.publish();

        assertThat(broadcasts).containsExactly("[on-time]", "[late]");
    }

    /// A redelivered event whose first copy also landed: the second copy, same `eventId`, is not sent again.
    @Test
    void publish_duplicateCopyOfASentEvent_isNotSentAgain() {
        var publisher = publisher();
        var now = Instant.now();

        log.add(event("rollback", now, "id-1"));
        publisher.publish();
        log.add(event("rollback", now, "id-1"));
        publisher.publish();

        assertThat(broadcasts).containsExactly("[rollback]");
    }

    /// Each poll reads from the cursor (less a small overlap), not from the start of the log.
    @Test
    void publish_readsFromTheCursor_notTheWholeLog() {
        var publisher = publisher();
        var now = Instant.now();

        for (int i = 0; i < 100; i++) {
            log.add(event("e" + i, now, "id-" + i));
        }
        publisher.publish();
        log.add(event("e100", now, "id-100"));
        publisher.publish();

        assertThat(readsFrom).containsExactly(0L, 100L - EventWebSocketPublisher.OVERLAP);
    }

    /// Remembered keys are forgotten after DEDUP_MEMORY_MS, so the set is bounded by what can still be duplicated.
    @Test
    void publish_rememberedKeys_areForgottenAfterTheDedupMemory() {
        var publisher = publisher();

        log.add(event("old", Instant.now(), "id-1"));
        publisher.publish();
        assertThat(publisher.rememberedBroadcasts()).as("control").isEqualTo(1);

        clock.addAndGet(EventWebSocketPublisher.DEDUP_MEMORY_MS + 1);
        publisher.publish();

        assertThat(publisher.rememberedBroadcasts()).isZero();
    }

    private EventWebSocketPublisher publisher() {
        return EventWebSocketPublisher.eventWebSocketPublisher(new CapturingHandler(broadcasts),
                                                               this::eventsFrom,
                                                               events -> events.stream()
                                                                               .map(ClusterEvent::summary)
                                                                               .toList()
                                                                               .toString(),
                                                               clock::get);
    }

    private Promise<EventPage> eventsFrom(long fromOffset) {
        readsFrom.add(fromOffset);

        var from = (int) Math.min(fromOffset, log.size());

        return Promise.success(new EventPage(List.copyOf(log.subList(from, log.size())), log.size()));
    }

    private static ClusterEvent event(String summary, Instant at, String eventId) {
        return new ClusterEvent.AlertInjected(new HlcTimestamp(HlcTimestamp.pack(at.toEpochMilli(), 0), new NodeId("ws-test")),
                                              ClusterEvent.Severity.INFO,
                                              summary,
                                              Map.of(ClusterEventIdentity.EVENT_ID, eventId));
    }

    private static final class CapturingHandler extends EventWebSocketHandler {
        private final List<String> broadcasts;

        private CapturingHandler(List<String> broadcasts) {
            super(WebSocketAuthenticator.webSocketAuthenticator(SecurityValidator.apiKeyValidator(Set.of("k")), false));
            this.broadcasts = broadcasts;
        }

        @Override
        public int connectedClients() {
            return 1;
        }

        @Override
        public void broadcast(String message) {
            broadcasts.add(message);
        }
    }
}
