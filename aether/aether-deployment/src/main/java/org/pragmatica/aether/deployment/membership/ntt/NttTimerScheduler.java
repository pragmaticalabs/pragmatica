// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.ntt;

import org.pragmatica.lang.io.TimeSpan;

import java.util.concurrent.ScheduledFuture;


/// Scheduler indirection for [`PresenceSampler`]. Production wiring binds this to
/// `SharedScheduler::schedule`; tests provide a deterministic manual scheduler so timer
/// fire is driven by an explicit tick rather than wall-clock advancement.
///
/// Returns a [`ScheduledFuture`] so presence sampler can call `cancel(false)` to evict a still-pending
/// timer atomically with the map removal.
@FunctionalInterface
public interface NttTimerScheduler {
    ScheduledFuture<?> schedule(Runnable runnable, TimeSpan delay);
}
