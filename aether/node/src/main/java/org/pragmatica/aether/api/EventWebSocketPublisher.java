// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.LongSupplier;

import org.pragmatica.aether.api.ClusterEventAggregator.EventPage;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Pushes new cluster events to connected WebSocket clients once a second.
///
/// **An offset cursor, not a time window (#1640, #1653).** Each poll reads the log from the cursor, which is the offset
/// after the last event it read, and moves the cursor past what it read. Every event is appended, and so is a
/// redelivered one: it lands at an offset beyond the cursor however old its `at` is. It is therefore read by the next
/// poll by construction, and each poll costs only what is new. The first poll starts at offset 0, which the read clamps
/// to the oldest retained event, so a first client sees the retained history once. The read starts [#OVERLAP] events
/// before the cursor, a cheap guard against reading a prefix that a replica had not finished applying. The seen-set
/// makes that overlap harmless.
///
/// **Duplicates.** A redelivered event whose first publish also landed is in the log twice, with one `details.eventId`
/// ([ClusterEventIdentity]). Keys already broadcast are remembered for [#DEDUP_MEMORY_MS], which is derived from the
/// redelivery bounds: the latest a retry can land after the first copy is the retry horizon plus one maximum backoff
/// plus a forward timeout. A copy landing later than that would be sent a second time. That is the safe direction: a
/// duplicate, never a miss.
@SuppressWarnings("JBCT-RET-01")
public class EventWebSocketPublisher {
    private static final Logger log = LoggerFactory.getLogger(EventWebSocketPublisher.class);
    /// Events re-read before the cursor on each poll.
    static final long OVERLAP = 16;

    /// Upper bound on how long after an event's first copy a duplicate copy can land, plus margin.
    static final long DEDUP_MEMORY_MS = ClusterEventRedelivery.RETRY_HORIZON_MS + ClusterEventRedelivery.MAX_BACKOFF_MS + 60_000L;

    private final EventWebSocketHandler handler;
    private final Function<Long, Promise<EventPage>> eventsFrom;
    private final Function<List<ClusterEvent>, String> jsonSerializer;
    private final long intervalMs;
    private final LongSupplier clock;

    private final AtomicReference<Option<ScheduledFuture<?>>> taskRef = new AtomicReference<>(Option.none());

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong cursor = new AtomicLong();
    /// Key ([ClusterEventIdentity#key]) → when it was broadcast, for [#DEDUP_MEMORY_MS].
    private final Map<String, Long> broadcast = new ConcurrentHashMap<>();

    private EventWebSocketPublisher(EventWebSocketHandler handler,
                                    Function<Long, Promise<EventPage>> eventsFrom,
                                    Function<List<ClusterEvent>, String> jsonSerializer,
                                    long intervalMs,
                                    LongSupplier clock) {
        this.handler = handler;
        this.eventsFrom = eventsFrom;
        this.jsonSerializer = jsonSerializer;
        this.intervalMs = intervalMs;
        this.clock = clock;
    }

    public static EventWebSocketPublisher eventWebSocketPublisher(EventWebSocketHandler handler,
                                                                  Function<Long, Promise<EventPage>> eventsFrom,
                                                                  Function<List<ClusterEvent>, String> jsonSerializer,
                                                                  long intervalMs) {
        return new EventWebSocketPublisher(handler, eventsFrom, jsonSerializer, intervalMs, System::currentTimeMillis);
    }

    public static EventWebSocketPublisher eventWebSocketPublisher(EventWebSocketHandler handler,
                                                                  Function<Long, Promise<EventPage>> eventsFrom,
                                                                  Function<List<ClusterEvent>, String> jsonSerializer) {
        return new EventWebSocketPublisher(handler, eventsFrom, jsonSerializer, 1000, System::currentTimeMillis);
    }

    /// Test seam: an explicit clock for [#DEDUP_MEMORY_MS].
    static EventWebSocketPublisher eventWebSocketPublisher(EventWebSocketHandler handler,
                                                           Function<Long, Promise<EventPage>> eventsFrom,
                                                           Function<List<ClusterEvent>, String> jsonSerializer,
                                                           LongSupplier clock) {
        return new EventWebSocketPublisher(handler, eventsFrom, jsonSerializer, 1000, clock);
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

        Promise<?> ignored = eventsFrom.apply(Math.max(0,
                                                       cursor.get() - OVERLAP))
                                       .onSuccess(this::broadcastNew)
                                       .onFailure(cause -> log.error("Error publishing events via WebSocket: {}",
                                                                     cause.message()));
    }

    private void broadcastNew(EventPage page) {
        var now = clock.getAsLong();
        var newEvents = notYetBroadcast(page.events(), now);

        cursor.accumulateAndGet(page.nextOffset(), Math::max);
        forgetBefore(now - DEDUP_MEMORY_MS);
        if (!newEvents.isEmpty()) {
            handler.broadcast(jsonSerializer.apply(newEvents));
        }
    }

    private List<ClusterEvent> notYetBroadcast(List<ClusterEvent> events, long now) {
        return events.stream()
                     .filter(event -> broadcast.putIfAbsent(ClusterEventIdentity.key(event),
                                                            now) == null)
                     .toList();
    }

    private void forgetBefore(long horizon) {
        broadcast.values().removeIf(broadcastAt -> broadcastAt < horizon);
    }

    /// Keys remembered as broadcast (observability for the #1653 prune pin).
    int rememberedBroadcasts() {
        return broadcast.size();
    }
}
