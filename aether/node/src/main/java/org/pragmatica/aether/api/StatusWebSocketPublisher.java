// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@SuppressWarnings("JBCT-RET-01")
public class StatusWebSocketPublisher {
    private static final Logger log = LoggerFactory.getLogger(StatusWebSocketPublisher.class);

    private final StatusWebSocketHandler handler;
    private final Supplier<String> jsonSupplier;
    private final long intervalMs;

    private final AtomicReference<Option<ScheduledFuture<?>>> taskRef = new AtomicReference<>(Option.none());

    private final AtomicBoolean running = new AtomicBoolean(false);

    private StatusWebSocketPublisher(StatusWebSocketHandler handler, Supplier<String> jsonSupplier, long intervalMs) {
        this.handler = handler;
        this.jsonSupplier = jsonSupplier;
        this.intervalMs = intervalMs;
    }

    public static StatusWebSocketPublisher statusWebSocketPublisher(StatusWebSocketHandler handler,
                                                                    Supplier<String> jsonSupplier,
                                                                    long intervalMs) {
        return new StatusWebSocketPublisher(handler, jsonSupplier, intervalMs);
    }

    public static StatusWebSocketPublisher statusWebSocketPublisher(StatusWebSocketHandler handler,
                                                                    Supplier<String> jsonSupplier) {
        return new StatusWebSocketPublisher(handler, jsonSupplier, 1000);
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {return;}

        taskRef.set(Option.some(SharedScheduler.scheduleAtFixedRate(this::publish,
                                                                    TimeSpan.timeSpan(intervalMs).millis())));
        log.info("Status WebSocket publisher started ({}ms interval)", intervalMs);
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {return;}

        taskRef.getAndSet(Option.none()).onPresent(task -> task.cancel(false));
        log.info("Status WebSocket publisher stopped");
    }

    private void publish() {
        if (handler.connectedClients() == 0) {return;}
        try {
            var json = jsonSupplier.get();
            handler.broadcast(json);
        } catch (Exception e) {
            log.error("Error publishing status via WebSocket", e);
        }
    }
}
