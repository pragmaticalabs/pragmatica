// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// Receives the events a ring needs to reclaim and makes them durable. The returned promise is the
/// ring's license to reclaim (#1234): the ring keeps the events — readable, and counted against its
/// capacity — until it resolves successfully, and a failure leaves them in place for a later attempt.
/// [#NOOP] is the one listener that persists nothing; a ring built with it reclaims immediately.
@FunctionalInterface
public interface EvictionListener {
    Promise<Unit> onEviction(String streamName, int partition, List<OffHeapRingBuffer.RawEvent> events);
    EvictionListener NOOP = (_, _, _) -> Promise.unitPromise();
}
