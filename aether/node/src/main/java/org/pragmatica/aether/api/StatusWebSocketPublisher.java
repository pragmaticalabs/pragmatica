package org.pragmatica.aether.api;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scheduled publisher that broadcasts status JSON via WebSocket.
 * Short-circuits when no clients are connected.
 */
public class StatusWebSocketPublisher {
    private static final Logger log = LoggerFactory.getLogger(StatusWebSocketPublisher.class);

    private final StatusWebSocketHandler handler;
    private final Supplier<String> jsonSupplier;
    private final long intervalMs;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private StatusWebSocketPublisher(StatusWebSocketHandler handler,
                                     Supplier<String> jsonSupplier,
                                     long intervalMs) {
        this.handler = handler;
        this.jsonSupplier = jsonSupplier;
        this.intervalMs = intervalMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r, "status-ws-publisher");
            thread.setDaemon(true);
            return thread;
        });
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
        if (!running.compareAndSet(false, true)) {
            return;
        }
        scheduler.scheduleAtFixedRate(this::publish,
                                      intervalMs,
                                      intervalMs,
                                      TimeUnit.MILLISECONDS);
        log.info("Status WebSocket publisher started ({}ms interval)", intervalMs);
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        scheduler.shutdown();
        log.info("Status WebSocket publisher stopped");
    }

    private void publish() {
        if (handler.connectedClients() == 0) {
            return;
        }
        try {
            var json = jsonSupplier.get();
            handler.broadcast(json);
        } catch (Exception e) {
            log.error("Error publishing status via WebSocket", e);
        }
    }
}
