// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
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
    /// #1640 / #1653 round 2: the keys ([ClusterEventIdentity#key]) of events already broadcast. Each poll reads the
    /// whole retained log and sends only what is not in this set, with NO time window. A redelivered event keeps its
    /// original `at`, and it can land long after that `at` (up to the redelivery horizon, plus a backoff and a
    /// forward timeout). Any fixed look-back would have a boundary it misses. The set is pruned to the keys still
    /// in the retained log, which bounds it by the stream's retention.
    private final Set<String> broadcast = ConcurrentHashMap.newKeySet();

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

    /// One poll. Package-private so the #1640 tests can drive it without the scheduler.
    void publish() {
        if (handler.connectedClients() == 0) {
            return;
        }

        Promise<?> ignored = eventsSinceProvider.apply(Instant.EPOCH)
                                                .onSuccess(this::broadcastNew)
                                                .onFailure(cause -> log.error("Error publishing events via WebSocket: {}",
                                                                              cause.message()));
    }

    private void broadcastNew(List<ClusterEvent> retained) {
        var newEvents = notYetBroadcast(retained);

        pruneToRetained(retained);
        if (!newEvents.isEmpty()) {
            handler.broadcast(jsonSerializer.apply(newEvents));
        }
    }

    private List<ClusterEvent> notYetBroadcast(List<ClusterEvent> retained) {
        return retained.stream()
                       .filter(event -> broadcast.add(ClusterEventIdentity.key(event)))
                       .toList();
    }

    /// Keys of events that left the retained log can never be read again, so they are forgotten.
    private void pruneToRetained(List<ClusterEvent> retained) {
        var retainedKeys = retained.stream()
                                   .map(ClusterEventIdentity::key)
                                   .collect(Collectors.toSet());

        broadcast.retainAll(retainedKeys);
    }

    /// Keys remembered as broadcast (observability for the #1653 prune pin).
    int rememberedBroadcasts() {
        return broadcast.size();
    }
}
