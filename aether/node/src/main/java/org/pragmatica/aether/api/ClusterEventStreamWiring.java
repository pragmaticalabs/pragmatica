// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.stream.FrameworkStreamConsumer;
import org.pragmatica.aether.slice.stream.FrameworkStreamConsumers;
import org.pragmatica.aether.slice.stream.FrameworkStreamPublisher;
import org.pragmatica.aether.slice.stream.FrameworkStreamPublishers;
import org.pragmatica.aether.slice.stream.StreamAddress;
import org.pragmatica.lang.Result;

/// Node-local wiring for the `system:cluster-events:1.0.0` stream (stream-namespaces rebuild,
/// Stage 4). Constructs one shared {@link ClusterEventStreamBuffer} (a bounded, retention-enforcing
/// object ring) and exposes it through both framework SPIs:
///   - a {@link FrameworkStreamPublisher} for {@link ClusterEventAggregator#emit}, and
///   - a {@link FrameworkStreamConsumer} for `events()` / `eventsSince(...)`.
///
/// Both halves close over the SAME buffer instance, so a published event is immediately visible to
/// the consumer on the same node. The framework-namespace invariant is preserved: construction flows
/// through `FrameworkStreamPublishers` / `FrameworkStreamConsumers`, which reject non-`system:*`
/// addresses.
public final class ClusterEventStreamWiring {
    private final FrameworkStreamPublisher<ClusterEvent> publisher;
    private final FrameworkStreamConsumer<ClusterEvent> consumer;

    private ClusterEventStreamWiring(FrameworkStreamPublisher<ClusterEvent> publisher,
                                     FrameworkStreamConsumer<ClusterEvent> consumer) {
        this.publisher = publisher;
        this.consumer = consumer;
    }

    public FrameworkStreamPublisher<ClusterEvent> publisher() {
        return publisher;
    }

    public FrameworkStreamConsumer<ClusterEvent> consumer() {
        return consumer;
    }

    /// Build the publisher+consumer pair for `address` (must be `system:*`) backed by a single
    /// bounded buffer sized by `retention`. Returns a failure only if the address is not a system
    /// address (defensive; the caller always passes `SystemStreams.CLUSTER_EVENTS`).
    public static Result<ClusterEventStreamWiring> clusterEventStreamWiring(StreamAddress address, RetentionPolicy retention) {
        var buffer = ClusterEventStreamBuffer.clusterEventStreamBuffer(address.asString(), retention);
        return FrameworkStreamPublishers.<ClusterEvent>systemStreamPublisher(address, event -> buffer.publish(event).mapToUnit())
                .flatMap(pub -> FrameworkStreamConsumers.systemStreamConsumer(address, buffer)
                                                        .map(con -> new ClusterEventStreamWiring(pub, con)));
    }
}
