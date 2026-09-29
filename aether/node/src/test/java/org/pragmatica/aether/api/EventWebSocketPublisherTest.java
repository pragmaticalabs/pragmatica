// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.api.ClusterEventAggregator.EventPage;
import org.pragmatica.aether.api.ClusterEventAggregator.LandedEvent;
import org.pragmatica.aether.http.security.SecurityValidator;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;

/// #1640 / #1653: the live event feed follows an offset cursor. A late-landing event is appended beyond the cursor
/// and sent once however old its `at` is; a copy of an already-sent event is not sent again; each poll reads only from
/// the cursor; after an owner failover that reuses offsets, the feed re-reads the new owner's log instead of skipping.
///
/// The fake log models `ClusterEventAggregator.page()`: offsets are list indices, the read starts at
/// `max(from, tail)`, and `nextOffset` is the last offset read plus one, or `from` when nothing was read.
class EventWebSocketPublisherTest {
    private final List<ClusterEvent> log = new CopyOnWriteArrayList<>();
    private final List<String> broadcasts = new CopyOnWriteArrayList<>();
    private final List<Long> readsFrom = new CopyOnWriteArrayList<>();
    private final AtomicLong tail = new AtomicLong();
    private final AtomicLong ownershipChanges = new AtomicLong();

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

    /// v1640 V3: the key is the `eventId`, not `at`. Two distinct events that share `at` are both sent.
    @Test
    void publish_twoDistinctEventsWithTheSameAt_areBothSent() {
        var publisher = publisher();
        var now = Instant.now();

        log.add(event("first", now, "id-1"));
        log.add(event("second", now, "id-2"));
        publisher.publish();

        assertThat(broadcasts).containsExactly("[first, second]");
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

    /// v1640 B1: on a quiet log every poll re-reads the overlap. Its events stay remembered while they are retained,
    /// however long the log stays quiet, so nothing is sent twice.
    @Test
    void publish_quietLog_sentEventsAreNeverSentAgain() {
        var publisher = publisher();
        var now = Instant.now();

        log.add(event("a", now, "id-a"));
        log.add(event("b", now, "id-b"));
        for (int poll = 0; poll < 50; poll++) {
            publisher.publish();
        }

        assertThat(broadcasts).containsExactly("[a, b]");
    }

    /// Remembered keys are forgotten once their offset falls below the retained tail, so the set is bounded by
    /// retention.
    @Test
    void publish_eventLeavesTheRetainedLog_isForgotten() {
        var publisher = publisher();
        var now = Instant.now();

        log.add(event("old", now, "id-1"));
        log.add(event("kept", now, "id-2"));
        publisher.publish();
        assertThat(publisher.rememberedBroadcasts()).as("control").isEqualTo(2);

        tail.set(1);
        publisher.publish();

        assertThat(publisher.rememberedBroadcasts()).isEqualTo(1);
        assertThat(broadcasts).as("nothing re-sent").containsExactly("[old, kept]");
    }

    /// v1640 B2, the node applied the ownership change: the new owner kept 20 of 40 read events and appended 25 new
    /// ones at offsets 20..44, past the old cursor (40). No page ends below the cursor, so only the ownership change
    /// tells the feed to re-read; without it `new0..new3` (offsets 20..23, below cursor − overlap) are never sent.
    @Test
    void publish_ownerChangeReusesMoreThanTheOverlap_andPassesTheCursor_everyNewEventIsSentOnce() {
        var publisher = publisher();

        appendEvents("old", 40);
        publisher.publish();
        failOver(20);
        ownershipChanges.incrementAndGet();
        appendEvents("new", 25);
        publisher.publish();
        publisher.publish();
        publisher.publish();

        assertThat(sentSummaries()).as("every event sent exactly once")
                                   .containsExactlyInAnyOrderElementsOf(expected(40, 25));
    }

    /// v1640 B2, before the node applied the ownership change: the log read has moved back below the cursor (head 22
    /// against cursor 40), which alone tells the feed to re-read. The rest of the new events arrive later.
    @Test
    void publish_logMovesBackBelowTheCursor_reusingMoreThanTheOverlap_everyNewEventIsSentOnce() {
        var publisher = publisher();

        appendEvents("old", 40);
        publisher.publish();
        failOver(20);
        appendEvents("new", 2);
        publisher.publish();
        publisher.publish();
        appendEvents("new", 2, 25);
        publisher.publish();
        publisher.publish();

        assertThat(sentSummaries()).as("every event sent exactly once")
                                   .containsExactlyInAnyOrderElementsOf(expected(40, 25));
    }

    /// Control, the 2-offset reuse the Ember run measured: it stays inside the overlap and needs no re-anchor.
    @Test
    void publish_reuseWithinTheOverlap_everyNewEventIsSentOnce() {
        var publisher = publisher();

        appendEvents("old", 40);
        publisher.publish();
        failOver(38);
        appendEvents("new", 5);
        publisher.publish();
        publisher.publish();

        assertThat(sentSummaries()).containsExactlyElementsOf(expected(40, 5));
    }

    private void appendEvents(String prefix, int count) {
        appendEvents(prefix, 0, count);
    }

    private void appendEvents(String prefix, int from, int to) {
        var now = Instant.now();

        IntStream.range(from, to).forEach(i -> log.add(event(prefix + i, now, prefix + "-id-" + i)));
    }

    /// The new owner's log keeps only the first `kept` events; the rest were never replicated.
    private void failOver(int kept) {
        var survivors = new ArrayList<>(log.subList(0, kept));

        log.clear();
        log.addAll(survivors);
    }

    private List<String> sentSummaries() {
        return broadcasts.stream()
                         .flatMap(batch -> List.of(batch.substring(1, batch.length() - 1).split(", ")).stream())
                         .toList();
    }

    private static List<String> expected(int old, int fresh) {
        var all = new ArrayList<String>();

        IntStream.range(0, old).forEach(i -> all.add("old" + i));
        IntStream.range(0, fresh).forEach(i -> all.add("new" + i));
        return all;
    }

    private EventWebSocketPublisher publisher() {
        return EventWebSocketPublisher.eventWebSocketPublisher(new CapturingHandler(broadcasts),
                                                               this::eventsFrom,
                                                               events -> events.stream()
                                                                               .map(ClusterEvent::summary)
                                                                               .toList()
                                                                               .toString());
    }

    private Promise<EventPage> eventsFrom(long fromOffset) {
        readsFrom.add(fromOffset);

        var changes = ownershipChanges.get();
        var start = (int) Math.min(Math.max(fromOffset, tail.get()), log.size());
        var events = IntStream.range(start, log.size())
                              .mapToObj(offset -> new LandedEvent(offset, log.get(offset)))
                              .toList();
        var nextOffset = events.isEmpty()
                         ? fromOffset
                         : events.getLast().offset() + 1;

        return Promise.success(new EventPage(events, nextOffset, tail.get(), changes));
    }

    private static ClusterEvent event(String summary, Instant at, String eventId) {
        return new ClusterEvent.AlertInjected(new HlcTimestamp(HlcTimestamp.pack(at.toEpochMilli(), 0), new NodeId("ws-test")),
                                              ClusterEvent.Severity.INFO,
                                              summary,
                                              Map.of(ClusterEventIdentity.EVENT_ID, eventId));
    }

    static final class CapturingHandler extends EventWebSocketHandler {
        private final List<String> broadcasts;

        CapturingHandler(List<String> broadcasts) {
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
