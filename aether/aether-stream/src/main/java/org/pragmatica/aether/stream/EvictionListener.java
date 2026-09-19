// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// Takes the events a ring is about to reclaim (#1234). Success means the listener now owns them — the ring
/// reclaims their space at once, and making them durable is the listener's job (the partition WAL holds them
/// meanwhile). A failure is a refusal: the ring keeps the events, and an append that needed their room fails
/// with the listener's cause. [#NOOP] persists nothing and never refuses.
@FunctionalInterface
public interface EvictionListener {
    Result<Unit> onEviction(String streamName, int partition, List<OffHeapRingBuffer.RawEvent> events);

    /// Whether `offset` was handed over and is not yet durably stored — a read of it will succeed once the
    /// listener finishes, so it is in flight rather than lost. Only a listener that persists can say yes.
    default boolean holdsUnsealed(String streamName, int partition, long offset) {
        return false;
    }

    EvictionListener NOOP = (_, _, _) -> Result.unitResult();
}
