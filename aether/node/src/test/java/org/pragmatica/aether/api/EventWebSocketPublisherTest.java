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

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.security.SecurityValidator;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;

/// #1640 / #1653 round 2: the live event feed sends an event that lands late exactly once, however late. A
/// redelivered event keeps the `at` of its first attempt, which can be older than any fixed look-back window.
class EventWebSocketPublisherTest {
    private final List<ClusterEvent> log = new CopyOnWriteArrayList<>();
    private final List<String> broadcasts = new CopyOnWriteArrayList<>();

    /// The boundary v1640 found: a retry can land at an age beyond any fixed look-back (304.5 s against 300 s).
    /// With no time window at all, an event older than the redelivery horizon plus the maximum backoff still goes
    /// out once, and nothing repeats.
    @Test
    void publish_eventLandsLaterThanHorizonPlusBackoff_isBroadcastOnce_andNothingIsRepeated() {
        var publisher = publisher();
        var now = Instant.now();
        var beyondAnyRetry = Duration.ofMillis(ClusterEventRedelivery.RETRY_HORIZON_MS + ClusterEventRedelivery.MAX_BACKOFF_MS)
                                     .plusSeconds(30);

        log.add(event("on-time", now.minusSeconds(1)));
        publisher.publish();

        log.add(event("late", now.minus(beyondAnyRetry)));
        publisher.publish();
        publisher.publish();

        assertThat(broadcasts).containsExactly("[on-time]", "[late]");
    }

    /// The remembered set is pruned to what the retained log still holds, so it is bounded by retention.
    @Test
    void publish_eventLeavesTheRetainedLog_isForgotten() {
        var publisher = publisher();
        var now = Instant.now();
        var evicted = event("evicted", now.minusSeconds(2));

        log.add(evicted);
        log.add(event("kept", now.minusSeconds(1)));
        publisher.publish();
        assertThat(publisher.rememberedBroadcasts()).as("control").isEqualTo(2);

        log.remove(evicted);
        publisher.publish();

        assertThat(publisher.rememberedBroadcasts()).isEqualTo(1);
    }

    private EventWebSocketPublisher publisher() {
        return EventWebSocketPublisher.eventWebSocketPublisher(new CapturingHandler(broadcasts),
                                                               this::eventsSince,
                                                               events -> events.stream()
                                                                               .map(ClusterEvent::summary)
                                                                               .toList()
                                                                               .toString());
    }

    private Promise<List<ClusterEvent>> eventsSince(Instant since) {
        return Promise.success(log.stream()
                                  .filter(event -> event.at()
                                                        .physicalMillis() > since.toEpochMilli())
                                  .toList());
    }

    private static ClusterEvent event(String summary, Instant at) {
        return new ClusterEvent.AlertInjected(new HlcTimestamp(HlcTimestamp.pack(at.toEpochMilli(), 0), new NodeId("ws-test")),
                                              ClusterEvent.Severity.INFO,
                                              summary,
                                              Map.of());
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
