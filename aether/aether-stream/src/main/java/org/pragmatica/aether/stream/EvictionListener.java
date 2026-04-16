// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.lang.Contract;

import java.util.List;


/// Callback invoked before events are evicted from the ring buffer.
/// Implementations can capture events for persistent storage (segment sealing).
@FunctionalInterface public interface EvictionListener {
    @Contract void onEviction(String streamName, int partition, List<OffHeapRingBuffer.RawEvent> events);

    EvictionListener NOOP = (_, _, _) -> {};
}
