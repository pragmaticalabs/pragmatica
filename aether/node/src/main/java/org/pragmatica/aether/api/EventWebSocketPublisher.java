// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@SuppressWarnings("JBCT-RET-01")
public class EventWebSocketPublisher {
    private static final Logger log = LoggerFactory.getLogger(EventWebSocketPublisher.class);

    private final EventWebSocketHandler handler;
    private final Function<Instant, Promise<List<ClusterEvent>>> eventsSinceProvider;
    private final Function<List<ClusterEvent>, String> jsonSerializer;
    private final long intervalMs;

    private final AtomicReference<Option<ScheduledFuture<?>>> taskRef = new AtomicReference<>(Option.none());

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<Instant> lastBroadcast = new AtomicReference<>(Instant.EPOCH);

    private EventWebSocketPublisher(EventWebSocketHandler handler,
                                    Function<Instant, Promise<List<ClusterEvent>>> eventsSinceProvider,
                                    Function<List<ClusterEvent>, String> jsonSerializer,
                                    long intervalMs) {
        this.handler = handler;
        this.eventsSinceProvider = eventsSinceProvider;
        this.jsonSerializer = jsonSerializer;
        this.intervalMs = intervalMs;
    }

    public static EventWebSocketPublisher eventWebSocketPublisher(EventWebSocketHandler handler,
                                                                  Function<Instant, Promise<List<ClusterEvent>>> eventsSinceProvider,
                                                                  Function<List<ClusterEvent>, String> jsonSerializer,
                                                                  long intervalMs) {
        return new EventWebSocketPublisher(handler, eventsSinceProvider, jsonSerializer, intervalMs);
    }

    public static EventWebSocketPublisher eventWebSocketPublisher(EventWebSocketHandler handler,
                                                                  Function<Instant, Promise<List<ClusterEvent>>> eventsSinceProvider,
                                                                  Function<List<ClusterEvent>, String> jsonSerializer) {
        return new EventWebSocketPublisher(handler, eventsSinceProvider, jsonSerializer, 1000);
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        taskRef.set(Option.some(SharedScheduler.scheduleAtFixedRate(this::publish,
                                                                    TimeSpan.timeSpan(intervalMs).millis())));
        log.info("Event WebSocket publisher started ({}ms interval)", intervalMs);
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        taskRef.getAndSet(Option.none()).onPresent(task -> task.cancel(false));
        log.info("Event WebSocket publisher stopped");
    }

    private void publish() {
        if (handler.connectedClients() == 0) {
            return;
        }

        var since = lastBroadcast.get();
        var now = Instant.now();
        Promise<?> ignored = eventsSinceProvider.apply(since)
                                                .onSuccess(events -> broadcastIfPresent(events, now))
                                                .onFailure(cause -> log.error("Error publishing events via WebSocket: {}",
                                                                              cause.message()));
    }

    private void broadcastIfPresent(List<ClusterEvent> newEvents, Instant now) {
        if (!newEvents.isEmpty()) {
            var json = jsonSerializer.apply(newEvents);

            handler.broadcast(json);
        }

        lastBroadcast.set(now);
    }
}
