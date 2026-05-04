// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.pragmatica.consensus.NodeId;

import java.util.concurrent.atomic.AtomicLong;


/// Per-node {@link EventId} allocator (spec §6.4.1).
///
/// Thread-safe monotonic sequence stamped with the local {@link NodeId}. Each call to
/// {@link #next()} returns an `EventId` strictly greater than every prior one issued by this
/// allocator instance.
///
/// One allocator per node — sharing across nodes would violate the per-node monotonicity
/// invariant that {@link EventId#compareTo} relies on.
public final class EventIdAllocator {
    private final NodeId nodeId;
    private final AtomicLong counter = new AtomicLong(0);

    private EventIdAllocator(NodeId nodeId) {
        this.nodeId = nodeId;
    }

    public static EventIdAllocator eventIdAllocator(NodeId nodeId) {
        return new EventIdAllocator(nodeId);
    }

    public EventId next() {
        return new EventId(counter.incrementAndGet(), nodeId);
    }
}
