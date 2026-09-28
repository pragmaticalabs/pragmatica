// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.utility.ULID;


/// The identity that makes a cluster event's copies recognisable as ONE event (#1640, #1653 round 2).
///
/// A publish whose outcome was unknown may have landed, and redelivery then sends the same event again, so the
/// log can hold two copies. `at` alone is not a safe key: two distinct events from one node can share it (the same
/// node restarting within the same millisecond after a clock step, for example). So each event gets an id when this
/// node first delivers it, `details.eventId = <incarnation>:<sequence>`, where the incarnation is a ULID minted once
/// per aggregator (per node process). Retries re-send the stamped event, so every copy carries the same id.
///
/// The id travels in `details`, which every variant already carries as a `Map<String, String>`, so this is no wire
/// or codec change. The copy is made through the record's canonical constructor, which works for every variant
/// without per-type code; a variant that is not a record (an `ExtendedEvent` plugin) keeps no id, and is
/// de-duplicated by `at` as before.
final class ClusterEventIdentity {
    static final String EVENT_ID = "eventId";

    private final String incarnation = ULID.ulid().encoded();
    private final AtomicLong sequence = new AtomicLong();

    private ClusterEventIdentity() {}

    static ClusterEventIdentity clusterEventIdentity() {
        return new ClusterEventIdentity();
    }

    /// `event` with a fresh `details.eventId`, or `event` unchanged when it already has one or cannot be copied.
    ClusterEvent stamped(ClusterEvent event) {
        return event.details()
                    .containsKey(EVENT_ID)
               ? event
               : withEventId(event, incarnation + ":" + sequence.incrementAndGet());
    }

    /// The key two copies of one event share: its `eventId`, or `at` for an event without one.
    static String key(ClusterEvent event) {
        return event.details()
                    .getOrDefault(EVENT_ID,
                                  "at:" + event.at());
    }

    private static ClusterEvent withEventId(ClusterEvent event, String eventId) {
        return event.getClass()
                    .isRecord()
               ? copyWithDetails(event, eventId).or(event)
               : event;
    }

    /// A copy of the record `event` through its canonical constructor, with `details` replaced. Any reflective
    /// failure leaves the caller with the original event (de-duplicated by `at`, as before).
    private static Result<ClusterEvent> copyWithDetails(ClusterEvent event, String eventId) {
        var components = event.getClass().getRecordComponents();

        return Result.allOf(Arrays.stream(components).map(component -> argument(event, component, eventId)).toList()).flatMap(arguments -> construct(event,
                                                                                                                                                     components,
                                                                                                                                                     arguments));
    }

    private static Result<Object> argument(ClusterEvent event, RecordComponent component, String eventId) {
        return "details".equals(component.getName())
               ? Result.success(detailsWithId(event.details(), eventId))
               : Result.lift(Causes::fromThrowable,
                             () -> component.getAccessor()
                                            .invoke(event));
    }

    private static Result<ClusterEvent> construct(ClusterEvent event,
                                                  RecordComponent[] components,
                                                  List<Object> arguments) {
        var types = Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new);

        return Result.lift(Causes::fromThrowable,
                           () -> (ClusterEvent) event.getClass()
                                                     .getDeclaredConstructor(types)
                                                     .newInstance(arguments.toArray()));
    }

    private static Map<String, String> detailsWithId(Map<String, String> details, String eventId) {
        var enriched = new HashMap<>(details);

        enriched.put(EVENT_ID, eventId);

        return Map.copyOf(enriched);
    }
}
