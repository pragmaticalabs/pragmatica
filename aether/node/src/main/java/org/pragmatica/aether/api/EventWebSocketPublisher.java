// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;

import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@SuppressWarnings("JBCT-RET-01")
public class EventWebSocketPublisher {
    private static final Logger log = LoggerFactory.getLogger(EventWebSocketPublisher.class);

    private final EventWebSocketHandler handler;
    private final Supplier<List<ClusterEvent>> allEventsProvider;
    private final Function<List<ClusterEvent>, String> jsonSerializer;
    private final long intervalMs;

    private final AtomicReference<Option<ScheduledFuture<?>>> taskRef = new AtomicReference<>(Option.none());

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger lastPublishedCount = new AtomicInteger(0);

    private EventWebSocketPublisher(EventWebSocketHandler handler,
                                    Supplier<List<ClusterEvent>> allEventsProvider,
                                    Function<List<ClusterEvent>, String> jsonSerializer,
                                    long intervalMs) {
        this.handler = handler;
        this.allEventsProvider = allEventsProvider;
        this.jsonSerializer = jsonSerializer;
        this.intervalMs = intervalMs;
    }

    public static EventWebSocketPublisher eventWebSocketPublisher(EventWebSocketHandler handler,
                                                                  Supplier<List<ClusterEvent>> allEventsProvider,
                                                                  Function<List<ClusterEvent>, String> jsonSerializer,
                                                                  long intervalMs) {
        return new EventWebSocketPublisher(handler, allEventsProvider, jsonSerializer, intervalMs);
    }

    public static EventWebSocketPublisher eventWebSocketPublisher(EventWebSocketHandler handler,
                                                                  Supplier<List<ClusterEvent>> allEventsProvider,
                                                                  Function<List<ClusterEvent>, String> jsonSerializer) {
        return new EventWebSocketPublisher(handler, allEventsProvider, jsonSerializer, 1000);
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {return;}

        taskRef.set(Option.some(SharedScheduler.scheduleAtFixedRate(this::publish,
                                                                    TimeSpan.timeSpan(intervalMs).millis())));
        log.info("Event WebSocket publisher started ({}ms interval)", intervalMs);
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {return;}

        taskRef.getAndSet(Option.none()).onPresent(task -> task.cancel(false));
        log.info("Event WebSocket publisher stopped");
    }

    private void publish() {
        if (handler.connectedClients() == 0) {return;}
        try {
            var all = allEventsProvider.get();
            var last = lastPublishedCount.get();

            if (last <all.size()) {
                var newEvents = all.subList(last, all.size());
                handler.broadcast(jsonSerializer.apply(newEvents));
                lastPublishedCount.set(all.size());
            }
        } catch (Exception e) {
            log.error("Error publishing events via WebSocket", e);
        }
    }
}
