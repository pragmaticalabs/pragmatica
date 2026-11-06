// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.deployment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.pragmatica.aether.deployment.wiring.FsmTimers.TimerService;

/// A timer service driven by the test: nothing runs until `advance` or `fire`.
final class ManualClock implements TimerService {
    private record Armed(Runnable body, long dueMs, long delayMs) {}

    private final List<Armed> armed = new ArrayList<>();
    private long nowMs;

    @Override
    public synchronized Runnable schedule(Runnable body, long delayMs) {
        var entry = new Armed(body, nowMs + delayMs, delayMs);

        armed.add(entry);

        return () -> cancel(entry);
    }

    private synchronized void cancel(Armed entry) {
        armed.remove(entry);
    }

    /// Runs, in due order, every body that falls due by now + `ms`; a body may arm another, which runs too if it falls due.
    void advance(long ms) {
        var target = nowMs + ms;

        for (var next = nextDue(target); next != null; next = nextDue(target)) {
            nowMs = next.dueMs();
            remove(next);
            next.body().run();
        }
        nowMs = target;
    }

    /// Runs every armed body now, regardless of its due time.
    void fire() {
        List.copyOf(snapshot()).forEach(entry -> {
            remove(entry);
            entry.body().run();
        });
    }

    long armedCount() {
        return snapshot().size();
    }

    List<Long> armedDelays() {
        return snapshot().stream().map(Armed::delayMs).toList();
    }

    private synchronized Armed nextDue(long target) {
        return armed.stream().filter(entry -> entry.dueMs() <= target).min(Comparator.comparingLong(Armed::dueMs)).orElse(null);
    }

    private synchronized void remove(Armed entry) {
        armed.remove(entry);
    }

    private synchronized List<Armed> snapshot() {
        return new ArrayList<>(armed);
    }
}
