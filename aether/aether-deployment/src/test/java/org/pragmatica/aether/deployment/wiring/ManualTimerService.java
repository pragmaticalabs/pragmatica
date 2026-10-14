// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.wiring;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/// A timer service driven by the test: nothing runs until `advance` or `run`, and a cancelled body is skipped by `advance` but can still
/// be run by hand with `run`, which is how a body that was already executing when its timer was replaced is simulated.
final class ManualTimerService implements FsmTimers.TimerService {
    static final class Entry {
        final Runnable body;
        final long delayMs;
        final long dueMs;
        boolean cancelled;
        boolean ran;

        Entry(Runnable body, long delayMs, long dueMs) {
            this.body = body;
            this.delayMs = delayMs;
            this.dueMs = dueMs;
        }
    }

    final List<Entry> entries = new ArrayList<>();
    long nowMs;

    @Override
    public Runnable schedule(Runnable body, long delayMs) {
        var entry = new Entry(body, delayMs, nowMs + delayMs);

        entries.add(entry);

        return () -> entry.cancelled = true;
    }

    /// Runs, in due order, every entry that is not cancelled and falls due by now + `ms`.
    void advance(long ms) {
        var target = nowMs + ms;

        entries.stream()
               .filter(entry -> !entry.cancelled && !entry.ran && entry.dueMs <= target)
               .sorted(Comparator.comparingLong(entry -> entry.dueMs))
               .toList()
               .forEach(ManualTimerService::run);
        nowMs = target;
    }

    static void run(Entry entry) {
        entry.ran = true;
        entry.body.run();
    }

    Entry last() {
        return entries.getLast();
    }

    long armedCount() {
        return entries.stream().filter(entry -> !entry.cancelled && !entry.ran).count();
    }
}
