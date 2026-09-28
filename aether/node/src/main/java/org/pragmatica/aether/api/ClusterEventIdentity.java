// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.utility.ULID;

/// The identity that makes a cluster event's copies recognisable as ONE event (#1640, #1653).
///
/// A publish whose outcome was unknown may have landed, and redelivery then sends the same event again, so the
/// log can hold two copies. `at` alone is not a safe key: two distinct events from one node can share it (the same
/// node restarting within one millisecond after a clock step, for example). So each event gets an id when the
/// aggregator ACCEPTS it, before its first publish attempt: `details.eventId = <incarnation>:<sequence>`, where the
/// incarnation is a ULID minted once per aggregator (per node process). Redelivery re-sends that stamped object, so
/// the first attempt and every retry carry the same id.
///
/// The id travels in `details`, an existing `Map<String, String>` on every variant, so this is no wire or codec
/// change. The copy is [ClusterEvent#withDetail], implemented per variant (compile-checked, no reflection). An
/// `ExtendedEvent` returns itself, keeps no id, and is de-duplicated by `at` as before.
final class ClusterEventIdentity {
    static final String EVENT_ID = "eventId";

    private final String incarnation = ULID.ulid()
                                           .encoded();
    private final AtomicLong sequence = new AtomicLong();

    private ClusterEventIdentity() {}

    static ClusterEventIdentity clusterEventIdentity() {
        return new ClusterEventIdentity();
    }

    /// `event` with a fresh `details.eventId`, or `event` unchanged when it already has one.
    ClusterEvent stamped(ClusterEvent event) {
        return event.details()
                    .containsKey(EVENT_ID)
               ? event
               : event.withDetail(EVENT_ID, incarnation + ":" + sequence.incrementAndGet());
    }

    /// The key two copies of one event share: its `eventId`, or `at` for an event without one.
    static String key(ClusterEvent event) {
        return event.details()
                    .getOrDefault(EVENT_ID, "at:" + event.at());
    }
}
