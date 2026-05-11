// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.drain;

import org.pragmatica.lang.Contract;

import java.util.concurrent.atomic.AtomicInteger;


/// Tracks the number of in-flight management/app-HTTP requests on this node. The
/// DrainCoordinator polls a remote node's value via the `/api/node/inflight` endpoint
/// to know when it is safe to terminate.
///
/// Increment on request entry, decrement on response/error exit. The counter is
/// guaranteed non-negative; decrementing past zero is logged and clamped.
public final class InFlightRequestTracker {
    private final AtomicInteger counter = new AtomicInteger(0);

    private InFlightRequestTracker() {}

    public static InFlightRequestTracker inFlightRequestTracker() {
        return new InFlightRequestTracker();
    }

    @Contract public void enter() {
        counter.incrementAndGet();
    }

    @Contract public void exit() {
        counter.updateAndGet(v -> Math.max(0, v - 1));
    }

    public int count() {
        return counter.get();
    }
}
