// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskKey;
import org.pragmatica.lang.Unit;


/// What the scheduler tells the node about a fire that is still in flight when its next tick arrives (#1930).
///
/// A task whose previous fire has not resolved skips the tick (up to the completion bound), so a lost response silences a
/// SINGLE-mode task for up to ten minutes. That is an operator condition, and a per-tick log line is not a way to tell the
/// operator: the scheduler reports it ONCE per in-flight fire, and again when that fire resolves.
///
/// Both calls are made on the node whose scheduler holds the fire: the fact is local to it, not derived from a committed
/// record, so the receiver publishes it from there.
public interface ScheduledFireObserver {
    /// The first tick skipped for the fire that started at `fireStartedAt` and has been in flight `inFlightMs`. Called at
    /// most once per fire, however many ticks it then makes the task skip.
    Unit onFireHeld(ScheduledTaskKey task, long fireStartedAt, long inFlightMs);
    /// That fire resolved after `inFlightMs`. Called only for a fire [#onFireHeld] was called for. `outcome` is `executed`,
    /// `failed`, `unknown` (the completion bound passed with no response) or `completed` (released by a manual trigger).
    Unit onFireReleased(ScheduledTaskKey task, long fireStartedAt, long inFlightMs, String outcome);

    ScheduledFireObserver NONE = new ScheduledFireObserver() {
        @Override
        public Unit onFireHeld(ScheduledTaskKey task, long fireStartedAt, long inFlightMs) {
            return Unit.unit();
        }

        @Override
        public Unit onFireReleased(ScheduledTaskKey task, long fireStartedAt, long inFlightMs, String outcome) {
            return Unit.unit();
        }
    };
}
