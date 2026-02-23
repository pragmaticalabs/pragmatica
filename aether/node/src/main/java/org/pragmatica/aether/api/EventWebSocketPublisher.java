package org.pragmatica.aether.api;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Scheduled publisher that broadcasts new cluster events via WebSocket.
/// Only pushes events that occurred since the last broadcast (delta mode).
/// Short-circuits when no clients are connected.
@SuppressWarnings("JBCT-RET-01")
public class EventWebSocketPublisher {
    private static final Logger log = LoggerFactory.getLogger(EventWebSocketPublisher.class);

    private final EventWebSocketHandler handler;
    private final Function<Instant, List<ClusterEvent>> eventsSinceProvider;
    private final Function<List<ClusterEvent>, String> jsonSerializer;
    private final long intervalMs;
    private final AtomicReference<ScheduledExecutorService> schedulerRef = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<Instant> lastBroadcast = new AtomicReference<>(Instant.EPOCH);

    private EventWebSocketPublisher(EventWebSocketHandler handler,
                                    Function<Instant, List<ClusterEvent>> eventsSinceProvider,
                                    Function<List<ClusterEvent>, String> jsonSerializer,
                                    long intervalMs) {
        this.handler = handler;
        this.eventsSinceProvider = eventsSinceProvider;
        this.jsonSerializer = jsonSerializer;
        this.intervalMs = intervalMs;
    }

    public static EventWebSocketPublisher eventWebSocketPublisher(EventWebSocketHandler handler,
                                                                   Function<Instant, List<ClusterEvent>> eventsSinceProvider,
                                                                   Function<List<ClusterEvent>, String> jsonSerializer,
                                                                   long intervalMs) {
        return new EventWebSocketPublisher(handler, eventsSinceProvider, jsonSerializer, intervalMs);
    }

    public static EventWebSocketPublisher eventWebSocketPublisher(EventWebSocketHandler handler,
                                                                   Function<Instant, List<ClusterEvent>> eventsSinceProvider,
                                                                   Function<List<ClusterEvent>, String> jsonSerializer) {
        return new EventWebSocketPublisher(handler, eventsSinceProvider, jsonSerializer, 1000);
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        var scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                                                                       var thread = new Thread(r, "event-ws-publisher");
                                                                       thread.setDaemon(true);
                                                                       return thread;
                                                                   });
        schedulerRef.set(scheduler);
        scheduler.scheduleAtFixedRate(this::publish, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("Event WebSocket publisher started ({}ms interval)", intervalMs);
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        var scheduler = schedulerRef.getAndSet(null);
        if (scheduler != null) {
            scheduler.shutdown();
        }
        log.info("Event WebSocket publisher stopped");
    }

    private void publish() {
        if (handler.connectedClients() == 0) {
            return;
        }
        try{
            var since = lastBroadcast.get();
            var now = Instant.now();
            var newEvents = eventsSinceProvider.apply(since);
            if (!newEvents.isEmpty()) {
                var json = jsonSerializer.apply(newEvents);
                handler.broadcast(json);
            }
            lastBroadcast.set(now);
        } catch (Exception e) {
            log.error("Error publishing events via WebSocket", e);
        }
    }
}
