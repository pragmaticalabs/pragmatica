// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.drain;

import org.pragmatica.lang.Contract;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;


/// Tracks the number of in-flight management/app-HTTP requests on this node. The
/// `ConsensusDrainCoordinator` polls a remote node's value via the `/api/node/inflight`
/// endpoint to know when it is safe to terminate.
///
/// Increment on request entry, decrement on response/error exit. The counter is
/// guaranteed non-negative; decrementing past zero is logged and clamped.
///
/// Self-drain gate (membership-architecture-spec.md §16.1):
///
/// * `setAcceptingNewWork(false)` flips the gate closed. After that point, `tryEnter()`
///   returns `false` (and `enter()` is a no-op that does NOT increment the counter) so
///   newly arriving requests can be rejected at the HTTP boundary with 503.
/// * Once the in-flight count drops to zero AFTER the gate was closed, the registered
///   `onAllDrained` callback (if any) fires exactly once. This is the signal the
///   `SelfDrainCoordinator` uses to short-circuit the `inflightGrace` timeout and exit
///   the JVM as soon as quiescence is reached.
public final class InFlightRequestTracker {
    private final AtomicInteger counter = new AtomicInteger(0);
    private final AtomicBoolean acceptingNewWork = new AtomicBoolean(true);
    private final AtomicBoolean drainCallbackFired = new AtomicBoolean(false);
    private final AtomicReference<Runnable> onAllDrained = new AtomicReference<>();

    private InFlightRequestTracker() {}

    public static InFlightRequestTracker inFlightRequestTracker() {
        return new InFlightRequestTracker();
    }

    /// Legacy fire-and-forget instrumentation entry point. When the gate is closed
    /// (`setAcceptingNewWork(false)`), this is a no-op so the counter remains an
    /// accurate quiescence signal. New callers that need to know whether the request
    /// was admitted should call `tryEnter()` instead.
    @Contract
    public void enter() {
        if (!acceptingNewWork.get()) {return;}
        counter.incrementAndGet();
    }

    /// Gate-aware entry: returns `true` if the request was admitted and the counter
    /// incremented; returns `false` if the node has begun self-draining and the
    /// caller MUST reject the request (HTTP 503, abort handler).
    public boolean tryEnter() {
        if (!acceptingNewWork.get()) {return false;}

        counter.incrementAndGet();

        return true;
    }

    @Contract
    public void exit() {
        var updated = counter.updateAndGet(v -> Math.max(0, v - 1));
        if (updated == 0 && !acceptingNewWork.get()) {fireDrainCallbackIfPending();}
    }

    public int count() {
        return counter.get();
    }

    /// Returns `true` when the node is admitting new requests; `false` after
    /// `setAcceptingNewWork(false)` has been called by the self-drain coordinator.
    public boolean isAcceptingNewWork() {
        return acceptingNewWork.get();
    }

    /// Flip the gate. `false` is one-way for self-drain (the coordinator never re-opens
    /// the gate — the JVM exits at the end of the drain), but the method is reversible
    /// for tests and operator-initiated graceful drain flows that may want to abort.
    @Contract
    public void setAcceptingNewWork(boolean accepting) {
        acceptingNewWork.set(accepting);
        if (!accepting && counter.get() == 0) {fireDrainCallbackIfPending();}
    }

    /// Register a single callback that fires once when the in-flight count reaches zero
    /// AFTER `setAcceptingNewWork(false)` was called. Subsequent re-registrations
    /// replace the callback but do not re-arm a previously-fired notification.
    @Contract
    public void onAllDrained(Runnable callback) {
        onAllDrained.set(callback);
        if (!acceptingNewWork.get() && counter.get() == 0) {fireDrainCallbackIfPending();}
    }

    private void fireDrainCallbackIfPending() {
        var callback = onAllDrained.get();

        if (callback == null) {return;}
        if (!drainCallbackFired.compareAndSet(false, true)) {return;}

        callback.run();
    }
}
