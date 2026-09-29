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
import java.util.function.Supplier;

import org.pragmatica.aether.api.ClusterEventAggregator.EventPage;
import org.pragmatica.aether.api.ClusterEventAggregator.LandedEvent;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Pushes new cluster events to connected WebSocket clients once a second.
///
/// **An offset cursor, not a time window (#1640, #1653).** Each poll reads the log from the cursor (less [#OVERLAP]
/// events, a guard against a replica that had not finished applying a prefix) and moves the cursor past what it read.
/// Within one owner's log every event is appended, a redelivered one included, so it lands beyond the cursor however
/// old its `at` is and the next poll reads it.
///
/// **Owner failover.** A new owner continues from its own head, which can be BELOW offsets this feed already read, and
/// reuses them for new events. The feed therefore re-anchors, reading again from the oldest retained offset, when the
/// node applies a cluster-events ownership change ([EventPage#ownershipChanges]) or when a page ends below the cursor
/// (the log it reads has moved back). It never relies on the overlap to cover a reuse of unknown size.
///
/// **Sent once.** Every key sent ([ClusterEventIdentity#key]: `details.eventId`, else `at`) is remembered with its
/// offset for as long as that offset is still retained, so neither the overlap, a re-anchor, nor a second copy of a
/// redelivered event (same `eventId`) is sent again while the first copy is retained. Keys below the retained tail are
/// forgotten, which bounds the set by retention. The limit: a copy landing after its first copy was trimmed is sent
/// again, which is a duplicate, never a miss.
@SuppressWarnings("JBCT-RET-01")
public class EventWebSocketPublisher {
    private static final Logger log = LoggerFactory.getLogger(EventWebSocketPublisher.class);
    /// Events re-read before the cursor on each poll.
    static final long OVERLAP = 16;

    private final EventWebSocketHandler handler;
    private final Function<Long, Promise<EventPage>> eventsFrom;
    private final Function<List<ClusterEvent>, String> jsonSerializer;
    private final long intervalMs;

    private final AtomicReference<Option<ScheduledFuture<?>>> taskRef = new AtomicReference<>(Option.none());

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong cursor = new AtomicLong();
    private final AtomicLong ownershipChangesSeen = new AtomicLong();
    /// Key ([ClusterEventIdentity#key]) of every event sent, with the offset it was last read at.
    private final Map<String, Long> sent = new ConcurrentHashMap<>();

    private EventWebSocketPublisher(EventWebSocketHandler handler,
                                    Function<Long, Promise<EventPage>> eventsFrom,
                                    Function<List<ClusterEvent>, String> jsonSerializer,
                                    long intervalMs) {
        this.handler = handler;
        this.eventsFrom = eventsFrom;
        this.jsonSerializer = jsonSerializer;
        this.intervalMs = intervalMs;
    }

    /// Production wiring: the feed reads the node's own aggregator from its cursor.
    public static EventWebSocketPublisher eventWebSocketPublisher(EventWebSocketHandler handler,
                                                                  Supplier<ClusterEventAggregator> aggregator,
                                                                  Function<List<ClusterEvent>, String> jsonSerializer) {
        return new EventWebSocketPublisher(handler,
                                           fromOffset -> aggregator.get()
                                                                   .eventsFrom(fromOffset),
                                           jsonSerializer,
                                           1000);
    }

    /// Test seam: an explicit page source.
    static EventWebSocketPublisher eventWebSocketPublisher(EventWebSocketHandler handler,
                                                           Function<Long, Promise<EventPage>> eventsFrom,
                                                           Function<List<ClusterEvent>, String> jsonSerializer) {
        return new EventWebSocketPublisher(handler, eventsFrom, jsonSerializer, 1000);
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
        var newEvents = notYetSent(page.events());

        forgetBelow(page.tailOffset());
        moveCursor(page);
        if (!newEvents.isEmpty()) {
            handler.broadcast(jsonSerializer.apply(newEvents));
        }
    }

    private List<ClusterEvent> notYetSent(List<LandedEvent> events) {
        return events.stream()
                     .filter(landed -> sent.put(ClusterEventIdentity.key(landed.event()),
                                                landed.offset()) == null)
                     .map(LandedEvent::event)
                     .toList();
    }

    private void forgetBelow(long tailOffset) {
        sent.values().removeIf(offset -> offset < tailOffset);
    }

    /// Re-anchor to the oldest retained offset when the log may be a new owner's; otherwise advance.
    private void moveCursor(EventPage page) {
        var ownerChanged = ownershipChangesSeen.getAndSet(page.ownershipChanges()) != page.ownershipChanges();
        var movedBack = page.nextOffset() < cursor.get();

        if (ownerChanged || movedBack) {
            cursor.set(0);
        } else {
            cursor.accumulateAndGet(page.nextOffset(), Math::max);
        }
    }

    /// Keys remembered as sent (observability for the #1653 bound pin).
    int rememberedBroadcasts() {
        return sent.size();
    }
}
