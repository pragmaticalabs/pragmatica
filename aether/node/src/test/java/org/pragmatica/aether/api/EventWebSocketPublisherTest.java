// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

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

/// #1640: the live event feed sends an event that lands late exactly once. A redelivered event keeps the `at` of
/// its first attempt, which can be older than the feed's previous poll; a feed that polled only from its last
/// broadcast would never send it.
class EventWebSocketPublisherTest {
    private final List<ClusterEvent> log = new CopyOnWriteArrayList<>();
    private final List<String> broadcasts = new CopyOnWriteArrayList<>();

    @Test
    void publish_eventLandsLateWithAnOldAt_isBroadcastOnce_andNothingIsRepeated() {
        var publisher = EventWebSocketPublisher.eventWebSocketPublisher(new CapturingHandler(broadcasts),
                                                                        this::eventsSince,
                                                                        events -> events.stream()
                                                                                        .map(ClusterEvent::summary)
                                                                                        .toList()
                                                                                        .toString());
        var now = Instant.now();

        log.add(event("on-time", now.minusSeconds(1)));
        publisher.publish();

        // Lands after that poll, stamped 30 s ago: a redelivered event keeps its first attempt's `at`.
        log.add(event("late", now.minusSeconds(30)));
        publisher.publish();
        publisher.publish();

        assertThat(broadcasts).containsExactly("[on-time]", "[late]");
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
